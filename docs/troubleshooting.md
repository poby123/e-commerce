# 개발 중 겪은 문제

면접에서 "어려웠던 점"으로 쓸 수 있는 실제 사례. 모두 테스트나 재현 스크립트가 있다.

## 1. 재고 복구가 두 번 반영됨 (2단계)
- **증상**: release를 두 번 호출하면 재고가 두 번 돌아왔다(10 → 14).
- **원인**: 재고 UPDATE 쿼리의 `@Modifying(clearAutomatically = true)`가 영속성 컨텍스트를 비우면서, 그 앞에서 바꾼 엔티티 상태(RELEASED)가 저장되지 않았다. 동시 호출에서도 둘 다 RESERVED로 읽는 문제가 있었다.
- **해결**: 상태 전이도 `UPDATE ... WHERE status = 'RESERVED'` 조건부 UPDATE로 바꿨다. 영향받은 행이 1인 쪽만 재고를 돌려준다.
- **검증**: `ReservationTest#concurrentReleasesGiveStockBackOnce`

## 2. 쿠폰 동시 발급 데드락 (3단계)
- **증상**: 수량 5개 정책에 20명이 동시에 발급을 요청하자 `Deadlock found when trying to get lock`.
- **원인**: `user_coupons` INSERT의 외래키 검사가 부모 행(`coupon_policies`)에 공유 락(S)을 건다. 이어지는 수량 UPDATE가 같은 행에 배타 락(X)을 요청하므로, S를 쥔 두 트랜잭션이 서로의 S가 풀리기를 기다렸다.
- **해결**: 정책 행 UPDATE(X 락)를 먼저 하고 INSERT를 나중에 한다.
- **검증**: `CouponTest#limitedPolicyIssuesExactlyItsQuantityUnderConcurrency` (3회 반복 통과)
- **남은 과제**: 모든 발급이 정책 한 행에 직렬화된다. 4단계에서 Redis로 옮긴다.

## 3. 새 서비스가 Gateway에서 1분 가까이 503 (1단계)
- **원인**: Eureka 클라이언트의 레지스트리 갱신 주기(30초)와 LoadBalancer 캐시(35초)가 겹쳤다.
- **해결**: 로컬에서는 갱신 5초, 캐시 5초, lease 5초/15초로 줄였다(`config-repo/application.yml`). 운영에서는 이 값이 트래픽과 레지스트리 부하 사이의 트레이드오프다.
- **관련**: 서비스를 lease 만료(15초)보다 오래 멈추면 Eureka가 인스턴스를 제거한다. 재개 후 재등록될 때까지 몇 초간 503이 난다(`demo-failures.sh`가 이를 기다린다).

## 4. 장애 데모가 "재고 미복구"로 보임 (3단계)
- **증상**: 보상 후 재고가 5로 찍혔다(8이어야 함).
- **원인**: 시스템은 정상이었다. inventory가 멈춰 있는 동안에는 재고가 줄지 않은 상태(8)였고, 스크립트가 "8이 될 때까지 대기"하다가 즉시 통과했다. 그 뒤 예약(…45.61)과 복구(…45.64) 사이의 30ms에 값을 읽었다.
- **교훈**: 비동기 시스템의 검증은 "값이 X가 될 때까지"가 아니라 "이 작업이 끝날 때까지(예약 RELEASED)"를 기다려야 한다.

## 5. Testcontainers Kafka가 시작 직후 종료 (4단계)
- **증상**: `Timed out waiting for log output matching '.*Transitioning from RECOVERY to RUNNING.*'`
- **원인**: 컨테이너 로그에 `advertised.listeners cannot use the nonroutable meta-address 0.0.0.0`. Kafka 3.9부터 이 값을 거부하는데, Testcontainers 1.21의 `KafkaContainer`가 내부 리스너를 0.0.0.0으로 광고한다.
- **해결**: 테스트만 `apache/kafka:3.8.1`을 쓴다(클라이언트 프로토콜 동일). compose는 3.9.0 유지. 이유는 `FirstComeClaimTest`에 주석으로 남겼다.

## 6. 테스트에서 Kafka group.id 누락 (4단계)
- **원인**: group id를 config-repo에만 두었는데, 테스트는 config-server 없이 뜬다.
- **해결**: group id, offset reset, ack 모드는 "환경 값"이 아니라 서비스 동작이므로 각 서비스의 `application.yml`로 옮겼다(README의 설정 원칙). k8s에서 config-server를 빼도 유지된다.

## 7. 첫 부하 측정이 이론과 반대로 나옴 (4단계)
- **증상**: 비관적 락이 조건부 UPDATE보다 빨랐다(측정 1~2초).
- **조치**: 재고 3,000/요청 20,000으로 키우고 3회 반복 중앙값을 냈다. 여전히 둘의 차이는 노이즈 수준이었다. 이론("비관적 락은 느리다")에 맞추지 않고 그대로 기록했다(ADR-0009). 이 환경에서는 행 락 하나와 커넥션 풀 10개가 공통 병목이기 때문이다.

## 8. 테스트 클래스를 추가하자 Circuit이 저절로 열림 (5단계)
- **증상**: 장애 격리 테스트가 단독으로는 통과하고 전체 실행에서는 실패했다. 4xx만 보냈는데 Circuit이 OPEN이 됐다.
- **원인**: Spring은 설정이 같은 테스트 클래스끼리 컨텍스트를 캐시해 재사용한다. 그런데 WireMock(`@RegisterExtension`)은 클래스마다 새 포트로 다시 떴다. 캐시된 컨텍스트가 죽은 포트로 호출했고, 연결 실패가 쌓여 Circuit이 열렸다. Circuit은 정확히 동작한 것이다.
- **해결**: WireMock을 JVM당 하나(static)만 띄우고, 테스트마다 스텁만 초기화한다.

## 9. 장애 중 Gateway가 order-service보다 먼저 포기함 (5단계)
- **증상**: product를 멈추자 주문이 5초 만에 GATEWAY-503으로 끝났다. order-service의 응답(ORDER-005)은 클라이언트에게 가지 못했다.
- **원인**: 안쪽(order → product: 2초 × 재시도)이 바깥쪽(Gateway 5초)보다 길었다.
- **해결**: 타임아웃 예산을 맞췄다. product 1초 × 2 + 0.1초 < Gateway 5초.

## 10. 재시도가 2배로 일어남 (5단계)
- **증상**: 타임아웃을 1초로 줄였는데 실패까지 4.1초가 걸렸다.
- **원인**: spring-kafka가 spring-retry를 끌고 들어오고, spring-retry가 클래스패스에 있으면 Spring Cloud LoadBalancer가 GET의 I/O 오류를 스스로 재시도한다. Resilience4j 2회 × LoadBalancer 2회 = 4회 호출이었다. 장애 상황에서 다운스트림 부하를 키우는 재시도 증폭이다.
- **해결**: `spring.cloud.loadbalancer.retry.enabled=false`. 재시도는 Resilience4j 한 곳에서만 한다.
- **검증**: `aFailingLookupIsCalledExactlyTwiceNotMore`. 수정 전에는 "4회 호출"로 실패하고, 수정 후에는 통과한다. 처음에는 500 응답으로 테스트해서 문제를 잡지 못했다. LoadBalancer는 I/O 오류만 재시도하기 때문에, 타임아웃으로 바꿔서 잡아냈다.

## 11. Gateway 메트릭이 401
- **원인**: Gateway의 보안 필터가 `/actuator/prometheus`에도 인증을 요구했다. 경로를 공개하면 외부에 메트릭이 노출된다.
- **해결**: actuator를 내부 전용 관리 포트(8090)로 분리하고, 그 포트로 들어온 요청만 허용한다. Prometheus는 Eureka 메타데이터의 `management.port`로 주소를 바꿔서 수집한다.

## 12. 재시작 직후 Debezium 커넥터 등록 실패
- **원인**: Kafka Connect가 헬스체크(`GET /connectors`)에는 응답하지만, 내부 리밸런싱 중에는 설정 변경을 거절했다. 그 결과 `connect-init`이 실패했고, 거기에 의존하는 서비스 9개가 "Created" 상태로 멈췄다.
- **해결**: 등록 스크립트가 재시도하고, 커넥터 task가 RUNNING이 될 때까지 기다린다.

## 13. `docker build`만 권한 오류 (6단계)
- **증상**: `open ~/.docker/buildx/activity/desktop-linux: permission denied`
- **원인**: 그 파일의 소유자가 root였다(과거에 sudo로 docker를 실행한 흔적). compose 빌드는 다른 경로를 써서 영향이 없었다.
- **조치**: ES 이미지는 compose 서비스로 정의해 compose로 빌드하고, 테스트는 Testcontainers가 Docker API로 직접 빌드한다. 근본 해결은 `sudo chown $(whoami) ~/.docker/buildx/activity/desktop-linux`(사용자 확인 필요).

## 14. 한국어 검색 사전 (6단계)
사용자 사전 누락, 동의어 분석 실패, 동의어 과확장, 자동완성 재분석과 띄어쓰기 문제. ADR-0011의 표에 정리했다. 원칙은 두 가지다. **동의어에 쓰는 단어는 사용자 사전에도 등록한다. 입력 중인 글자는 형태소 분석하지 않는다.**

## 15. ES 클라이언트가 `Instant`를 직렬화하지 못함
- **원인**: Elasticsearch Java 클라이언트는 Boot의 ObjectMapper가 아니라 자체 기본 매퍼를 썼다.
- **해결**: Boot ObjectMapper의 복사본으로 `JacksonJsonpMapper` 빈을 만들었다.

## 16. 오래된 이벤트 무시가 예외로 터짐
- **원인**: 버전 충돌(409)이 `ElasticsearchException`이 아니라 저수준 `ResponseException`으로 올라왔다. 이 예외는 `IOException`의 하위 타입이라 catch에 걸리지 않았다.
- **해결**: 세 가지 형태(ElasticsearchException, TransportException, ResponseException)를 모두 409로 판별한다. `anOlderOrRepeatedEventNeverOverwritesANewerOne`이 고정한다.

## 17. 하이브리드를 기본값으로 바꾸자 E2E가 실패 (6b)
- **증상**: 고유한 상품명으로 검색했는데 3건이 나왔다.
- **원인**: 하이브리드는 의미가 비슷한 다른 상품(이전 테스트가 남긴 같은 이름 패턴)도 함께 돌려준다. 버그가 아니라 의도된 동작 변화다.
- **조치**: "색인됐는가"는 KEYWORD로 확인하고, 하이브리드에서는 그 상품이 1위인지 확인한다. 기본 모드를 바꾸면 기존 검증의 전제도 바뀐다는 교훈.

## 18. 재시작 직후 단건 확인만 실패
- **증상**: search-service를 재시작한 직후 자동완성 확인만 실패했다. 재시도 루프가 있는 다른 확인은 통과했다.
- **원인**: 새 IP로 뜬 인스턴스를 Gateway가 아직 몰라서 잠깐 fallback 503이 났다(troubleshooting #3).
- **조치**: 결과적 일관성인 시스템의 확인은 모두 재시도와 함께 한다(#4와 같은 교훈).
