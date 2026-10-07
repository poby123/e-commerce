#!/usr/bin/env bash
# Step 4 measurements against the running compose stack (docker compose --profile app up -d).
# k6 runs inside the compose network and calls the services directly: the gateway is left out so the numbers
# are about each strategy, not about routing.
# Output: load-test/results/*.json and load-test/results/summary.md
set -euo pipefail
cd "$(dirname "$0")/.."

NET=ecommerce_default
K6="docker run --rm --network $NET -v $PWD/load-test:/scripts:ro -v $PWD/load-test/results:/results grafana/k6:0.57.0"
ROUNDS=${ROUNDS:-3}
STOCK=${STOCK:-3000}; STOCK_REQUESTS=${STOCK_REQUESTS:-20000}; STOCK_VUS=${STOCK_VUS:-200}
COUPONS=${COUPONS:-1000}; COUPON_REQUESTS=${COUPON_REQUESTS:-10000}; COUPON_USERS=${COUPON_USERS:-7000}; COUPON_VUS=${COUPON_VUS:-500}

chmod 777 load-test/results
in_net() { docker compose exec -T gateway curl -s "$@"; }
sql() { docker compose exec -T mysql mysql -uroot -proot -N -e "$1" 2>/dev/null; }

rm -f load-test/results/*.json
echo "== stock: $STOCK units, $STOCK_REQUESTS requests, $STOCK_VUS concurrent, $ROUNDS rounds"
PRODUCT=$((900000 + RANDOM))
for ROUND in $(seq 1 $ROUNDS); do
for STRATEGY in CONDITIONAL PESSIMISTIC OPTIMISTIC REDIS; do
  # Warm-up (JIT, connection pools), not recorded.
  in_net -X POST "http://inventory-service:8083/internal/loadtest/stocks/$PRODUCT/reset?quantity=100000" > /dev/null
  $K6 run -q -e STRATEGY=$STRATEGY -e PRODUCT=$PRODUCT -e ITERATIONS=1000 -e VUS=50 /scripts/stock-strategies.js > /dev/null
  PRODUCT=$((PRODUCT + 1))
  in_net -X POST "http://inventory-service:8083/internal/loadtest/stocks/$PRODUCT/reset?quantity=$STOCK" > /dev/null
  $K6 run -q -e STRATEGY=$STRATEGY -e PRODUCT=$PRODUCT -e ITERATIONS=$STOCK_REQUESTS -e VUS=$STOCK_VUS /scripts/stock-strategies.js | tail -1
  mv load-test/results/stock-$STRATEGY.json load-test/results/stock-$STRATEGY-r$ROUND.json
  echo "{\"finalStock\": $(sql "SELECT quantity FROM inventory_db.stocks WHERE product_id=$PRODUCT")}" > load-test/results/stock-$STRATEGY-r$ROUND.db.json
  PRODUCT=$((PRODUCT + 1))
done
done

echo "== coupons: $COUPONS coupons, $COUPON_REQUESTS requests from $COUPON_USERS users, $COUPON_VUS concurrent, $ROUNDS rounds"
policy() { # firstCome
  in_net -X POST http://coupon-service:8085/api/coupon-policies -H 'Content-Type: application/json' \
    -H 'X-User-Id: 1' -H 'X-User-Roles: ADMIN' \
    -d "{\"code\":\"LT$RANDOM$RANDOM\",\"name\":\"load test\",\"discountType\":\"FIXED\",\"discountValue\":1000,\"minOrderAmount\":0,\"totalQuantity\":$COUPONS,\"validDays\":7,\"firstCome\":$1}" | jq -r .id
}
for ROUND in $(seq 1 $ROUNDS); do
for MODE in db redis; do
  WARM=$(policy $([ $MODE = redis ] && echo true || echo false))
  $K6 run -q -e MODE=$MODE -e POLICY=$WARM -e ITERATIONS=1000 -e VUS=50 -e USERS=1000 /scripts/coupon-burst.js > /dev/null
  POLICY=$(policy $([ $MODE = redis ] && echo true || echo false))
  $K6 run -q -e MODE=$MODE -e POLICY=$POLICY -e ITERATIONS=$COUPON_REQUESTS -e VUS=$COUPON_VUS -e USERS=$COUPON_USERS /scripts/coupon-burst.js | tail -1
  # Redis mode stores asynchronously: wait until the consumer has written every winner.
  mv load-test/results/coupon-$MODE.json load-test/results/coupon-$MODE-r$ROUND.json
  START=$(date +%s); WON=$(jq .won load-test/results/coupon-$MODE-r$ROUND.json)
  for _ in $(seq 1 120); do [[ $(sql "SELECT COUNT(*) FROM coupon_db.user_coupons WHERE policy_id=$POLICY") -ge $WON ]] && break; sleep 0.5; done
  DRAIN=$(( $(date +%s) - START ))
  read ROWS USERS ISSUED <<<"$(sql "SELECT COUNT(*), COUNT(DISTINCT user_id), (SELECT issued_quantity FROM coupon_db.coupon_policies WHERE id=$POLICY) FROM coupon_db.user_coupons WHERE policy_id=$POLICY")"
  echo "{\"rows\": $ROWS, \"distinctUsers\": $USERS, \"issuedQuantity\": $ISSUED, \"drainSec\": $DRAIN}" > load-test/results/coupon-$MODE-r$ROUND.db.json
done
done

python3 load-test/summarize.py "$STOCK" "$COUPONS" "$ROUNDS" "$STOCK_REQUESTS" "$STOCK_VUS" "$COUPON_REQUESTS" "$COUPON_USERS" "$COUPON_VUS" > load-test/results/summary.md
cat load-test/results/summary.md
