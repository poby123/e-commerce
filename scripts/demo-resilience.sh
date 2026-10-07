#!/usr/bin/env bash
# Fault isolation against the running compose stack (ADR-0010).
#   1. product-service frozen: order-service's circuit breaker opens; orders fail in milliseconds instead of
#      waiting for timeouts, then recover by themselves once product-service is back.
#   2. Password guessing: the gateway's per-IP rate limit answers 429 after the burst.
set -euo pipefail
cd "$(dirname "$0")/.."
B=${1:-http://localhost:8000}; J='Content-Type: application/json'
ADMIN=$(curl -s -X POST $B/api/auth/login -H "$J" -d '{"email":"admin@ecommerce.local","password":"admin1234!"}' | jq -r .accessToken)
PID=$(curl -s -X POST $B/api/products -H "$J" -H "Authorization: Bearer $ADMIN" -d '{"name":"CB demo","price":1000}' | jq -r .id)
curl -s -o /dev/null -X PUT $B/api/inventory/$PID -H "$J" -H "Authorization: Bearer $ADMIN" -d '{"quantity":100}'
order() { curl -s -o /tmp/demo-order.json -w '%{http_code} %{time_total}' -X POST $B/api/orders -H "$J" -H "Authorization: Bearer $ADMIN" \
  -d "{\"items\":[{\"productId\":$PID,\"quantity\":1}],\"paymentToken\":\"tok_approve\"}"; }
circuit() { curl -s --data-urlencode 'query=max(resilience4j_circuitbreaker_state{application="order-service",name="product",state="open"})' \
  localhost:9090/api/v1/query | jq -r '.data.result[0].value[1] // "?" | if . == "1" then "OPEN" elif . == "0" then "closed" else . end'; }

# Start from a healthy system: after a redeploy the gateway's own circuit for order-service may still be open.
for _ in $(seq 1 60); do read code secs <<<"$(order)"; [[ $code == 202 ]] && break; sleep 2; done
[[ $code == 202 ]] || { echo "orders are not going through; is the stack up?"; exit 1; }
sleep 6

echo "== 1. product-service frozen"
docker compose pause product-service > /dev/null 2>&1
for i in $(seq 1 8); do
  read code secs <<<"$(order)"
  printf "   order %d: HTTP %s in %5.2fs  %s\n" $i $code $secs "$(jq -r '.code // .status' /tmp/demo-order.json)"
done
sleep 6; echo "   circuit (Prometheus): $(circuit)"
docker compose unpause product-service > /dev/null 2>&1
echo "   product-service resumed; waiting for half-open (10 s) and Eureka re-registration"
for _ in $(seq 1 40); do read code secs <<<"$(order)"; [[ $code == 202 ]] && break; sleep 2; done
printf "   order after recovery: HTTP %s in %.2fs\n" $code $secs
sleep 6; echo "   circuit (Prometheus): $(circuit)"

echo "== 2. password guessing from one IP"
codes=$(for i in $(seq 1 15); do curl -s -o /dev/null -w '%{http_code} ' -X POST $B/api/auth/login -H "$J" -d '{"email":"admin@ecommerce.local","password":"wrong"}'; done)
echo "   15 attempts: $codes"
echo "   -> $(tr ' ' '\n' <<<"$codes" | grep -c 401) answered (401), $(tr ' ' '\n' <<<"$codes" | grep -c 429) rate-limited (429)"
