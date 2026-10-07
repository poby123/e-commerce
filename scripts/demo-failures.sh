#!/usr/bin/env bash
# Failure scenarios behind ADR-0003 and ADR-0007, against the running compose stack.
#   1. Outbox relay (Debezium) down: the order waits in the outbox and completes when the relay is back.
#   2. inventory-service frozen: the saga resends, gives up, compensates; the stock is whole afterwards.
#      (In step 2 the same situation left the order PENDING with the stock gone.)
set -euo pipefail
cd "$(dirname "$0")/.."
B=${1:-http://localhost:8000}; J='Content-Type: application/json'

ADMIN=$(curl -s -X POST $B/api/auth/login -H "$J" -d '{"email":"admin@ecommerce.local","password":"admin1234!"}' | jq -r .accessToken)
PID=$(curl -s -X POST $B/api/products -H "$J" -H "Authorization: Bearer $ADMIN" -d '{"name":"Demo","price":5000}' | jq -r .id)
curl -s -o /dev/null -X PUT $B/api/inventory/$PID -H "$J" -H "Authorization: Bearer $ADMIN" -d '{"quantity":10}'
order() { curl -s -X POST $B/api/orders -H "$J" -H "Authorization: Bearer $ADMIN" -d "{\"items\":[{\"productId\":$PID,\"quantity\":$1}],\"paymentToken\":\"tok_approve\"}" | jq -r .orderId; }
status() { curl -s $B/api/orders/$1 -H "Authorization: Bearer $ADMIN" | jq -r '"\(.status) \(.failureReason // "")"'; }
stock() { curl -s $B/api/inventory/$PID -H "Authorization: Bearer $ADMIN" | jq -r .quantity; }
# A previous run may have left inventory evicted from Eureka for a few seconds.
for _ in $(seq 1 30); do [[ "$(stock)" =~ ^[0-9]+$ ]] && break; sleep 1; done
pending_outbox() { docker compose exec -T mysql mysql -uroot -proot -N -e "SELECT COUNT(*) FROM order_db.outbox WHERE aggregate_id='$1'" 2>/dev/null; }

echo "== 1. relay down: nothing is lost"
docker compose stop connect > /dev/null 2>&1
O=$(order 2); sleep 3
echo "   relay stopped, order placed:      $(status $O) | stock $(stock) | rows for it in order_db.outbox: $(pending_outbox $O)"
docker compose start connect > /dev/null 2>&1
for _ in $(seq 1 60); do [[ $(status $O) == COMPLETED* ]] && break; sleep 1; done
echo "   relay back:                       $(status $O) | stock $(stock)"

echo "== 2. inventory frozen: the saga times out and compensates"
BEFORE=$(stock)
reservation() { docker compose exec -T mysql mysql -uroot -proot -N -e "SELECT status FROM inventory_db.stock_reservations WHERE order_id='$1'" 2>/dev/null; }
docker compose pause inventory-service > /dev/null 2>&1
O=$(order 3)
for t in 5 15 25 35; do sleep $((t - ${prev:-0})); prev=$t; echo "   t=${t}s  order: $(status $O)"; done
docker compose unpause inventory-service > /dev/null 2>&1
# Wait for the compensation itself: the queued ReserveStock(s) are applied first, then ReleaseStock.
for _ in $(seq 1 60); do [[ "$(reservation $O)" == RELEASED ]] && break; sleep 1; done
echo "   inventory resumed: reservation $(reservation $O) (ReserveStock x3 replayed as one, then ReleaseStock)"
# Frozen longer than its 15 s lease, inventory was evicted from Eureka; the gateway sees it again once it re-registers.
for _ in $(seq 1 30); do [[ "$(stock)" =~ ^[0-9]+$ ]] && break; sleep 1; done
echo "   stock before: $BEFORE, after: $(stock)  (in step 2 the stock stayed taken)"
