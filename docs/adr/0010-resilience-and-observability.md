# ADR-0010 장애 격리와 관측성

- 상태: 채택, 구현 완료 (2026-10-07)
- 재현: `scripts/demo-resilience.sh`. 대시보드는 http://localhost:3000 (E-commerce overview)

## 1. 장애 격리

| 위치 | 적용 | 이유 |
|---|---|---|
| order → product (동기 GET) | `Retry(CircuitBreaker(Bulkhead))`, 타임아웃 1초 | 주문 접수 경로에 남은 유일한 동기 호출. 가격 조회라 재시도해도 안전하다 |
| 그 밖의 서비스 간 통신 | Kafka(Saga). 재시도 → DLT, Saga 타임아웃 | 동기 호출이 없으니 연쇄 장애도 없다 |
| Gateway 라우트마다 | CircuitBreaker + JSON fallback(GATEWAY-503), 연결 2초 / 응답 5초 타임아웃 | 죽은 서비스 앞에서 클라이언트가 타임아웃을 기다리지 않게 한다 |
| Gateway 로그인 | Redis Rate Limiter, IP당 버스트 10 → 초당 1 | 비밀번호 대입 공격 방지 |
| Gateway 주문 | Redis Rate Limiter, 사용자당 버스트 10 → 초당 5 | 한 사용자의 폭주가 다른 사용자 몫을 쓰지 못하게 한다 |

### 결정
- **fallback으로 캐시된 가격을 쓰지 않는다.** 틀린 가격으로 주문받는 것보다 "잠시 후 다시"가 낫다. Circuit Breaker의 목적은 대체 응답이 아니라 **빨리 실패하기**와 **다운스트림 보호**다.
- **4xx는 장애가 아니다.** 비즈니스 응답(상품 없음 등)은 Circuit에 집계하지 않고 재시도하지도 않는다.
- **Gateway의 Circuit은 연결 실패와 타임아웃만 센다.** 서비스가 직접 보낸 503(예: order의 ORDER-005)은 의미 있는 답이므로 fallback으로 덮지 않는다.
- **타임아웃 예산**: 안쪽 타임아웃 × 시도 횟수 + 대기 < 바깥쪽 타임아웃. product 1초 × 2 + 0.1초 ≈ 2.1초 < Gateway 5초. 이 순서가 어긋나면 Gateway가 먼저 포기하고, order-service는 이미 끊긴 요청을 계속 처리한다(troubleshooting #9).
- **재시도 계층은 하나만 둔다.** LoadBalancer 재시도를 끄고 Resilience4j에서만 재시도한다(troubleshooting #10).
- **Rate Limiter는 Redis가 죽으면 요청을 통과시킨다**(Spring 기본값). 가용성을 우선한 선택이다.

### 검증 (`demo-resilience.sh`, product-service 정지)
| 주문 | 응답 | 시간 |
|---|---|---|
| 1~4 | 503 ORDER-005 | 2.14초 (1초 × 2회 + 대기) |
| 5~8 | 503 ORDER-005 | **0.01초** (Circuit OPEN, product 호출 없음) |
| product 재개 후 | 202 | 0.04초 (half-open → closed) |

로그인 15회 연속 실패 시도: 11번째부터 429.

## 2. 관측성

```
서비스 ── trace(OTel bridge) ─▶ Zipkin  ◀─┐
       ── /actuator/prometheus ◀─ Prometheus (Eureka에서 대상 자동 발견) ─▶ Grafana
       ── 로그(loki4j, traceId 포함) ─▶ Loki ──── traceId 클릭 → Zipkin ───┘
```

### 결정
- **Outbox에 traceparent를 저장하고, Debezium이 Kafka 헤더로 옮긴다.** 중계기(Debezium)는 트레이싱이 안 되므로 그대로 두면 메시지마다 트레이스가 끊긴다. 이렇게 하면 주문 하나가 **HTTP → Outbox → Kafka → 참여 서비스 → 응답**까지 하나의 트레이스가 된다. Kafka 리스너와 템플릿의 observation을 켜서 헤더를 읽고 쓴다.
- **Prometheus는 Eureka에서 수집 대상을 찾는다**(`eureka_sd_configs`). 서비스나 replica를 늘려도 설정을 바꿀 필요가 없다.
- **Gateway의 actuator는 별도 포트(8090, 내부 전용)**에 둔다. 공개 포트의 `/actuator/prometheus`는 401이다.
- **비즈니스 메트릭**: `ecommerce_orders_finished_total{result,reason}`, `ecommerce_orders_saga_duration_seconds`, `ecommerce_saga_running`. 기술 지표만으로는 "주문이 왜 취소되는가"를 볼 수 없다.
- **샘플링**: 이 환경은 100%(config-repo). config-server 없이 뜨면 0%. 운영이라면 일부 샘플링에 오류는 전부 남기는 방식(tail sampling)을 쓴다.

### 검증
| 항목 | 결과 |
|---|---|
| 주문 하나의 트레이스 | gateway, order, product, coupon, inventory, payment 6개 서비스, span 16개 |
| 로그 → 트레이스 | Loki의 "Order … COMPLETED"에서 traceId → Zipkin 트레이스 → 같은 traceId의 payment 로그 |
| Prometheus 대상 | 9/9 up (Eureka로 발견한 7개 + 인프라 2개) |
| Grafana | 데이터소스 Prometheus·Loki 정상, 대시보드 자동 등록 |
| 장애 중 지표 | `resilience4j_circuitbreaker_state{state="open"}`가 1 → 복구 후 0 |

## 남은 것
- 알림(Alertmanager) 규칙이 없다. 예: Circuit OPEN 1분 이상, DLT 적재, Saga 진행 중 건수 급증.
- Kafka consumer lag은 클라이언트 지표(`records_lag_max`)만 본다. 그룹 단위 lag은 kafka-exporter가 필요하다.
- Zipkin은 메모리 저장소라 재시작하면 트레이스가 사라진다.
