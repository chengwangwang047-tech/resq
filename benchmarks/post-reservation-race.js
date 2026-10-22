import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import crypto from 'k6/crypto';

// One independent new reservation attempt per VU. No retries or warm-up calls.
const vus = Number(__ENV.RESQ_VUS || '5');
const slotId = Number(__ENV.RESQ_POST_SLOT_ID);
const runId = __ENV.RESQ_RUN_ID;
const baseUrl = (__ENV.RESQ_BASE_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '');

if (!Number.isSafeInteger(vus) || vus < 1 || vus > 200) {
  throw new Error('RESQ_VUS must be an integer from 1 to 200. Day24 uses 50, 100, 200.');
}
if (!Number.isSafeInteger(slotId) || slotId <= 0) {
  throw new Error('Set a valid RESQ_POST_SLOT_ID for a verified AVAILABLE experiment slot.');
}
if (!runId || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$/.test(runId)) {
  throw new Error('Set RESQ_RUN_ID to a unique 1-80 character run identifier (letters, digits, _ or -).');
}
if (!/^https?:\/\/[^\s@?#]+$/.test(baseUrl)) {
  throw new Error('RESQ_BASE_URL must be an HTTP(S) base URL without credentials, query or fragment.');
}

// Private JSON format: [{ "userId": 123, "token": "..." }, ...].
// This file is read only; never archive it with benchmark results.
let identities;
const usingTokensFile = Boolean(__ENV.RESQ_TOKENS_FILE);
if (usingTokensFile) {
  try {
    identities = JSON.parse(open(__ENV.RESQ_TOKENS_FILE));
  } catch (_) {
    throw new Error('Unable to read/parse the private RESQ_TOKENS_FILE JSON.');
  }
  if (!Array.isArray(identities) || identities.length < vus) {
    throw new Error('RESQ_TOKENS_FILE must contain at least RESQ_VUS independent test users.');
  }
  const userIds = new Set();
  const tokens = new Set();
  for (const identity of identities) {
    if (!identity || !['string', 'number'].includes(typeof identity.userId)
      || !/^[A-Za-z0-9_.:@-]{1,128}$/.test(String(identity.userId))
      || typeof identity.token !== 'string' || !identity.token.trim()
      || /\s/.test(identity.token)) {
      throw new Error('Invalid private credential entry; each needs a nonempty userId and token.');
    }
    const normalizedUserId = String(identity.userId);
    if (userIds.has(normalizedUserId) || tokens.has(identity.token)) {
      throw new Error('Private credential entries must have unique userIds and distinct tokens.');
    }
    userIds.add(normalizedUserId);
    tokens.add(identity.token);
  }
} else {
  if (!__ENV.RESQ_TOKEN || /\s/.test(__ENV.RESQ_TOKEN)) {
    throw new Error('Set RESQ_TOKENS_FILE, or RESQ_TOKEN only for the <=10 VU compatibility mode.');
  }
  if (vus > 10) {
    throw new Error('A single JWT is restricted to <=10 VUs. Use independent test users for 50/100/200.');
  }
  identities = [{ userId: null, token: __ENV.RESQ_TOKEN }];
}

const created = new Counter('reservation_created');
const conflicts = new Counter('reservation_conflict');
const limited = new Counter('reservation_rate_limited');
const unexpected = new Counter('reservation_unexpected');
const technical = new Counter('reservation_technical_failure');
const all409 = new Counter('reservation_http_409');
const otherStatuses = new Counter('reservation_http_other');
const technicalRate = new Rate('reservation_technical_failure_rate');
const unexpectedRate = new Rate('reservation_unexpected_rate');
const startOffset = new Trend('reservation_request_start_offset_ms', true);
const callDuration = new Trend('reservation_call_duration_ms', true);

export const options = {
  scenarios: {
    reservation_race: {
      executor: 'per-vu-iterations',
      vus,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  setupTimeout: '30s',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    reservation_created: ['count==1'],
    reservation_conflict: [`count==${vus - 1}`],
    reservation_rate_limited: ['count==0'],
    reservation_unexpected: ['count==0'],
    reservation_technical_failure: ['count==0'],
    reservation_technical_failure_rate: ['rate==0'],
    reservation_unexpected_rate: ['rate==0'],
    http_reqs: [`count==${vus}`],
    iterations: [`count==${vus}`],
    checks: ['rate==1'],
  },
};

// Compatibility with the original script: HTTP 201 and ALL HTTP 409 responses
// are expected for built-in http_req_failed. That built-in metric is therefore
// NOT the technical failure rate. Only the exact message below is an inventory
// conflict; other 409 responses are unexpected and fail the strict thresholds.
const expectedStatuses = http.expectedStatuses(201, 409);
const inventoryConflictMessage = '时隙不可用，预约失败';

export function setup() {
  const setupAt = Date.now();
  const startAt = __ENV.RESQ_START_AT_MS ? Number(__ENV.RESQ_START_AT_MS) : setupAt + 5000;
  if (!Number.isSafeInteger(startAt) || startAt < setupAt + 1000 || startAt > setupAt + 20000) {
    throw new Error('RESQ_START_AT_MS must be 1-20 seconds in the future at setup; default is setup +5 seconds.');
  }
  console.log(JSON.stringify({
    event: 'reservation_race_plan',
    run_id: runId,
    slot_id: slotId,
    vus,
    attempts_per_vu: 1,
    executor: 'per-vu-iterations',
    credential_mode: usingTokensFile ? 'independent_users' : 'single_user_compatibility',
    selected_users: usingTokensFile ? vus : 1,
    setup_at_ms: setupAt,
    target_start_at_ms: startAt,
    target_start_at_utc: new Date(startAt).toISOString(),
    request_timeout: '5s',
    http_req_failed_expected_statuses: [201, 409],
    technical_failure_definition: 'status=0 or status>=500',
    unexpected_definition: 'neither 201 nor exact inventory 409 nor 429; includes technical failures',
  }));
  return { startAt };
}

export default function (data) {
  const identity = identities[usingTokensFile ? __VU - 1 : 0];
  const requestId = `${runId}_${vus}_${__VU}_${__ITER}`;
  const payload = JSON.stringify({ slotId });
  const params = {
    headers: {
      Authorization: `Bearer ${identity.token}`,
      'Content-Type': 'application/json',
      'X-Request-Id': requestId,
    },
    responseCallback: expectedStatuses,
    redirects: 0,
    timeout: '5s',
    tags: { name: 'POST /api/reservations' },
  };

  // All VUs sleep until one wall-clock target; logging happens after responses
  // so console output does not serialize the initial dispatch. Actual dispatch
  // skew remains measurable from request_start_at_ms and startOffset min/max.
  while (Date.now() < data.startAt) {
    sleep(Math.max(0, data.startAt - Date.now()) / 1000);
  }
  const requestStartAt = Date.now();
  let res;
  let requestThrew = false;
  try {
    res = http.post(`${baseUrl}/api/reservations`, payload, params);
  } catch (_) {
    // Do not log arbitrary exception text, request headers or response bodies.
    requestThrew = true;
    res = { status: 0, timings: { duration: null }, error_code: null };
  }
  const requestEndAt = Date.now();

  let message = null;
  if (res.status === 409) {
    try {
      const body = res.json();
      if (body && typeof body.message === 'string') message = body.message;
    } catch (_) {
      // Missing/non-JSON message is an unexpected 409, never inventory proof.
    }
  }
  const isCreated = res.status === 201;
  const isConflict = res.status === 409 && message === inventoryConflictMessage;
  const isLimited = res.status === 429;
  const isTechnical = res.status === 0 || res.status >= 500;
  // Technical failures are a separately reported subset of unexpected results.
  const isUnexpected = !(isCreated || isConflict || isLimited);
  const isOtherStatus = ![201, 409, 429].includes(res.status);

  // Add 0 as well as 1 so every metric exists in successful result files.
  created.add(Number(isCreated));
  conflicts.add(Number(isConflict));
  limited.add(Number(isLimited));
  unexpected.add(Number(isUnexpected));
  technical.add(Number(isTechnical));
  all409.add(Number(res.status === 409));
  otherStatuses.add(Number(isOtherStatus));
  technicalRate.add(isTechnical);
  unexpectedRate.add(isUnexpected);
  startOffset.add(requestStartAt - data.startAt);
  callDuration.add(requestEndAt - requestStartAt);

  check(res, {
    'created or inventory business conflict': () => isCreated || isConflict,
  });

  console.log(JSON.stringify({
    event: 'reservation_race_request',
    run_id: runId,
    slot_id: slotId,
    vu: __VU,
    iteration: __ITER,
    user_id: identity.userId,
    request_id: requestId,
    target_start_at_ms: data.startAt,
    request_start_at_ms: requestStartAt,
    request_end_at_ms: requestEndAt,
    request_start_offset_ms: requestStartAt - data.startAt,
    call_duration_ms: requestEndAt - requestStartAt,
    http_duration_ms: res.timings.duration,
    status: res.status,
    classification: isCreated ? 'created' : isConflict ? 'inventory_conflict'
      : isLimited ? 'rate_limited' : isTechnical ? 'technical_failure' : 'unexpected',
    conflict_message_class: res.status !== 409 ? null
      : isConflict ? 'inventory_unavailable' : message === null ? 'missing_or_non_json_message' : 'other_409',
    // Only the known, nonsecret inventory message is emitted as plaintext.
    conflict_message: isConflict ? inventoryConflictMessage : null,
    conflict_message_sha256: message === null ? null : crypto.sha256(message, 'hex'),
    error_code: res.error_code || null,
    request_threw: requestThrew,
  }));
}
