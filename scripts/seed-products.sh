#!/usr/bin/env bash
# Korean demo catalog for search (step 6): names chosen to exercise Nori (compound words, user dictionary,
# synonyms) and facets (brand, category, price). Usage: scripts/seed-products.sh [gateway-url]
set -euo pipefail
B=${1:-http://localhost:8000}; J='Content-Type: application/json'
ADMIN=$(curl -s -X POST $B/api/auth/login -H "$J" -d '{"email":"admin@ecommerce.local","password":"admin1234!"}' | jq -r .accessToken)
n=0
while IFS='|' read -r name brand category price description; do
  [[ -z "$name" ]] && continue
  body=$(jq -nc --arg n "$name" --arg b "$brand" --arg c "$category" --argjson p "$price" --arg d "$description" \
    '{name:$n, brand:$b, category:$c, price:$p, description:$d}')
  id=$(curl -s -X POST $B/api/products -H "$J" -H "Authorization: Bearer $ADMIN" -d "$body" | jq -r .id)
  curl -s -o /dev/null -X PUT "$B/api/inventory/$id" -H "$J" -H "Authorization: Bearer $ADMIN" -d '{"quantity":100}'
  n=$((n + 1))
done <<'DATA'
무선 블루투스 이어폰 프로|사운드랩|DIGITAL|89000|노이즈캔슬링 블루투스 5.3 무선 이어폰
유선 이어폰 고음질|사운드랩|DIGITAL|19000|3.5mm 유선 이어셋
게이밍 무선 헤드셋|게임온|DIGITAL|129000|7.1 채널 무선 게이밍 헤드셋
무선충전기 고속 15W|차지업|DIGITAL|25000|스마트폰 고속 무선충전기
보조배터리 20000mAh|차지업|DIGITAL|39000|고속충전 대용량 보조배터리
기계식 키보드 갈축|키톡|DIGITAL|99000|텐키리스 기계식 키보드
무선 마우스 저소음|키톡|DIGITAL|29000|저소음 무선 마우스
14인치 노트북 경량|라이트북|DIGITAL|1290000|1kg 경량 노트북
노트북 거치대 알루미늄|데스크핏|DIGITAL|35000|각도 조절 노트북 거치대
스마트폰 케이스 투명|케이스원|DIGITAL|12000|휴대폰 투명 젤리 케이스
초음파 가습기 대용량|에어린|LIVING|45000|4L 대용량 초음파 가습기
가열식 가습기 미니|에어린|LIVING|59000|살균 가열식 가습기
제습기 10L|에어린|LIVING|189000|원룸용 저소음 제습기
공기청정기 소형|에어린|LIVING|149000|헤파필터 공기청정기
빨래 습기 제거제 6개입|하우스킵|LIVING|9000|옷장 습기 제거제
에어프라이어 5.5L|쿡앤|LIVING|79000|대용량 에어프라이어
전기포트 1.7L|쿡앤|LIVING|29000|스테인리스 전기포트
극세사 이불 퀸|슬립웰|LIVING|69000|겨울용 극세사 이불
메모리폼 베개|슬립웰|LIVING|39000|경추 메모리폼 베개
경량 러닝화|런데이|SPORTS|79000|쿠셔닝 좋은 경량 러닝화
남성 운동화 화이트|런데이|SPORTS|69000|데일리 운동화
여성 스니커즈 캔버스|워크온|FASHION|49000|캔버스 스니커즈
요가매트 8mm|핏존|SPORTS|25000|미끄럼 방지 요가매트
덤벨 세트 10kg|핏존|SPORTS|59000|가정용 덤벨 세트
남성 운동복 상하의 세트|핏존|SPORTS|55000|기능성 운동복
오버핏 후드티|데일리웨어|FASHION|39000|기모 오버핏 후드티
슬림핏 청바지|데일리웨어|FASHION|49000|스판 슬림핏 청바지
경량 패딩 조끼|데일리웨어|FASHION|59000|가벼운 경량 패딩
수분 크림 50ml|그린랩|BEAUTY|28000|민감성 수분 크림
선크림 SPF50|그린랩|BEAUTY|19000|무기자차 선크림
립밤 3개입|그린랩|BEAUTY|9000|보습 립밤
핸드크림 세트|퓨어핸즈|BEAUTY|15000|선물용 핸드크림 세트
제주 감귤 3kg|제주팜|FOOD|25000|산지직송 제주 감귤
유기농 현미 4kg|자연미|FOOD|32000|국내산 유기농 현미
드립백 커피 20개입|빈스토리|FOOD|18000|싱글오리진 드립백 커피
DATA
echo "seeded $n products"
