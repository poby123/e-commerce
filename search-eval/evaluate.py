"""Compares KEYWORD / SEMANTIC / HYBRID on search-eval/queries.json through the gateway.

Recall@5: share of a query's relevant products in the top 5. MRR@10: 1 / rank of the first relevant product.
Zero results: queries that returned nothing at all. For 'absent' queries (nothing relevant exists) the
column that matters is noise: how many results came back anyway. Usage: python3 search-eval/evaluate.py [gateway-url] [label]
"""
import json
import statistics
import sys
import time
import urllib.parse
import urllib.request

base = sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:8000'
label = sys.argv[2] if len(sys.argv) > 2 else ''
queries = json.load(open(__file__.replace('evaluate.py', 'queries.json')))['queries']


def search(q, mode):
    url = f"{base}/api/search/products?{urllib.parse.urlencode({'q': q, 'mode': mode, 'size': 10})}"
    started = time.perf_counter()
    with urllib.request.urlopen(url) as r:
        body = json.load(r)
    return [i['name'] for i in body['items']], (time.perf_counter() - started) * 1000


rows = []
for mode in ['KEYWORD', 'SEMANTIC', 'HYBRID']:
    search('워밍업', mode)
    for kind in ['exact', 'intent', 'absent']:
        recall, mrr, zero, latency, noise = [], [], 0, [], []
        for item in [x for x in queries if x['type'] == kind]:
            names, ms = search(item['q'], mode)
            latency.append(ms)
            relevant = set(item['relevant'])
            noise.append(len(names))
            recall.append(len(relevant & set(names[:5])) / len(relevant) if relevant else 0)
            mrr.append(next((1 / (i + 1) for i, n in enumerate(names[:10]) if n in relevant), 0))
            zero += not names
        rows.append((mode, kind, statistics.mean(recall), statistics.mean(mrr), zero, len(recall), statistics.median(latency),
                     statistics.mean(noise)))

print(f"| mode | queries | Recall@5 | MRR@10 | zero results | avg results | p50 latency |{(' ' + label) if label else ''}")
print('|---|---|---:|---:|---:|---:|---:|')
for mode, kind, r, m, z, n, ms, avg in rows:
    if kind == 'absent':
        print(f"| {mode} | absent ({n}) | – | – | {z} | **{avg:.1f}** | {ms:.0f} ms |")
    else:
        print(f"| {mode} | {kind} ({n}) | {r:.2f} | {m:.2f} | {z} | {avg:.1f} | {ms:.0f} ms |")
