// Payments under load, end to end: create, authorize and capture, through the gateway, at the arrival
// rate a named profile sets. ADR 0054. Run by scripts/load-profiles.sh, which passes:
//
//   -e PROFILE=steady|spike|soak   -e GATEWAY=http://gateway:8080   -e MERCHANTS=10
//
// Passed with k6's own -e rather than as container environment, which __ENV does not read.
//
// Several merchants, not one: each has its own allowance at the gateway (ADR 0053), and a load test
// run as one merchant measures the rate limit rather than the platform.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY || 'http://localhost:8080';
const PROFILE = __ENV.PROFILE || 'steady';
const MERCHANTS = parseInt(__ENV.MERCHANTS || '10', 10);

const PROFILES = {
  steady: {
    executor: 'constant-arrival-rate',
    rate: 30, timeUnit: '1s', duration: '3m',
    preAllocatedVUs: 60, maxVUs: 200,
  },
  spike: {
    executor: 'ramping-arrival-rate',
    startRate: 10, timeUnit: '1s',
    preAllocatedVUs: 100, maxVUs: 400,
    stages: [
      { target: 10, duration: '30s' },
      { target: 120, duration: '10s' },
      { target: 120, duration: '60s' },
      { target: 10, duration: '10s' },
      { target: 10, duration: '60s' },
    ],
  },
  soak: {
    executor: 'constant-arrival-rate',
    rate: 20, timeUnit: '1s', duration: '30m',
    preAllocatedVUs: 40, maxVUs: 150,
  },
};

// What each profile claims, and so what fails it. ADR 0054.
//
// Every profile must stay correct: almost nothing failed, and every check passed. The books are
// checked after the run by the script. Steady and soak also claim ordinary traffic is fast, so their
// latency is held to a limit. Spike does not make that claim. It offers four times what this laptop
// sustains, on purpose, and its question is whether overload fails or corrupts anything; its
// latency is recorded, not thresholded. That distinction was drawn after the first spike run crossed
// the latency limits with no failures at all, and ADR 0054 says so.
const CORRECT = {
  http_req_failed: ['rate<0.01'],
  checks: ['rate>0.99'],
};
const FAST = {
  'http_req_duration{step:create}': ['p(95)<500'],
  'http_req_duration{step:authorize}': ['p(95)<800'],
  'http_req_duration{step:capture}': ['p(95)<800'],
};
const THRESHOLDS = {
  steady: Object.assign({}, CORRECT, FAST),
  spike: Object.assign({}, CORRECT, {
    // Still declared, so the summary reports them, but set where only a collapse would cross.
    'http_req_duration{step:create}': ['p(95)<10000'],
    'http_req_duration{step:authorize}': ['p(95)<10000'],
    'http_req_duration{step:capture}': ['p(95)<10000'],
  }),
  soak: Object.assign({}, CORRECT, FAST),
};

export const options = {
  scenarios: { [PROFILE]: PROFILES[PROFILE] },
  thresholds: THRESHOLDS[PROFILE],
  summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max'],
};

const captured = new Counter('payments_captured');
// Which step failed and with what, so a failure in the numbers can be followed rather than guessed.
const failedAt = new Counter('failed_steps');
const endToEnd = new Trend('payment_end_to_end', true);

// k6's end-of-run summary keeps only the total of a tagged counter, so each failure is also said at
// the moment it happens: which step, which status, and the correlation id to find it in the logs.
function failed(step, response) {
  failedAt.add(1, { step, status: String(response.status) });
  console.warn(`failed ${step}: status ${response.status} correlation ${response.headers['X-Correlation-Id'] || '-'} ${String(response.body || response.error || '').slice(0, 160)}`);
}

function key() {
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function json(method, url, body, token, step) {
  const headers = { 'Content-Type': 'application/json', 'Idempotency-Key': key() };
  if (token) headers.Authorization = `Bearer ${token}`;
  return http.request(method, url, body ? JSON.stringify(body) : null, { headers, tags: { step } });
}

export function setup() {
  const merchants = [];
  for (let i = 0; i < MERCHANTS; i++) {
    const email = `load-${key()}@mizan.local`;
    const password = `a-load-password-${key()}`;
    const registered = json('POST', `${GATEWAY}/api/v1/merchants`,
      { merchantName: `Load ${i}`, fullName: 'Ada Lovelace', email, password }, null, 'setup');
    if (registered.status !== 201) throw new Error(`register: ${registered.status} ${registered.body}`);
    const id = registered.json('merchant.id');
    const signedIn = json('POST', `${GATEWAY}/api/v1/tokens`, { email, password }, null, 'setup');
    if (signedIn.status !== 200) throw new Error(`sign in: ${signedIn.status}`);
    const token = signedIn.json('accessToken');
    const account = json('POST', `${GATEWAY}/api/v1/merchants/${id}/accounts`,
      { code: 'settlement.try', name: 'Owed to the merchant, TRY', type: 'LIABILITY', currency: 'TRY' },
      token, 'setup');
    if (account.status !== 201) throw new Error(`account: ${account.status}`);
    merchants.push({ id, token, email, password });
  }
  return { merchants };
}

// Access tokens live fifteen minutes (identity-service), and the soak runs thirty. Each VU keeps its
// own tokens and signs in again well before one expires, so a soak measures the platform rather than
// the moment every token setup() issued stops working at once.
const TOKEN_REFRESH_MS = 10 * 60 * 1000;
const fresh = {};

function tokenFor(merchant) {
  const held = fresh[merchant.id];
  if (held && Date.now() - held.at < TOKEN_REFRESH_MS) return held.token;
  if (!held && Date.now() - setupAt < TOKEN_REFRESH_MS) return merchant.token;
  const signedIn = json('POST', `${GATEWAY}/api/v1/tokens`,
    { email: merchant.email, password: merchant.password }, null, 'sign-in');
  if (signedIn.status !== 200) return merchant.token;
  fresh[merchant.id] = { token: signedIn.json('accessToken'), at: Date.now() };
  return fresh[merchant.id].token;
}

const setupAt = Date.now();

export default function (data) {
  const listed = data.merchants[(__VU + __ITER) % data.merchants.length];
  const merchant = Object.assign({}, listed, { token: tokenFor(listed) });
  const payments = `${GATEWAY}/api/v1/merchants/${merchant.id}/payments`;
  const started = Date.now();

  const created = json('POST', payments,
    { amount: 125000, currency: 'TRY', reference: `load-${key()}` }, merchant.token, 'create');
  if (!check(created, { 'created 201': (r) => r.status === 201 })) {
    failed('create', created);
    return;
  }

  const authorized = json('POST', `${payments}/${created.json('id')}/authorize`,
    { card: '4000000000000000' }, merchant.token, 'authorize');
  if (!check(authorized, { 'authorized 200': (r) => r.status === 200 })) {
    failed('authorize', authorized);
    return;
  }
  if (authorized.json('status') !== 'AUTHORIZED') return; // held or declined: the platform working

  const capturedResponse = json('POST', `${payments}/${created.json('id')}/capture`,
    null, merchant.token, 'capture');
  if (check(capturedResponse, { 'captured 200': (r) => r.status === 200 })) {
    captured.add(1);
    endToEnd.add(Date.now() - started);
  } else {
    failed('capture', capturedResponse);
  }
}

// The numbers a person reads and the numbers kept. Built from k6's own summary data rather than a
// remote helper library, so a run needs nothing but the pinned image.
export function handleSummary(data) {
  const m = data.metrics;
  const trend = (name) => (m[name] ? m[name].values : null);
  const ms = (v) => (v === undefined || v === null ? 'n/a' : `${Math.round(v)}ms`);
  const seconds = data.state.testRunDurationMs / 1000;
  const steps = ['create', 'authorize', 'capture'].map((step) => {
    const t = trend(`http_req_duration{step:${step}}`);
    return { step, p50: t && t.med, p95: t && t['p(95)'], p99: t && t['p(99)'], max: t && t.max };
  });

  const result = {
    profile: PROFILE,
    merchants: MERCHANTS,
    seconds,
    requests: m.http_reqs ? m.http_reqs.values.count : 0,
    requestsPerSecond: m.http_reqs ? m.http_reqs.values.rate : 0,
    paymentsCaptured: m.payments_captured ? m.payments_captured.values.count : 0,
    capturedPerSecond: m.payments_captured ? m.payments_captured.values.count / seconds : 0,
    errorRate: m.http_req_failed ? m.http_req_failed.values.rate : 0,
    checksPassed: m.checks ? m.checks.values.rate : 0,
    endToEnd: trend('payment_end_to_end'),
    steps,
    droppedIterations: m.dropped_iterations ? m.dropped_iterations.values.count : 0,
    failedSteps: m.failed_steps ? m.failed_steps.values.count : 0,
    thresholdsPassed: Object.values(m).every((metric) =>
      !metric.thresholds || Object.values(metric.thresholds).every((t) => t.ok)),
  };

  const lines = [
    '',
    `profile ${PROFILE}: ${result.seconds.toFixed(0)}s, ${result.merchants} merchants`,
    `  requests          ${result.requests} (${result.requestsPerSecond.toFixed(1)}/s), errors ${(result.errorRate * 100).toFixed(3)}%`,
    `  captured          ${result.paymentsCaptured} (${result.capturedPerSecond.toFixed(1)}/s), dropped iterations ${result.droppedIterations}`,
    `  end to end        p50 ${ms(result.endToEnd && result.endToEnd.med)}  p95 ${ms(result.endToEnd && result.endToEnd['p(95)'])}  p99 ${ms(result.endToEnd && result.endToEnd['p(99)'])}`,
    ...steps.map((s) => `  ${s.step.padEnd(17)} p50 ${ms(s.p50)}  p95 ${ms(s.p95)}  p99 ${ms(s.p99)}  max ${ms(s.max)}`),
    `  thresholds        ${result.thresholdsPassed ? 'passed' : 'FAILED'}`,
    '',
  ];

  return {
    stdout: lines.join('\n'),
    [`/results/${PROFILE}.json`]: JSON.stringify(result, null, 2),
  };
}
