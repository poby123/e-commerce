#!/usr/bin/env bash
# End-to-end check through the gateway: signup -> login -> authenticated call -> refresh rotation -> logout.
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

echo "all $PASS checks passed"
