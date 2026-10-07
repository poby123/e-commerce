# e-commerce (Spring MSA)

주문·재고·결제·쿠폰이 서로 다른 서비스로 나뉜 쇼핑몰 백엔드.
분산 트랜잭션(Saga + Outbox), 동시성(선착순 재고·쿠폰), 장애 격리, 관측성을 직접 구현하고 숫자로 검증하는 것이 목표다.

## 아키텍처

```
                       ┌────────────────────────┐
  Client ── :8000 ───▶ │ gateway                │  JWT 검증(RS256/JWKS), X-User-Id 주입, 위조 헤더 제거
                       └───────────┬────────────┘
                                   │ lb://  (Eureka)
       ┌──────────┬──────────┬─────┴────┬───────────┬───────────┐
   user-service  product   coupon   inventory    order      payment       ← 2단계부터
   (MySQL,Redis)                                   │ OrderSaga (오케스트레이터)
                                                   └─ Kafka 명령/응답 + Outbox
  infra: discovery-server(Eureka :8761) · config-server(:8888, config-repo/)
```

| 모듈 | 포트 | 상태 |
|---|---|---|
| `infra/discovery-server` | 8761 | ✅ |
| `infra/config-server` | 8888 | ✅ `config-repo/`를 서빙 (native) |
| `infra/gateway` | 8000 | ✅ 외부에 열린 유일한 포트 |
| `services/user-service` | 8081 | ✅ 회원가입·로그인·토큰 재발급·로그아웃 |
| product / coupon / inventory / order / payment / notification | 8082~ | 예정 |
| `common` | — | 공통 에러 포맷, `AuthHeaders`, `@CurrentUserId` |

## 스택

Java 21 · Spring Boot 3.5 · Spring Cloud 2025.0 · Gradle 8.14 (Kotlin DSL, convention plugin) · MySQL 8.4 · Redis 7.4 · Flyway · Testcontainers
버전은 "1~2년 검증된 안정 버전" 기준. 근거는 [ADR-0005](docs/adr/0005-versions.md).

## 실행

필요한 것: Docker. JDK는 없어도 된다(Gradle toolchain이 JDK 21을 받아온다).

```bash
./gradlew build                        # 컴파일 + 테스트 (Testcontainers로 실제 MySQL/Redis 사용)
./gradlew bootJar
docker compose --profile app up -d --build
scripts/smoke-test.sh                  # gateway를 통한 E2E 12개 항목
```

- Eureka 대시보드: http://localhost:8761
- 서비스 설정 확인: http://localhost:8888/user-service/default
- 인프라만 띄우고 서비스는 IDE에서: `docker compose up -d` (MySQL **3307**, Redis **6380** — 로컬에 설치된 MySQL/Redis와 겹치지 않게)

## k8s(k3s) 준비 상태

- 모듈별 이미지, 환경변수 설정, `/actuator/health/{liveness,readiness}`, graceful shutdown(20초) 완료
- JWT 서명 키: 고정 키 필수(없으면 기동 실패). `scripts/generate-jwt-key.sh`로 만들고 Secret으로 마운트
- 남은 일(6단계): Eureka와 Config Server 대신 k8s Service와 ConfigMap 사용, Helm 차트, Ingress

## 설정 원칙

- 서비스 동작 자체에 속한 값(`open-in-view`, `ddl-auto: validate`, 포트)은 각 서비스의 `application.yml`
- 환경마다 바뀌는 값(호스트, 비밀번호, TTL, 라우트)은 `config-repo/<service>.yml`, 공통은 `config-repo/application.yml`

## API (1단계)

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/auth/signup` | – | 회원가입 (201) |
| POST | `/api/auth/login` | – | Access(15분, RS256 JWT) + Refresh(불투명 토큰) 발급 |
| POST | `/api/auth/refresh` | – | Refresh Token Rotation. 재사용이 감지되면 같은 로그인 계열 전체 폐기 |
| POST | `/api/auth/logout` | – | Refresh 토큰 계열 폐기 (204) |
| GET | `/api/users/me` | Bearer | 내 정보 |

에러는 모든 서비스와 gateway가 같은 형식을 쓴다: `{"code":"AUTH-001","message":"...","errors":[],"timestamp":"..."}`

## 로드맵

- [x] **1. 골격** — 멀티모듈, Eureka, Config, Gateway(JWT), user-service
- [ ] **2. 상품·재고·주문** — 동기 호출로 주문 1건이 끝까지 성공
- [ ] **3. 쿠폰 + 하이브리드 Saga** — OrderSaga 오케스트레이터(쿠폰→재고→결제, 역순 보상), Outbox(Debezium), 멱등 Consumer, Mock PG
- [ ] **4. 동시성** — 재고 차감 3방식(비관적 락/낙관적 락/Redis Lua) k6 비교, 선착순 쿠폰 발급(Redis Lua + Kafka)
- [ ] **5. 장애 격리·관측성** — Resilience4j, Micrometer Tracing + Zipkin, Prometheus/Grafana, Loki
- [ ] 6. (선택) Elasticsearch CQRS, K8s/Helm, GitHub Actions

## 설계 결정 (ADR)

1. [서비스 분리와 모노레포](docs/adr/0001-service-boundaries-and-monorepo.md)
2. [하이브리드 Saga](docs/adr/0002-hybrid-saga.md)
3. [Transactional Outbox](docs/adr/0003-transactional-outbox.md)
4. [인증: Gateway에서 JWT 검증](docs/adr/0004-authentication-at-gateway.md)
5. [버전 선택](docs/adr/0005-versions.md)
