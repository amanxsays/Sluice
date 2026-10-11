import test from 'node:test';
import assert from 'node:assert/strict';
import { createConnection, isConnectionFailure } from '../src/lib/connection.js';

const tick = () => new Promise((resolve) => setImmediate(resolve));
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function clock() {
  let time = 0, id = 0;
  const timers = new Map();
  return {
    now: () => time,
    schedule(fn, delay) { const key = ++id; timers.set(key, { fn, at: time + delay }); return key; },
    cancel(key) { timers.delete(key); },
    advance(ms) {
      const end = time + ms;
      while (true) {
        const next = [...timers.entries()].filter(([, timer]) => timer.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
        if (!next) break;
        time = next[1].at;
        timers.delete(next[0]);
        next[1].fn();
      }
      time = end;
    },
  };
}
function setup(probe) {
  const timer = clock();
  const states = [];
  const connection = createConnection({ probe, onChange: (state) => states.push(state), ...timer });
  return { timer, states, connection, latest: () => states.at(-1) };
}

test('an already awake server becomes ready immediately without a fixed startup delay', async () => {
  let calls = 0;
  const app = setup(async () => { calls++; });
  try {
    await tick();
    assert.equal(app.latest().phase, 'ready');
    assert.equal(app.latest().hasConnected, true);
    app.timer.advance(180000);
    assert.equal(app.latest().phase, 'ready');
    assert.equal(calls, 1);
  } finally { app.connection.stop(); }
});

test('wake-up retries wait for each request and do not overlap or restart on repeated retry clicks', async () => {
  const batches = [deferred(), deferred()];
  let calls = 0;
  const app = setup(() => batches[calls++].promise);
  try {
    await tick();
    app.connection.retry();
    app.connection.retry();
    app.timer.advance(10000);
    assert.equal(calls, 1);
    batches[0].reject(new Error('still asleep'));
    await tick();
    app.timer.advance(4999);
    assert.equal(calls, 1);
    app.timer.advance(1);
    assert.equal(calls, 2);
    batches[1].resolve();
    await tick();
    assert.equal(app.latest().phase, 'ready');
  } finally { app.connection.stop(); }
});

test('three-minute deadline aborts a pending probe and stops automatic requests', async () => {
  const batch = deferred();
  let signal, calls = 0;
  const app = setup((current) => { signal = current; calls++; return batch.promise; });
  try {
    await tick();
    app.timer.advance(179999);
    assert.equal(app.latest().phase, 'connecting');
    app.timer.advance(1);
    assert.equal(app.latest().phase, 'unavailable');
    assert.equal(signal.aborted, true);
    batch.resolve();
    await tick();
    app.timer.advance(180000);
    assert.equal(app.latest().phase, 'unavailable');
    assert.equal(calls, 1);
  } finally { app.connection.stop(); }
});

test('terminal failure exposes the last error and Try again can recover', async () => {
  let awake = false, calls = 0;
  const app = setup(async () => { calls++; if (!awake) throw new Error('HTTP 503'); });
  try {
    await tick();
    app.timer.advance(180000);
    assert.equal(app.latest().phase, 'unavailable');
    assert.equal(app.latest().error, 'HTTP 503');
    awake = true;
    app.connection.retry();
    await tick();
    assert.equal(app.latest().phase, 'ready');
    assert.equal(app.latest().error, '');
    assert.equal(calls, 3);
  } finally { app.connection.stop(); }
});

test('an old aborted response cannot mark a new retry ready', async () => {
  const batches = [deferred(), deferred()];
  let calls = 0;
  const app = setup(() => batches[calls++].promise);
  try {
    await tick();
    app.timer.advance(180000);
    app.connection.retry();
    await tick();
    batches[0].resolve();
    await tick();
    assert.equal(app.latest().phase, 'connecting');
    batches[1].resolve();
    await tick();
    assert.equal(app.latest().phase, 'ready');
  } finally { app.connection.stop(); }
});

test('connection loss starts one new wake-up window and remembers previously loaded sessions', async () => {
  const reconnect = deferred();
  let calls = 0;
  const app = setup(() => ++calls === 1 ? Promise.resolve() : reconnect.promise);
  try {
    await tick();
    app.connection.disconnected(new Error('offline'));
    app.connection.disconnected(new Error('another simultaneous failure'));
    await tick();
    assert.equal(calls, 2);
    assert.equal(app.latest().phase, 'connecting');
    assert.equal(app.latest().hasConnected, true);
    reconnect.resolve();
    await tick();
    assert.equal(app.latest().phase, 'ready');
  } finally { app.connection.stop(); }
});

test('unmount cancels pending readiness work and ignores late responses', async () => {
  const batch = deferred();
  let signal;
  const app = setup((current) => { signal = current; return batch.promise; });
  await tick();
  app.connection.stop();
  assert.equal(signal.aborted, true);
  batch.resolve();
  await tick();
  app.timer.advance(180000);
  app.connection.retry();
  assert.equal(app.states.length, 1);
  const stopped = setup(async () => { throw new Error('must not run'); });
  stopped.connection.stop();
  await tick();
  assert.equal(stopped.states.length, 1);
});

test('transport failures and server errors trigger reconnection; validation, missing jobs, and cancellation do not', () => {
  assert.equal(isConnectionFailure(new Error('offline')), true);
  for (const status of [200, 408, 429, 500, 502, 503]) assert.equal(isConnectionFailure({ status }), true);
  for (const status of [400, 401, 403, 404]) assert.equal(isConnectionFailure({ status }), false);
  assert.equal(isConnectionFailure({ name: 'AbortError' }), false);
});
