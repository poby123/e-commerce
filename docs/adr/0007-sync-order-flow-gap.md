# ADR-0007 동기 주문 흐름과 의도적으로 남긴 문제

- 상태: 2단계 구현, 3단계(Saga)에서 대체 예정 (2026-10-07)

## 지금의 흐름
```
POST /api/orders
 ├─ product-service  GET /internal/products?ids=…   가격 스냅샷 (bulk 1회)
 ├─ [tx] orders INSERT (PENDING)
 ├─ inventory-service POST /internal/reservations   orderId로 예약
 └─ [tx] CONFIRMED  |  재고 부족(409) → REJECTED  |  타임아웃/5xx → PENDING 그대로 (202)
```
- 원격 호출 중에는 DB 트랜잭션을 열어두지 않는다. 느린 서비스가 DB 커넥션과 락을 붙잡지 않게 하기 위해서다.
- `Idempotency-Key` 헤더로 같은 요청은 같은 주문을 돌려준다(따닥 방지). UNIQUE(user_id, idempotency_key)로 동시 요청도 막는다.
- 서비스 간 호출은 Spring HTTP Interface(`@HttpExchange`)와 RestClient로 한다. OpenFeign은 유지보수 모드라 쓰지 않는다. 모든 호출에 타임아웃이 있다.

## 문제 (재현됨)
inventory-service를 `docker compose pause`로 멈추고 주문하면:
- 응답: 202, 주문 `PENDING`
- inventory를 재개한 뒤: **재고는 10 → 6으로 빠졌는데, 주문은 계속 PENDING**

주문 서비스는 타임아웃 시점에 상대가 처리했는지 알 수 없다. 그리고 지금 구조에는 이걸 나중에 확인하거나 되돌리는 주체가 없다. 재고가 영원히 묶인다. 테스트 `OrderPlacementTest#inventoryTimeoutLeavesOrderPending`이 이 상태를 고정해 둔다.

## 3단계에서 바꿀 것
- OrderSaga가 Saga 상태를 저장하고, Outbox를 통해 Kafka 명령으로 진행한다. 응답이 오지 않은 Saga는 타임아웃 스케줄러가 재시도하거나 보상(release)을 실행한다.
- inventory의 orderId 예약 기록과 release 멱등성은 이미 그 전제로 만들어 두었다(ADR-0006).
