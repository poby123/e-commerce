# ADR-0008 OrderSaga 구현

- 상태: 채택, 구현 완료 (2026-10-07). ADR-0002(하이브리드), ADR-0003(Outbox)의 구현 기록이다.

## 흐름
```
POST /api/orders ─ product 가격 조회(동기, 읽기) ─ [tx] orders + order_sagas + outbox(첫 명령) ─ 202 PENDING

RESERVE_COUPON ─CouponReserved→ RESERVE_STOCK ─StockReserved→ PAY ─PaymentCompleted→ COMPLETED
      │ CouponRejected              │ StockRejected               │ PaymentFailed      (ConfirmCoupon, OrderCompleted)
      ▼                             ▼                             ▼
  CANCELLED               ReleaseCoupon, CANCELLED     ReleaseStock + ReleaseCoupon, CANCELLED
```
- 상태 전이는 `OrderSaga`(순수 로직), 저장과 메시지 발행은 `OrderSagaManager`가 맡는다. 응답 하나를 처리할 때 주문 상태, Saga 상태, 다음 명령이 한 트랜잭션으로 커밋된다.
- 토픽: 명령은 실행하는 서비스로(`coupon.command`, `inventory.command`, `payment.command`), 응답은 모두 `order.saga-reply`로, 이벤트는 `user.events`, `order.events`. 키는 sagaId(=orderId)이므로 한 주문의 메시지는 한 파티션에서 순서대로 처리된다.

## 결정과 이유
| 결정 | 이유 |
|---|---|
| 보상 명령은 응답을 기다리지 않는다 | 보상은 멱등하고, Outbox와 컨슈머 재시도(이후 DLT)로 반드시 전달된다. 응답을 기다려도 상태만 늘어날 뿐 결과는 같다 |
| 늦은/중복 응답은 무시한다 | 현재 단계와 맞지 않는 응답은 재전송에 대한 답이거나 Saga 포기 이후의 답이다. 포기할 때 이미 보상을 보냈으므로 효과가 상쇄된다 |
| 타임아웃: 같은 명령 재전송 → `max-attempts` 후 보상 | 참여자가 sagaId 기준으로 멱등하므로 재전송은 안전하다. 포기할 때는 **응답이 안 온 단계도 보상한다**. 성공했는데 응답만 늦은 것일 수 있기 때문이다(결제 단계면 CancelPayment) |
| 보상이 원래 명령을 앞지를 때(tombstone) | inventory, coupon, payment는 모르는 sagaId에 대한 취소를 받으면 기록을 남기고, 늦게 온 예약/결제를 거절한다 |
| 타임아웃 잡: `FOR UPDATE SKIP LOCKED` | order-service가 여러 대여도 서로 다른 행을 집어 가므로 중복 처리나 대기가 없다. 응답 처리와 경합하면 `@Version`(낙관적 락)이 한쪽을 실패시키고 Kafka 재시도가 최신 상태로 다시 처리한다 |
| 결제는 orderId를 PG 멱등키로 쓴다 | 외부 PG는 Outbox로 묶을 수 없다. PG 호출 중에는 DB 트랜잭션을 열지 않고, 재전달된 Pay는 첫 결과를 다시 받는다 |
| 카드번호 대신 PG 토큰(`tok_*`) | 카드번호(PAN)는 받지도 저장하지도 않는다. 재전송에 필요한 토큰만 Saga에 저장한다 |

## 검증
| 시나리오 | 방법 | 결과 |
|---|---|---|
| 모든 분기(정상, 쿠폰 거절, 재고 부족, 결제 거절, 중복/지연 응답, 타임아웃 재전송→보상) | `OrderSagaTest` | 통과 |
| 참여자별 멱등성과 tombstone | `InventoryCommandHandlerTest`, `CouponTest`, `PaymentCommandHandlerTest` | 통과 |
| 실제 스택에서 완료/보상/쿠폰/품절 | `scripts/smoke-test.sh` | 31/31 |
| Debezium 중단 → 재기동 | `scripts/demo-failures.sh` 1 | PENDING이던 주문이 COMPLETED로 완료(유실 없음) |
| inventory 35초 정지 | `scripts/demo-failures.sh` 2 | 10초·20초에 재전송, 30초에 보상, 재개 후 재고 8 → 8 그대로 |

## 남은 것
- Outbox 행은 지우지 않는다. 운영에서는 오래된 행을 배치로 지우거나 파티션으로 관리해야 한다.
- 환불이 PG 호출 직후 크래시로 유실될 수 있다(mock). 실제로는 REFUND_PENDING 상태를 두고 재시도해야 한다.
- DLT로 간 메시지를 재처리하는 운영 도구가 없다.
