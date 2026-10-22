import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

export const options = {
  scenarios: {
    reservation_race: {
      executor: 'per-vu-iterations',
      vus: 5,
      iterations: 1,
      maxDuration: '30s',
    },
  },
};

const baseUrl =
  __ENV.RESQ_BASE_URL || 'http://127.0.0.1:8080';

const token = __ENV.RESQ_TOKEN;
const slotId = Number(__ENV.RESQ_POST_SLOT_ID);

if (!token) {
  throw new Error('请设置 RESQ_TOKEN');
}

if (!Number.isSafeInteger(slotId) || slotId <= 0) {
  throw new Error('请设置有效的 RESQ_POST_SLOT_ID');
}

const created = new Counter('reservation_created');
const conflicts = new Counter('reservation_conflict');
const limited = new Counter('reservation_rate_limited');
const unexpected = new Counter('reservation_unexpected');

// 201: 创建成功
// 409: 预期的业务冲突，不计为 HTTP 技术失败
const expectedStatuses = http.expectedStatuses(201, 409);

export default function () {
  // 每个 VU 使用不同的幂等请求 ID
  const requestId = `day24_race_${Date.now()}_${__VU}`;

  const res = http.post(
    `${baseUrl}/api/reservations`,
    JSON.stringify({ slotId }),
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        'X-Request-Id': requestId,
      },
      responseCallback: expectedStatuses,
      timeout: '5s',
    }
  );

  if (res.status === 201) {
    created.add(1);
  } else if (res.status === 409) {
    conflicts.add(1);
  } else if (res.status === 429) {
    limited.add(1);
  } else {
    unexpected.add(1);
  }

  check(res, {
    'created or business conflict': (r) =>
      r.status === 201 || r.status === 409,
  });

  console.log(
    `VU=${__VU} status=${res.status} duration=${res.timings.duration.toFixed(2)}ms`
  );
}
