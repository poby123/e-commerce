## Stock: one hot product

3000 units, 20,000 requests for one unit each, 200 concurrent. Median of 3 rounds (worst round for sold/gave up/oversold).

| strategy | rps | p50 ms | p95 ms | p99 ms | sold | gave up while stock left | errors | oversold |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| CONDITIONAL | 3,489 | 36 | 165 | 315 | 3000–3000 | 0 | 0 | no |
| PESSIMISTIC | 3,838 | 33 | 153 | 195 | 3000–3000 | 0 | 0 | no |
| OPTIMISTIC | 3,633 | 12 | 294 | 864 | 3000–3000 | 116 | 0 | no |
| REDIS | 5,624 | 8 | 178 | 270 | 3000–3000 | 0 | 0 | no |

## First-come coupons

1000 coupons, 10,000 requests from 7,000 users (3,000 repeat clicks), 500 concurrent. Median of 3 rounds.

| mode | rps | p50 ms | p95 ms | p99 ms | won | rows in DB | distinct users | issued_quantity | DB caught up after |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| db | 2,457 | 136 | 617 | 1156 | 1000 | 1000 | 1000 | 1000 | 0–1 s |
| redis | 7,906 | 37 | 196 | 289 | 1000 | 1000 | 1000 | 1000 | 3 s |
