// One batch at a time. Manual refreshes during a batch coalesce into one follow-up.
export function createPoller(task, { intervalMs = 3000, onError = () => {} } = {}) {
  let active = true;
  let running = false;
  let queued = false;
  let timer;
  let controller;

  async function run() {
    if (!active || running) return;
    running = true;
    queued = false;
    controller = new AbortController();
    try {
      await task(controller.signal);
    } catch (error) {
      if (active && !controller.signal.aborted) onError(error);
    } finally {
      running = false;
      if (active) {
        if (queued) queueMicrotask(run);
        else timer = setTimeout(run, intervalMs);
      }
    }
  }

  queueMicrotask(run);

  return {
    refresh() {
      if (!active) return;
      clearTimeout(timer);
      if (running) queued = true;
      else void run();
    },
    stop() {
      active = false;
      clearTimeout(timer);
      controller?.abort();
    },
  };
}
