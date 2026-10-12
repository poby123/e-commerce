# e-commerce (Spring MSA)

주문·재고·결제·쿠폰이 서로 다른 서비스로 나뉜 쇼핑몰 백엔드.
분산 트랜잭션(Saga + Outbox), 동시성(선착순 재고·쿠폰), 장애 격리, 관측성을 직접 구현하고 숫자로 검증하는 것이 목표다.

## 아키텍처

> 전체 설계도(서비스 경계, 흐름별 트랜잭션, 토픽·파티션·컨슈머 그룹, 정합성 장치): [docs/architecture.md](docs/architecture.md)

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
| `services/inventory-service` | 8083 | ✅ 조건부 UPDATE 차감, Saga 참여자(ReserveStock/ReleaseStock) |
| `services/order-service` | 8084 | ✅ 주문 접수 + **OrderSaga 오케스트레이터**, 타임아웃 잡 |
| `services/coupon-service` | 8085 | ✅ 정책·발급, Saga 참여자(예약/확정/복구), 가입 축하 쿠폰(이벤트 구독) |
| `services/payment-service` | 8086 | ✅ Mock PG(`tok_approve`/`tok_decline`/`tok_slow`), 멱등 결제·취소 |
| `services/search-service` | 8087 | ✅ Elasticsearch 읽기 모델(CQRS): 한국어 검색(Nori), **하이브리드(BM25 + e5 벡터, RRF)**, 다중 facet, 자동완성, 무중단 재색인 |
| `messaging` | — | Transactional Outbox, 멱등 Consumer, DLT, 메시지 계약 |
| Kafka 3.9 (KRaft) + Debezium 3.0 | 29092 / 18083 | ✅ Outbox 테이블 → binlog → Kafka |
| Elasticsearch 8.18 + Nori | 9200 | ✅ `docker/elasticsearch` (compose로 빌드) |
| Grafana / Prometheus / Zipkin / Loki | 3000 / 9090 / 9411 / 13100 | ✅ 대시보드, Eureka로 대상 자동 발견, 서비스 간 트레이스, traceId로 로그 검색 |
| `common` | — | 공통 에러 포맷, `AuthHeaders`, `@CurrentUserId` |

## 스택

Java 21 · Spring Boot 3.5 · Spring Cloud 2025.0 · Gradle 8.14 (Kotlin DSL, convention plugin) · MySQL 8.4 · Redis 7.4 · Flyway · Testcontainers
버전은 "1~2년 검증된 안정 버전" 기준. 근거는 [ADR-0005](docs/adr/0005-versions.md).

## 실행

필요한 것: Docker. JDK는 없어도 된다(Gradle toolchain이 JDK 21을 받아온다). 검색용 임베딩 모델(135MB)은 첫 빌드 때 `models/`에 자동으로 받는다.

```bash
./gradlew build                        # 컴파일 + 테스트 (Testcontainers로 실제 MySQL/Redis 사용)
./gradlew bootJar
docker compose --profile app up -d --build
scripts/smoke-test.sh                  # gateway를 통한 E2E 31개 항목 (인증 + 주문 Saga)
scripts/demo-failures.sh               # 장애 재현: Debezium 중단(유실 없음), inventory 정지(타임아웃 → 보상)
load-test/run.sh                       # 4단계 부하 테스트(k6, 약 2분) → load-test/results/summary.md
scripts/demo-resilience.sh             # product 정지 → Circuit OPEN(0.01초 실패) → 자동 복구, 로그인 Rate Limit
scripts/seed-products.sh               # 한국어 상품 35개(검색 시연용)
scripts/demo-reindex.sh                # 검색·수정이 계속되는 중에 무중단 재색인
python3 search-eval/evaluate.py        # 키워드/시맨틱/하이브리드 검색 품질 비교(Recall@5, MRR)
```

- Grafana: http://localhost:3000 (익명 조회 가능, admin/admin). 로그 패널의 traceId를 누르면 Zipkin 트레이스로 이동
- Zipkin: http://localhost:9411 · Prometheus: http://localhost:9090
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
| POST | `/api/orders` | Bearer | 주문 접수(202, PENDING). 본문: `items`, `paymentToken`, 선택 `userCouponId`. `Idempotency-Key` 헤더 지원. 결과(COMPLETED / CANCELLED+사유)는 조회로 확인 |
| POST | `/api/coupon-policies` | ADMIN | 쿠폰 정책 생성(FIXED/RATE, 최소주문금액, 수량) |
| POST | `/api/coupon-policies/{id}/issue` | Bearer | 쿠폰 발급(1인 1매, 수량 제한) |
| POST | `/api/coupon-policies/{id}/claim` | Bearer | 선착순 쿠폰(`firstCome` 정책). Redis에서 즉시 판정(202/409), DB에는 비동기 저장 |
| GET | `/api/coupons/me` | Bearer | 내 쿠폰 |
| GET | `/api/orders`, `/api/orders/{orderId}` | Bearer | 내 주문 목록·상세(남의 주문은 404) |
| GET | `/api/search/products` | – | 검색. `q`, `category`·`brand`(여러 개), `minPrice`/`maxPrice`, `sort`(RELEVANCE/PRICE_ASC/PRICE_DESC/NEWEST), `mode`(KEYWORD/SEMANTIC/**HYBRID**). facet 포함 |
| GET | `/api/search/autocomplete` | – | 자동완성(`q`) |

서비스 간 API는 `/internal/**`이고 gateway 라우트가 없어 외부에서 호출할 수 없다.

에러는 모든 서비스와 gateway가 같은 형식을 쓴다: `{"code":"AUTH-001","message":"...","errors":[],"timestamp":"..."}`

## 로드맵

- [x] **1. 골격** — 멀티모듈, Eureka, Config, Gateway(JWT), user-service
- [x] **2. 상품·재고·주문** — 동기 호출로 주문 1건이 끝까지 성공. 타임아웃 시 PENDING에 묶이는 문제를 재현해 둠([ADR-0007](docs/adr/0007-sync-order-flow-gap.md))
- [x] **3. 쿠폰 + 하이브리드 Saga** — OrderSaga 오케스트레이터(쿠폰→재고→결제, 역순 보상), Outbox(Debezium), 멱등 Consumer, Mock PG, 가입 축하 쿠폰(코레오그래피). [ADR-0008](docs/adr/0008-order-saga-implementation.md)
- [x] **4. 동시성** — 재고 차감 4방식 k6 비교, 선착순 쿠폰(Redis Lua + Kafka) 처리량 3.2배. [ADR-0009](docs/adr/0009-concurrency-strategies.md)
- [x] **5. 장애 격리·관측성** — Resilience4j(order→product, Gateway), Redis Rate Limiter, 주문 하나가 6개 서비스를 거치는 하나의 트레이스, Prometheus/Grafana, Loki. [ADR-0010](docs/adr/0010-resilience-and-observability.md)
- [x] **6a. 검색(CQRS)** — Elasticsearch + Nori, external version 기반 멱등 색인, 다중 facet, 자동완성, 무중단 재색인. [ADR-0011](docs/adr/0011-search-cqrs.md)
- [x] **6b. 하이브리드 검색** — 로컬 e5 임베딩(ONNX) + kNN + 애플리케이션 RRF. 의도 검색 Recall@5 0 → 0.53~0.69, 정확 검색 유지. [ADR-0012](docs/adr/0012-hybrid-search.md)
- [ ] 7. (선택) K8s/Helm · GitHub Actions

## 설계 결정 (ADR)

1. [서비스 분리와 모노레포](docs/adr/0001-service-boundaries-and-monorepo.md)
2. [하이브리드 Saga](docs/adr/0002-hybrid-saga.md)
3. [Transactional Outbox](docs/adr/0003-transactional-outbox.md)
4. [인증: Gateway에서 JWT 검증](docs/adr/0004-authentication-at-gateway.md)
5. [버전 선택](docs/adr/0005-versions.md)
6. [재고 예약: 조건부 UPDATE + orderId 예약 기록](docs/adr/0006-inventory-reservation.md)
7. [동기 주문 흐름과 의도적으로 남긴 문제](docs/adr/0007-sync-order-flow-gap.md) — 3단계에서 해결
8. [OrderSaga 구현](docs/adr/0008-order-saga-implementation.md)
9. [동시성: 재고 차감 방식 비교와 선착순 쿠폰](docs/adr/0009-concurrency-strategies.md)
10. [장애 격리와 관측성](docs/adr/0010-resilience-and-observability.md)
11. [상품 검색: Elasticsearch 읽기 모델 (CQRS)](docs/adr/0011-search-cqrs.md)
12. [하이브리드 검색 (BM25 + 벡터, RRF)](docs/adr/0012-hybrid-search.md)

개발 중 겪은 문제와 해결: [docs/troubleshooting.md](docs/troubleshooting.md)
