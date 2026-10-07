#!/usr/bin/env bash
# Zero-downtime reindex (ADR-0011): searches and product updates keep running while the index is rebuilt with a
# new mapping and the read alias is swapped. Expect 0 failed searches and no lost update.
set -euo pipefail
cd "$(dirname "$0")/.."
B=${1:-http://localhost:8000}; J='Content-Type: application/json'
ADMIN=$(curl -s -X POST $B/api/auth/login -H "$J" -d '{"email":"admin@ecommerce.local","password":"admin1234!"}' | jq -r .accessToken)
in_net() { docker compose exec -T gateway curl -s "$@"; }
alias_index() { in_net "http://elasticsearch:9200/_alias/products" | jq -r 'keys[0]'; }
suggest() { curl -s "$B/api/search/autocomplete?q=$(jq -rn --arg q "$1" '$q|@uri')" | jq -c .; }

PID=$(curl -s "$B/api/search/products?q=$(jq -rn '"가습기"|@uri')" | jq -r '.items[0].productId')
echo "before: read alias -> $(alias_index), autocomplete 무선블루 -> $(suggest 무선블루)"

# Background load: a search every 50 ms, counting failures.
LOG=$(mktemp); ( while [[ ! -f $LOG.stop ]]; do curl -s -o /dev/null -w '%{http_code}\n' "$B/api/search/products?q=$(jq -rn '"이어폰"|@uri')" >> $LOG; sleep 0.05; done ) &
# Product updates during the rebuild: the last one must survive the backfill.
( for p in 41000 42000 43000 44000; do curl -s -o /dev/null -X PATCH "$B/api/products/$PID" -H "$J" -H "Authorization: Bearer $ADMIN" -d "{\"price\":$p}"; sleep 0.7; done ) &
UPDATES=$!

echo "reindex: $(in_net -X POST http://search-service:8087/internal/search/reindex)"
wait $UPDATES; sleep 3; touch $LOG.stop; sleep 0.2

echo "after:  read alias -> $(alias_index), autocomplete 무선블루 -> $(suggest 무선블루)"
echo "searches during the rebuild: $(wc -l < $LOG | tr -d ' ') total, $(grep -vc '^200$' $LOG || true) not 200"
echo "price of product $PID: DB $(curl -s $B/api/products/$PID | jq .price), index $(curl -s "$B/api/search/products?q=$(jq -rn '"가습기"|@uri')" | jq ".items[] | select(.productId==$PID) | .price") (last update 44000)"
rm -f $LOG $LOG.stop
