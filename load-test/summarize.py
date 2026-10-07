"""Turns load-test/results/*.json into summary.md (called by run.sh). Medians over rounds; any anomaly in
any round is counted (worst case), not averaged away."""
import json
import os
import statistics
import sys

stock, coupons, rounds, s_req, s_vus, c_req, c_users, c_vus = map(int, sys.argv[1:9])
d = os.path.join(os.path.dirname(__file__), 'results')
load = lambda name: json.load(open(os.path.join(d, name)))
med = lambda xs: statistics.median(xs)

print(f'## Stock: one hot product\n\n{stock} units, {s_req:,} requests for one unit each, {s_vus} concurrent. '
      f'Median of {rounds} rounds (worst round for sold/gave up/oversold).\n')
print('| strategy | rps | p50 ms | p95 ms | p99 ms | sold | gave up while stock left | errors | oversold |')
print('|---|---:|---:|---:|---:|---:|---:|---:|---|')
for s in ['CONDITIONAL', 'PESSIMISTIC', 'OPTIMISTIC', 'REDIS']:
    rs = [load(f'stock-{s}-r{i}.json') for i in range(1, rounds + 1)]
    dbs = [load(f'stock-{s}-r{i}.db.json') for i in range(1, rounds + 1)]
    oversold = any(r['taken'] + db['finalStock'] != stock or db['finalStock'] < 0 for r, db in zip(rs, dbs))
    print(f"| {s} | {med([r['rps'] for r in rs]):,.0f} | {med([r['p50'] for r in rs]):.0f} | {med([r['p95'] for r in rs]):.0f} | "
          f"{med([r['p99'] for r in rs]):.0f} | {min(r['taken'] for r in rs)}–{max(r['taken'] for r in rs)} | "
          f"{max(r['gaveUp'] for r in rs)} | {max(r['errors'] for r in rs)} | {'**YES**' if oversold else 'no'} |")

print(f'\n## First-come coupons\n\n{coupons} coupons, {c_req:,} requests from {c_users:,} users '
      f'({c_req - c_users:,} repeat clicks), {c_vus} concurrent. Median of {rounds} rounds.\n')
print('| mode | rps | p50 ms | p95 ms | p99 ms | won | rows in DB | distinct users | issued_quantity | DB caught up after |')
print('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|')
for m in ['db', 'redis']:
    rs = [load(f'coupon-{m}-r{i}.json') for i in range(1, rounds + 1)]
    dbs = [load(f'coupon-{m}-r{i}.db.json') for i in range(1, rounds + 1)]
    span = lambda xs: f'{min(xs)}' if min(xs) == max(xs) else f'{min(xs)}–{max(xs)}'
    print(f"| {m} | {med([r['rps'] for r in rs]):,.0f} | {med([r['p50'] for r in rs]):.0f} | {med([r['p95'] for r in rs]):.0f} | "
          f"{med([r['p99'] for r in rs]):.0f} | {span([r['won'] for r in rs])} | {span([x['rows'] for x in dbs])} | "
          f"{span([x['distinctUsers'] for x in dbs])} | {span([x['issuedQuantity'] for x in dbs])} | {span([x['drainSec'] for x in dbs])} s |")
