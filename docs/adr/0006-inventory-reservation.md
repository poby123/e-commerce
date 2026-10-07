# ADR-0006 재고 예약: 조건부 UPDATE + orderId 예약 기록

- 상태: 채택, 구현 완료 (2026-10-07)

## 결정
- 차감은 `UPDATE stocks SET quantity = quantity - ? WHERE product_id = ? AND quantity >= ?` 한 문장으로 한다. 영향받은 행이 0이면 재고 부족이다.
- 한 주문의 여러 상품은 **productId 순서로** 차감한다.
- 주문마다 `stock_reservations(order_id, product_id)`를 UNIQUE로 남긴다. 예약 기록을 먼저 INSERT하고 나서 차감한다.
- 복구(release)도 `UPDATE ... SET status='RELEASED' WHERE id=? AND status='RESERVED'`가 성공한 경우에만 재고를 돌려준다.
- `CHECK (quantity >= 0)`을 마지막 방어선으로 둔다.

## 이유
- 조건부 UPDATE는 InnoDB가 그 행에 락을 잡고 최신 값으로 조건을 다시 평가한다. 별도 락이나 재시도 루프 없이 초과판매가 없다.
- 정렬하지 않으면 {A,B} 주문과 {B,A} 주문이 서로 반대 순서로 락을 잡아 데드락이 난다.
- 호출자(order)는 타임아웃이 나면 처리 여부를 알 수 없어 재시도한다. orderId 예약 기록이 있으면 같은 요청은 한 번만 반영된다. 같은 orderId가 동시에 두 번 와도 UNIQUE 키에서 한쪽이 막히므로 재고는 한 번만 빠진다.

## 검증 (`ReservationTest`)
| 시나리오 | 결과 |
|---|---|
| 재고 50, 100명 동시 주문 | 정확히 50건 성공, 재고 0 |
| {A,B}/{B,A} 주문 60건 동시 | 데드락 0건 |
| 같은 orderId 10번 동시 | 1번만 차감 |
| release 10번 동시 | 1번만 복구 |
| 2개 중 1개 부족 | 전체 롤백 |

## 개발 중 발견한 버그
- 처음에는 release를 "엔티티를 읽고 상태 변경"으로 구현했다. 그런데 재고 UPDATE 쿼리(`clearAutomatically`)가 영속성 컨텍스트를 비우면서 상태 변경이 저장되지 않았고, 두 번 호출하면 두 번 복구됐다. 동시 호출에서도 같은 문제가 있었다. 차감과 같은 조건부 UPDATE로 바꿔 두 문제를 함께 해결했다.

## 이후
- 4단계에서 이 방식을 비관적 락, 낙관적 락, Redis Lua와 k6로 비교한다.
- Saga에서 release가 reserve보다 먼저 도착하는 경우(순서 역전)는 3단계에서 다룬다. 지금은 release가 아무것도 하지 않고, 늦게 온 reserve가 재고를 가져간다.
