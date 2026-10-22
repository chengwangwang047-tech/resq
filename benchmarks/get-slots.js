
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 1,
    duration: '10s',
};

const baseUrl = __ENV.RESQ_BASE_URL || 'http://127.0.0.1:8080';
const resourceId = __ENV.RESQ_RESOURCE_ID || '1';
const date = __ENV.RESQ_SLOT_DATE;
const token = __ENV.RESQ_TOKEN;

if (!date) {
    throw new Error('请设置 RESQ_SLOT_DATE');
}

export default function () {

    const url = `${baseUrl}/api/resources/${resourceId}/slots?date=${date}`;

    const headers = token
        ? { Authorization: `Bearer ${token}` }
        : {};

    const res = http.get(url, {
        headers: headers,
    });
    const t = res.timings;

if (t.duration > 1000 || t.connecting > 1000 || t.blocked > 1000) {
    console.warn(
        `SLOW REQUEST VU=${__VU}, ` +
        `status=${res.status}, ` +
        `duration=${t.duration.toFixed(0)}ms, ` +
        `blocked=${t.blocked.toFixed(0)}ms, ` +
        `connecting=${t.connecting.toFixed(0)}ms, ` +
        `waiting=${t.waiting.toFixed(0)}ms, ` +
        `receiving=${t.receiving.toFixed(0)}ms`
    );
}

    check(res, {
    'HTTP status is 200': r => r.status === 200,

    'slots not empty': r =>
        r.status === 200 &&
        Array.isArray(r.json()) &&
        r.json().length > 0
});

    sleep(1);
}
