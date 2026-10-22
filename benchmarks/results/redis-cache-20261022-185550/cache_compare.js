import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Rate, Counter } from 'k6/metrics';

const manifest = new SharedArray('fixture dates', () => JSON.parse(open(__ENV.MANIFEST)).dates);
const vus = Number(__ENV.VUS || 1);
const iterations = Number(__ENV.ITERATIONS || 30);
const resourceId = Number(__ENV.RESOURCE_ID);
const failBusiness = new Rate('business_failure_rate');
const success = new Counter('business_success');
export const options = {
  scenarios: { compare: { executor: 'per-vu-iterations', vus, iterations, maxDuration: '120s', gracefulStop: '5s' } },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: { http_req_failed: ['rate==0'], business_failure_rate: ['rate==0'], checks: ['rate==1'] },
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
};
export default function () {
  const index = (__ITER * vus) + (__VU - 1);
  if (index >= manifest.length) throw new Error('fixture pool exhausted');
  const fixture = manifest[index];
  const res = http.get(`http://127.0.0.1:8080/api/resources/${resourceId}/slots?date=${fixture.date}`, {
    tags: { name: 'GET experiment slots' }, timeout: '20s', redirects: 0,
  });
  let body;
  try { body = res.json(); } catch (_) { body = null; }
  const valid = Array.isArray(body) && body.length === 2 && body.every((s, i) =>
    s.resourceId === resourceId && s.id === fixture.slotIds[i] && s.status === 'AVAILABLE' &&
    s.slotStart === fixture.date + (i === 0 ? 'T09:00:00' : 'T10:00:00') &&
    s.slotEnd === fixture.date + (i === 0 ? 'T10:00:00' : 'T11:00:00'));
  check(res, { 'HTTP 200': r => r.status === 200, 'exact fixture slots': () => valid });
  failBusiness.add(!valid || res.status !== 200);
  if (valid && res.status === 200) success.add(1);
  // One compact per-request evidence record; identical output in both arms, no credentials.
  console.log(JSON.stringify({ kind: 'cache_request', index, vu: __VU, iteration: __ITER,
    date: fixture.date, status: res.status, valid, bytes: res.body ? res.body.length : 0,
    duration_ms: res.timings.duration, waiting_ms: res.timings.waiting,
    at_ms: Date.now(), arm: __ENV.ARM }));
  sleep(1);
}
