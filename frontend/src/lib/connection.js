// Readiness retries are independent of filters, views, and ordinary polling.
export function createConnection({ probe, onChange, retryMs = 5000, deadlineMs = 180000,
  now = Date.now, schedule = setTimeout, cancel = clearTimeout }) {
  let active = true;
  let generation = 0;
  let controller;
  let retryTimer;
  let deadlineTimer;
  let phase = 'connecting';
  let hasConnected = false;
  let lastError = '';
  let expiresAt;

  function publish() { onChange({ phase, hasConnected, error: lastError }); }
  function clear() {
    cancel(retryTimer);
    cancel(deadlineTimer);
    controller?.abort();
  }
  function unavailable(current) {
    if (!active || current !== generation || phase !== 'connecting') return;
    generation++;
    clear();
    phase = 'unavailable';
    publish();
  }
  async function attempt(current) {
    if (!active || current !== generation || phase !== 'connecting') return;
    if (now() >= expiresAt) { unavailable(current); return; }
    controller = new AbortController();
    try {
      await probe(controller.signal);
      if (!active || current !== generation) return;
      if (now() >= expiresAt) { unavailable(current); return; }
      cancel(deadlineTimer);
      phase = 'ready';
      hasConnected = true;
      lastError = '';
      publish();
    } catch (error) {
      if (!active || current !== generation) return;
      lastError = error.message || 'The server did not respond.';
      if (now() >= expiresAt) { unavailable(current); return; }
      // Schedule after settlement so even slow wake-up requests never overlap.
      retryTimer = schedule(() => { void attempt(current); }, retryMs);
    }
  }
  function begin(error = '') {
    if (!active) return;
    generation++;
    clear();
    phase = 'connecting';
    lastError = error;
    expiresAt = now() + deadlineMs;
    const current = generation;
    publish();
    deadlineTimer = schedule(() => unavailable(current), deadlineMs);
    queueMicrotask(() => { void attempt(current); });
  }

  begin();
  return {
    retry() { if (phase === 'unavailable') begin(); },
    disconnected(error) { if (phase === 'ready') begin(error.message); },
    stop() { active = false; generation++; clear(); },
  };
}

export function isConnectionFailure(error) {
  return error.name !== 'AbortError' && (error.status == null || error.status >= 500
    || error.status === 408 || error.status === 429 || error.status === 200);
}
