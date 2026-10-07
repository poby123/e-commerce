#!/usr/bin/env bash
# End-to-end check through the gateway.
#   auth:  signup -> login -> authenticated call -> refresh rotation -> logout
#   order: saga completes, idempotent retry, welcome coupon (choreography), declined card compensates
#          stock and coupon, coupon discount, sold out
# Usage: scripts/smoke-test.sh [gateway-url]   (default http://localhost:8000)
set -euo pipefail

BASE=${1:-http://localhost:8000}
EMAIL="smoke-$(date +%s)-$RANDOM@example.com"
PASS=0

check() { # name expected actual
  if [[ "$2" == "$3" ]]; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1 (expected $2, got $3)"; exit 1; fi
}
call() { # method path [body] [extra curl args...] -> prints "status body"
  local method=$1 path=$2 body=${3:-}; shift 3 || shift $#
  local out; out=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" -H 'Content-Type: application/json' ${body:+-d "$body"} "$@")
  STATUS=$(tail -n1 <<<"$out"); BODY=$(sed '$d' <<<"$out")
}

echo "gateway: $BASE"
call POST /api/auth/signup "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"name\":\"Smoke\"}"
check "signup" 201 "$STATUS"
USER_ID=$(jq -r .id <<<"$BODY")

call POST /api/auth/login "{\"email\":\"$EMAIL\",\"password\":\"password123\"}"
check "login" 200 "$STATUS"
ACCESS=$(jq -r .accessToken <<<"$BODY"); REFRESH=$(jq -r .refreshToken <<<"$BODY")

call GET /api/users/me "" -H "Authorization: Bearer $ACCESS"
check "GET /users/me with token" 200 "$STATUS"
check "token subject is the new user" "$USER_ID" "$(jq -r .id <<<"$BODY")"

call GET /api/users/me ""
check "GET /users/me without token" 401 "$STATUS"

call GET /api/users/me "" -H "X-User-Id: $USER_ID"
check "spoofed X-User-Id without token" 401 "$STATUS"

call GET /api/users/me "" -H "Authorization: Bearer ${ACCESS}tampered"
check "tampered token" 401 "$STATUS"

call POST /api/auth/refresh "{\"refreshToken\":\"$REFRESH\"}"
check "refresh" 200 "$STATUS"
NEW_REFRESH=$(jq -r .refreshToken <<<"$BODY")

call POST /api/auth/refresh "{\"refreshToken\":\"$REFRESH\"}"
check "reusing a rotated refresh token" 401 "$STATUS"
call POST /api/auth/refresh "{\"refreshToken\":\"$NEW_REFRESH\"}"
check "reuse revoked the newer token too" 401 "$STATUS"

call POST /api/auth/login "{\"email\":\"$EMAIL\",\"password\":\"password123\"}"
REFRESH=$(jq -r .refreshToken <<<"$BODY")
call POST /api/auth/logout "{\"refreshToken\":\"$REFRESH\"}"
check "logout" 204 "$STATUS"
call POST /api/auth/refresh "{\"refreshToken\":\"$REFRESH\"}"
check "refresh after logout" 401 "$STATUS"

echo "-- order flow (saga)"
wait_order() { # orderId expected-status -> leaves the order JSON in BODY
  for _ in $(seq 1 40); do
    call GET "/api/orders/$1" "" -H "Authorization: Bearer $ACCESS"
    [[ "$(jq -r .status <<<"$BODY")" == "$2" ]] && return 0
    sleep 0.5
  done
  return 0
}
order() { # productId quantity token [couponId] [idempotencyKey]
  local coupon=${4:+,\"userCouponId\":$4}
  call POST /api/orders "{\"items\":[{\"productId\":$1,\"quantity\":$2}],\"paymentToken\":\"$3\"$coupon}" \
    -H "Authorization: Bearer $ACCESS" ${5:+-H "Idempotency-Key: $5"}
}

call POST /api/auth/login "{\"email\":\"$EMAIL\",\"password\":\"password123\"}"
ACCESS=$(jq -r .accessToken <<<"$BODY")
call POST /api/auth/login '{"email":"admin@ecommerce.local","password":"admin1234!"}'
check "bootstrap admin login" 200 "$STATUS"
ADMIN=$(jq -r .accessToken <<<"$BODY")

call POST /api/products '{"name":"Smoke mug","price":12000}' -H "Authorization: Bearer $ACCESS"
check "non-admin cannot register a product" 403 "$STATUS"
call POST /api/products '{"name":"Smoke mug","price":12000}' -H "Authorization: Bearer $ADMIN"
check "admin registers a product" 201 "$STATUS"
PRODUCT_ID=$(jq -r .id <<<"$BODY")
call PUT "/api/inventory/$PRODUCT_ID" '{"quantity":5}' -H "Authorization: Bearer $ADMIN"
check "admin sets stock to 5" 200 "$STATUS"
call GET "/api/products/$PRODUCT_ID" ""
check "product page is public" 200 "$STATUS"

order "$PRODUCT_ID" 2 tok_approve
check "order is accepted (saga started)" 202 "$STATUS"
ORDER_ID=$(jq -r .orderId <<<"$BODY")
wait_order "$ORDER_ID" COMPLETED
check "saga completes: coupon-less order paid" COMPLETED "$(jq -r .status <<<"$BODY")"
check "paid the full price" 24000 "$(jq -r .paymentAmount <<<"$BODY")"

IDEM="retry-$RANDOM-$RANDOM"
order "$PRODUCT_ID" 1 tok_approve "" "$IDEM"; FIRST=$(jq -r .orderId <<<"$BODY")
order "$PRODUCT_ID" 1 tok_approve "" "$IDEM"
check "same Idempotency-Key returns the same order" "$FIRST" "$(jq -r .orderId <<<"$BODY")"
wait_order "$FIRST" COMPLETED

call GET /api/coupons/me "" -H "Authorization: Bearer $ACCESS"
for _ in $(seq 1 20); do [[ "$(jq length <<<"$BODY")" -ge 1 ]] && break; sleep 0.5; call GET /api/coupons/me "" -H "Authorization: Bearer $ACCESS"; done
check "signup event issued the WELCOME coupon (choreography)" WELCOME "$(jq -r '.[0].code' <<<"$BODY")"
COUPON_ID=$(jq -r '.[0].id' <<<"$BODY")

order "$PRODUCT_ID" 1 tok_decline "$COUPON_ID"; DECLINED=$(jq -r .orderId <<<"$BODY")
wait_order "$DECLINED" CANCELLED
check "declined card cancels the order" CARD_DECLINED "$(jq -r .failureReason <<<"$BODY")"
call GET "/api/inventory/$PRODUCT_ID" "" -H "Authorization: Bearer $ACCESS"
for _ in $(seq 1 20); do [[ "$(jq -r .quantity <<<"$BODY")" == 2 ]] && break; sleep 0.5; call GET "/api/inventory/$PRODUCT_ID" "" -H "Authorization: Bearer $ACCESS"; done
check "compensation returned the stock (5 - 2 - 1)" 2 "$(jq -r .quantity <<<"$BODY")"
call GET /api/coupons/me "" -H "Authorization: Bearer $ACCESS"
for _ in $(seq 1 20); do [[ "$(jq -r '.[0].status' <<<"$BODY")" == ISSUED ]] && break; sleep 0.5; call GET /api/coupons/me "" -H "Authorization: Bearer $ACCESS"; done
check "compensation returned the coupon" ISSUED "$(jq -r '.[0].status' <<<"$BODY")"

order "$PRODUCT_ID" 1 tok_approve "$COUPON_ID"; WITH_COUPON=$(jq -r .orderId <<<"$BODY")
wait_order "$WITH_COUPON" COMPLETED
check "order with the coupon completes" COMPLETED "$(jq -r .status <<<"$BODY")"
check "paid 12,000 - 3,000" 9000 "$(jq -r .paymentAmount <<<"$BODY")"
call GET /api/coupons/me "" -H "Authorization: Bearer $ACCESS"
for _ in $(seq 1 20); do [[ "$(jq -r '.[0].status' <<<"$BODY")" == USED ]] && break; sleep 0.5; call GET /api/coupons/me "" -H "Authorization: Bearer $ACCESS"; done
check "coupon is USED" USED "$(jq -r '.[0].status' <<<"$BODY")"

order "$PRODUCT_ID" 5 tok_approve; SOLD_OUT=$(jq -r .orderId <<<"$BODY")
wait_order "$SOLD_OUT" CANCELLED
check "more than the stock left is cancelled" OUT_OF_STOCK "$(jq -r .failureReason <<<"$BODY")"

call GET "/api/orders/$ORDER_ID" "" -H "Authorization: Bearer $ADMIN"
check "another user cannot see the order" 404 "$STATUS"
call GET "/internal/products?ids=$PRODUCT_ID" "" -H "Authorization: Bearer $ACCESS"
check "/internal/** is not reachable through the gateway" 404 "$STATUS"

echo "-- search (CQRS read model)"
UNIQUE="스모크$RANDOM$RANDOM"
call POST /api/products "{\"name\":\"$UNIQUE 블루투스 스피커\",\"brand\":\"스모크랩\",\"category\":\"DIGITAL\",\"price\":30000}" -H "Authorization: Bearer $ADMIN"
SEARCH_ID=$(jq -r .id <<<"$BODY")
found() { # query expected-count -> waits for the index to catch up (near real time + event lag).
  # KEYWORD: exact matching is the question here; the default HYBRID also returns similar products.
  for _ in $(seq 1 30); do
    call GET "/api/search/products?q=$(jq -rn --arg q "$1" '$q|@uri')&brand=%EC%8A%A4%EB%AA%A8%ED%81%AC%EB%9E%A9&mode=KEYWORD" ""
    [[ "$(jq -r .total <<<"$BODY")" == "$2" ]] && return 0
    sleep 0.5
  done
}
START=$(date +%s%N); found "$UNIQUE" 1
check "new product is searchable (no login needed)" 1 "$(jq -r .total <<<"$BODY")"
echo "       indexed after $(( ($(date +%s%N) - START) / 1000000 )) ms"
found "${UNIQUE}블루투스스피커" 1
check "Korean query without spaces matches" 1 "$(jq -r .total <<<"$BODY")"
call GET "/api/search/products?q=$(jq -rn --arg q "$UNIQUE 블루투스 스피커" '$q|@uri')" ""
check "hybrid (default) ranks the exact product first" "$SEARCH_ID" "$(jq -r '.items[0].productId' <<<"$BODY")"
call GET "/api/search/products?q=$(jq -rn '"방이 건조해서 목이 아파요"|@uri')" ""
check "hybrid finds a product by intent (keyword finds none)" true "$(jq '[.items[].name] | any(test("가습기"))' <<<"$BODY")"
call PATCH "/api/products/$SEARCH_ID" '{"price":25000}' -H "Authorization: Bearer $ADMIN"
for _ in $(seq 1 30); do found "$UNIQUE" 1; [[ "$(jq -r '.items[0].price' <<<"$BODY")" == 25000 ]] && break; sleep 0.5; done
check "price change reaches the index" 25000 "$(jq -r '.items[0].price' <<<"$BODY")"
for _ in $(seq 1 20); do
  call GET "/api/search/autocomplete?q=$(jq -rn --arg q "$UNIQUE" '$q|@uri')" ""
  [[ "$STATUS" == 200 && "$(jq -r '.[0]' <<<"$BODY")" != null ]] && break; sleep 0.5
done
check "autocomplete suggests it" "$UNIQUE 블루투스 스피커" "$(jq -r '.[0]' <<<"$BODY")"
call PATCH "/api/products/$SEARCH_ID" '{"status":"SUSPENDED"}' -H "Authorization: Bearer $ADMIN"
found "$UNIQUE" 0
check "suspended product disappears from search" 0 "$(jq -r .total <<<"$BODY")"

echo "all $PASS checks passed"
