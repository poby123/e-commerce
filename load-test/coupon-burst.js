// A coupon drop: ITERATIONS requests at once for a policy of limited quantity. USERS distinct users, so
// ITERATIONS - USERS requests are the same people clicking again.
//   MODE=db     POST /api/coupon-policies/{id}/issue   (conditional UPDATE + INSERT in MySQL)
//   MODE=redis  POST /api/coupon-policies/{id}/claim   (Redis Lua gate, Kafka, async insert)
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE || 'http://coupon-service:8085';
const MODE = __ENV.MODE;
const POLICY = __ENV.POLICY;
const USERS = Number(__ENV.USERS || 7000);

const won = new Counter('won');
const rejected = new Counter('rejected');
const errors = new Counter('errors');

export const options = {
  scenarios: {
    drop: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 500),
      iterations: Number(__ENV.ITERATIONS || 10000),
      maxDuration: '5m',
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const user = 1000000 + (exec.scenario.iterationInTest % USERS);
  const path = MODE === 'redis' ? 'claim' : 'issue';
  const res = http.post(`${BASE}/api/coupon-policies/${POLICY}/${path}`, null,
    { headers: { 'X-User-Id': String(user) }, timeout: '60s' });
  if (res.status === 201 || res.status === 202) won.add(1);
  else if (res.status === 409) rejected.add(1);
  else errors.add(1);
}

export function handleSummary(data) {
  const m = data.metrics;
  const out = {
    mode: MODE,
    requests: m.http_reqs.values.count,
    durationSec: data.state.testRunDurationMs / 1000,
    rps: m.http_reqs.values.rate,
    p50: m.http_req_duration.values['p(50)'],
    p95: m.http_req_duration.values['p(95)'],
    p99: m.http_req_duration.values['p(99)'],
    won: m.won ? m.won.values.count : 0,
    rejected: m.rejected ? m.rejected.values.count : 0,
    errors: m.errors ? m.errors.values.count : 0,
  };
  return { [`/results/coupon-${MODE}.json`]: JSON.stringify(out, null, 2), stdout: JSON.stringify(out) + '\n' };
}
