#!/usr/bin/env bash
# End-to-end check through the gateway.
#   auth:  signup -> login -> authenticated call -> refresh rotation -> logout
#   order: admin registers a product and stock -> user orders -> idempotent retry -> sold out
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

echo "-- order flow"
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
call PUT "/api/inventory/$PRODUCT_ID" '{"quantity":3}' -H "Authorization: Bearer $ADMIN"
check "admin sets stock to 3" 200 "$STATUS"

call GET "/api/products/$PRODUCT_ID" ""
check "product page is public" 200 "$STATUS"

ORDER_BODY="{\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":2}]}"
call POST /api/orders "$ORDER_BODY" -H "Authorization: Bearer $ACCESS"
check "order for 2 is confirmed" 201 "$STATUS"
check "status" CONFIRMED "$(jq -r .status <<<"$BODY")"
check "total uses the product price" 24000 "$(jq -r .totalAmount <<<"$BODY")"
ORDER_ID=$(jq -r .orderId <<<"$BODY")

IDEM="retry-$RANDOM-$RANDOM"
ONE="{\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":1}]}"
call POST /api/orders "$ONE" -H "Authorization: Bearer $ACCESS" -H "Idempotency-Key: $IDEM"
FIRST=$(jq -r .orderId <<<"$BODY")
call POST /api/orders "$ONE" -H "Authorization: Bearer $ACCESS" -H "Idempotency-Key: $IDEM"
check "same Idempotency-Key returns the same order" "$FIRST" "$(jq -r .orderId <<<"$BODY")"

call GET "/api/inventory/$PRODUCT_ID" "" -H "Authorization: Bearer $ACCESS"
check "stock is 3 - 2 - 1 (the retry took nothing)" 0 "$(jq -r .quantity <<<"$BODY")"

call POST /api/orders "$ORDER_BODY" -H "Authorization: Bearer $ACCESS"
check "sold out" 409 "$STATUS"
check "sold-out code" ORDER-003 "$(jq -r .code <<<"$BODY")"

call GET "/api/orders/$ORDER_ID" "" -H "Authorization: Bearer $ADMIN"
check "another user cannot see the order" 404 "$STATUS"

call GET "/internal/products?ids=$PRODUCT_ID" "" -H "Authorization: Bearer $ACCESS"
check "/internal/** is not reachable through the gateway" 404 "$STATUS"

echo "all $PASS checks passed"
