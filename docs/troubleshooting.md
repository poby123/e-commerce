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
