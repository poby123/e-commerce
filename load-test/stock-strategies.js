// A hot product: STOCK units, ITERATIONS requests for one unit each, VUS at the same time.
// Run through load-test/run.sh, which resets the stock and checks the database afterwards.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://inventory-service:8083';
const STRATEGY = __ENV.STRATEGY;
const PRODUCT = __ENV.PRODUCT;

const taken = new Counter('taken');
const soldOut = new Counter('sold_out');
const gaveUp = new Counter('gave_up');
const errors = new Counter('errors');

export const options = {
  scenarios: {
    flash_sale: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 200),
      iterations: Number(__ENV.ITERATIONS || 5000),
      maxDuration: '5m',
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.post(`${BASE}/internal/loadtest/stocks/${PRODUCT}/decrease?strategy=${STRATEGY}`, null, { timeout: '60s' });
  if (res.status === 200) taken.add(1);
  else if (res.status === 409) soldOut.add(1);
  else if (res.status === 503) gaveUp.add(1);
  else errors.add(1);
  check(res, { 'answered': (r) => [200, 409, 503].includes(r.status) });
}

export function handleSummary(data) {
  const m = data.metrics;
  const out = {
    strategy: STRATEGY,
    requests: m.http_reqs.values.count,
    durationSec: data.state.testRunDurationMs / 1000,
    rps: m.http_reqs.values.rate,
    p50: m.http_req_duration.values['p(50)'],
    p95: m.http_req_duration.values['p(95)'],
    p99: m.http_req_duration.values['p(99)'],
    taken: m.taken ? m.taken.values.count : 0,
    soldOut: m.sold_out ? m.sold_out.values.count : 0,
    gaveUp: m.gave_up ? m.gave_up.values.count : 0,
    errors: m.errors ? m.errors.values.count : 0,
  };
  return { [`/results/stock-${STRATEGY}.json`]: JSON.stringify(out, null, 2), stdout: JSON.stringify(out) + '\n' };
}
