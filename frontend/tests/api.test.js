import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, createApi, serializeJob } from '../src/lib/api.js';
import { formatPayload } from '../src/lib/format.js';

const fields = { jobType: 'call-api', payload: '{"latencyMs":100,"shouldFail":false,"retryAfterSeconds":1}', idempotencyKey: '' };
const response = (data, status = 200) => new Response(JSON.stringify(data), { status });

test('submission sends valid JSON as a string inside the outer JSON request', async () => {
  let sent;
  const client = createApi({ fetchImpl: async (url, options) => {
    sent = { url, ...options };
    return response({ id: 42, status: 'pending' });
  } });
  const result = await client.submitJob(fields);
  assert.equal(result.id, 42);
  assert.equal(sent.url, '/api/jobs');
  assert.equal(sent.method, 'POST');
  assert.equal(sent.headers['Content-Type'], 'application/json');
  const body = JSON.parse(sent.body);
  assert.equal(typeof body.payload, 'string');
  assert.deepEqual(JSON.parse(body.payload), { latencyMs: 100, shouldFail: false, retryAfterSeconds: 1 });
  assert.equal(body.idempotencyKey, null);
  assert.equal(body.jobType, 'call-api');
  assert.equal(body.priority, 0);
});

test('invalid payload and empty job type are rejected before contacting the backend', async () => {
  let calls = 0;
  const client = createApi({ fetchImpl: async () => { calls++; return response({}); } });
  assert.throws(() => client.submitJob({ ...fields, payload: '{bad}' }), /valid JSON/);
  assert.throws(() => serializeJob({ ...fields, jobType: '  ' }), /job type/);
  assert.equal(calls, 0);
});

test('idempotency keys are preserved and blank keys become null', () => {
  assert.equal(serializeJob({ ...fields, idempotencyKey: 'demo-key' }).idempotencyKey, 'demo-key');
  assert.equal(serializeJob({ ...fields, idempotencyKey: '   ' }).idempotencyKey, null);
});

test('listing consumes the API array directly and sends filtering and pagination', async () => {
  const jobs = [{ id: 3, status: 'pending' }];
  let url;
  const client = createApi({ baseUrl: '/custom/api/', fetchImpl: async (input) => { url = input; return response(jobs); } });
  assert.deepEqual(await client.listJobs({ status: 'pending', limit: 25, offset: 50 }), jobs);
  assert.equal(url, '/custom/api/jobs?limit=25&offset=50&status=pending');
});

test('PowerShell-style wrappers are rejected instead of mistaken for the array contract', async () => {
  const client = createApi({ fetchImpl: async () => response({ value: [], Count: 0 }) });
  await assert.rejects(client.listJobs(), /JSON array/);
});

test('statistics are real counts, including zero, and malformed counts are rejected', async () => {
  const stats = { total: 0, pending: 0, claimed: 0, completed: 0, dead_letter: 0 };
  const client = createApi({ fetchImpl: async () => response(stats) });
  assert.deepEqual(await client.getStats(), stats);
  const badClient = createApi({ fetchImpl: async () => response({ ...stats, total: '0' }) });
  await assert.rejects(badClient.getStats(), /unexpected response/);
});

test('HTTP 400 exposes the API message and status', async () => {
  const client = createApi({ fetchImpl: async () => response({ detail: 'limit must be between 1 and 100' }, 400) });
  await assert.rejects(client.listJobs(), (error) => error instanceof ApiError && error.status === 400 && /limit must/.test(error.message));
});

test('HTTP 404 produces an actionable details error', async () => {
  const client = createApi({ fetchImpl: async () => new Response('', { status: 404 }) });
  await assert.rejects(client.getJob(999), (error) => error.status === 404 && /could not be found/.test(error.message));
});

test('HTML server errors do not leak an HTML document into the UI', async () => {
  const client = createApi({ fetchImpl: async () => new Response('<html>proxy unavailable</html>', { status: 502 }) });
  await assert.rejects(client.listJobs(), (error) => error.status === 502 && /API is unavailable/.test(error.message) && !error.message.includes('<html>'));
});

test('network failures, invalid JSON, and malformed details are handled clearly', async () => {
  const offline = createApi({ fetchImpl: async () => { throw new TypeError('Failed to fetch'); } });
  await assert.rejects(offline.listJobs(), /Cannot reach Sluice/);
  const invalid = createApi({ fetchImpl: async () => new Response('not json') });
  await assert.rejects(invalid.listJobs(), /invalid JSON/);
  const invalidJob = createApi({ fetchImpl: async () => response(null) });
  await assert.rejects(invalidJob.getJob(1), /unexpected response/);
});

test('request timeouts stop a hanging fetch', async () => {
  let aborted = false;
  const client = createApi({ timeoutMs: 15, fetchImpl: (_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => { aborted = true; reject(new DOMException('Aborted', 'AbortError')); });
  }) });
  await assert.rejects(client.getStats(), /too long/);
  assert.equal(aborted, true);
});

test('caller cancellation remains an AbortError rather than a connection failure', async () => {
  const controller = new AbortController();
  const client = createApi({ fetchImpl: (_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
  }) });
  const pending = client.getStats({ signal: controller.signal });
  controller.abort();
  await assert.rejects(pending, (error) => error.name === 'AbortError');
});

test('payload formatting is safe for malformed content and JSON null', () => {
  assert.deepEqual(formatPayload('{bad}'), { text: '{bad}', valid: false });
  assert.deepEqual(formatPayload('null'), { text: 'null', valid: true });
  assert.equal(formatPayload('{"a":1}').text, '{\n  "a": 1\n}');
});
