import test from 'node:test';
import assert from 'node:assert/strict';
import { createPoller } from '../src/lib/poller.js';

const tick = () => new Promise((resolve) => setImmediate(resolve));
function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

test('polling never overlaps and concurrent manual refreshes coalesce', async () => {
  let calls = 0;
  let active = 0;
  let maxActive = 0;
  const batches = [deferred(), deferred()];
  const poller = createPoller(async () => {
    const index = calls++;
    active++;
    maxActive = Math.max(maxActive, active);
    await batches[index].promise;
    active--;
  }, { intervalMs: 60000 });
  try {
    await tick();
    assert.equal(calls, 1);
    poller.refresh();
    poller.refresh();
    poller.refresh();
    await tick();
    assert.equal(calls, 1);
    batches[0].resolve();
    await tick();
    assert.equal(calls, 2);
    assert.equal(maxActive, 1);
    batches[1].resolve();
    await tick();
    assert.equal(calls, 2);
  } finally {
    poller.stop();
    batches.forEach((batch) => batch.resolve());
  }
});

test('stopping aborts in-flight requests and prevents queued refreshes', async () => {
  const batch = deferred();
  let signal;
  let calls = 0;
  const poller = createPoller(async (currentSignal) => {
    calls++;
    signal = currentSignal;
    await batch.promise;
  });
  await tick();
  poller.refresh();
  poller.stop();
  assert.equal(signal.aborted, true);
  batch.resolve();
  await tick();
  poller.refresh();
  await tick();
  assert.equal(calls, 1);
});

test('stopping before initial work prevents requests after unmount', async () => {
  let calls = 0;
  const poller = createPoller(async () => { calls++; });
  poller.stop();
  await tick();
  assert.equal(calls, 0);
});

test('a failed batch can be refreshed and errors do not stop the poller', async () => {
  let calls = 0;
  const errors = [];
  const poller = createPoller(async () => {
    if (++calls === 1) throw new Error('offline');
  }, { intervalMs: 60000, onError: (error) => errors.push(error.message) });
  try {
    await tick();
    assert.deepEqual(errors, ['offline']);
    poller.refresh();
    await tick();
    assert.equal(calls, 2);
  } finally {
    poller.stop();
  }
});
