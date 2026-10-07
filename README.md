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
| `services/user-service` | 8081 | ✅ 회원가입·로그인·토큰 재발급·로그아웃, 관리자 시드 |
| `services/product-service` | 8082 | ✅ 상품 조회(공개)·등록/수정(ADMIN), 주문용 bulk 조회 |
| `services/inventory-service` | 8083 | ✅ 조건부 UPDATE 차감, orderId 예약/복구(멱등) |
| `services/order-service` | 8084 | ✅ 동기 주문(가격 스냅샷, Idempotency-Key) — 3단계에서 Saga로 대체 |
| coupon / payment / notification | 8085~ | 예정 |
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
scripts/smoke-test.sh                  # gateway를 통한 E2E 26개 항목 (인증 + 주문)
```

- Eureka 대시보드: http://localhost:8761
- 서비스 설정 확인: http://localhost:8888/user-service/default
- 관리자 계정(로컬): `admin@ecommerce.local` / `admin1234!` (`config-repo/user-service.yml`)
- 새 서비스 DB를 추가한 뒤에는 `docker compose --profile app down -v`로 볼륨을 새로 만든다(로컬 데이터 삭제됨)
- 인프라만 띄우고 서비스는 IDE에서: `docker compose up -d` (MySQL **3307**, Redis **6380** — 로컬에 설치된 MySQL/Redis와 겹치지 않게)

## k8s(k3s) 준비 상태

- 모듈별 이미지, 환경변수 설정, `/actuator/health/{liveness,readiness}`, graceful shutdown(20초) 완료
- JWT 서명 키: 고정 키 필수(없으면 기동 실패). `scripts/generate-jwt-key.sh`로 만들고 Secret으로 마운트
- 남은 일(6단계): Eureka와 Config Server 대신 k8s Service와 ConfigMap 사용, Helm 차트, Ingress

## 설정 원칙

- 서비스 동작 자체에 속한 값(`open-in-view`, `ddl-auto: validate`, 포트)은 각 서비스의 `application.yml`
- 환경마다 바뀌는 값(호스트, 비밀번호, TTL, 라우트)은 `config-repo/<service>.yml`, 공통은 `config-repo/application.yml`

## API

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/auth/signup` | – | 회원가입 (201) |
| POST | `/api/auth/login` | – | Access(15분, RS256 JWT) + Refresh(불투명 토큰) 발급 |
| POST | `/api/auth/refresh` | – | Refresh Token Rotation. 재사용이 감지되면 같은 로그인 계열 전체 폐기 |
| POST | `/api/auth/logout` | – | Refresh 토큰 계열 폐기 (204) |
| GET | `/api/users/me` | Bearer | 내 정보 |
| GET | `/api/products`, `/api/products/{id}` | – | 판매 중 상품 목록(페이지)·상세 |
| POST / PATCH | `/api/products`, `/api/products/{id}` | ADMIN | 등록·수정(가격, 판매중지) |
| GET / PUT | `/api/inventory/{productId}` | Bearer / ADMIN | 재고 조회·설정 |
| POST | `/api/orders` | Bearer | 주문. 201 확정 / 409 재고 부족 / 202 결과 미확정(PENDING). `Idempotency-Key` 헤더 지원 |
| GET | `/api/orders`, `/api/orders/{orderId}` | Bearer | 내 주문 목록·상세(남의 주문은 404) |

서비스 간 API는 `/internal/**`이고 gateway 라우트가 없어 외부에서 호출할 수 없다.

에러는 모든 서비스와 gateway가 같은 형식을 쓴다: `{"code":"AUTH-001","message":"...","errors":[],"timestamp":"..."}`

## 로드맵

- [x] **1. 골격** — 멀티모듈, Eureka, Config, Gateway(JWT), user-service
- [x] **2. 상품·재고·주문** — 동기 호출로 주문 1건이 끝까지 성공. 타임아웃 시 PENDING에 묶이는 문제를 재현해 둠([ADR-0007](docs/adr/0007-sync-order-flow-gap.md))
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
6. [재고 예약: 조건부 UPDATE + orderId 예약 기록](docs/adr/0006-inventory-reservation.md)
7. [동기 주문 흐름과 의도적으로 남긴 문제](docs/adr/0007-sync-order-flow-gap.md)
