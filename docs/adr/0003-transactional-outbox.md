# ADR-0003 Transactional Outbox

- 상태: 채택 (2026-10-07), 3단계에서 구현

## 결정
- "DB 변경 + 메시지 발행"이 일어나는 모든 지점(order, coupon, inventory, payment)에서 메시지를 같은 DB의 `outbox` 테이블에 쓰고, Debezium(CDC)이 Kafka로 발행한다.
- 받는 쪽은 `processed_message`로 중복을 거른다(멱등 Consumer). Outbox는 at-least-once 전달만 보장하기 때문이다.
- Kafka 파티션 키는 `aggregate_id`(sagaId/orderId)로 둔다. 같은 주문의 메시지끼리는 순서가 보장된다.

## 이유
- DB와 Kafka는 하나의 트랜잭션으로 묶을 수 없다(Kafka는 XA를 지원하지 않는다). 이중 쓰기를 하면 "DB는 커밋됐는데 메시지는 유실"되는 경우가 생긴다. Saga에서는 이게 재고 묶임이나 결제 후 주문 미완료로 이어진다.

## 대안
- Polling Publisher: 단순하지만 지연과 DB 부하가 생긴다. 먼저 이 방식으로 구현해 CDC와 비교하는 것도 고려한다.

## 검증 계획
- Kafka를 내린 상태에서 주문 → Kafka 재기동 → 처리 완료 (유실 없음)
- 같은 메시지 2회 전송 → 효과 1회 (중복 무해)
