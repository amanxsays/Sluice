export const scenarios = [
  { id: 'batch', name: 'More jobs than workers', short: 'Batch & queue', icon: 'jobs',
    description: 'Launch a batch with fewer workers than jobs. Watch workers claim one job each while the rest stay pending. A worker takes another job only after finishing its current job.',
    proof: 'All jobs complete, claimed jobs stay within the thread limit, and pending and claimed jobs overlap.',
    defaults: { jobCount: 10, workerCount: 2, latencyMs: 3000 } },
  { id: 'idempotency', name: 'Idempotency', short: 'Idempotency', icon: 'database',
    description: 'Send several concurrent submissions with the same idempotency key. PostgreSQL should store one job and return that same job ID to every submission.',
    proof: 'Every submission returns the same ID and exactly one job is stored.',
    defaults: { jobCount: 10, workerCount: 2, latencyMs: 3000 } },
  { id: 'locking', name: 'Concurrency / SKIP LOCKED', short: 'Concurrency & locks', icon: 'activity',
    description: 'Hold a real database row lock on the first job. Concurrent worker threads must skip it, claim other jobs, and process the first job after the lock is released.',
    proof: 'Other jobs are claimed during the lock, each job is claimed exactly once, and all finish.',
    defaults: { jobCount: 10, workerCount: 4, latencyMs: 3000 } },
  { id: 'lease-expiry', name: 'Lease expiry & recovery', short: 'Lease expiry', icon: 'clock',
    description: 'Claim a job, then deliberately abandon it without a heartbeat. Wait for its lease to expire and the scheduled reaper to return it to the queue. A recovery worker finishes it.',
    proof: 'The scheduled reaper recovers the abandoned job, increments attempts once, and a second claim completes it.',
    defaults: { jobCount: 1, workerCount: 1, latencyMs: 3000 } },
  { id: 'heartbeat', name: 'Heartbeat / lease renewal', short: 'Heartbeat renewal', icon: 'activity',
    description: 'Run a job longer than its initial lease. The real worker renews the lease every third of its duration so healthy work stays claimed and is never recovered by the reaper.',
    proof: 'Multiple renewals are recorded and the job completes with one claim and zero failed attempts.',
    defaults: { jobCount: 1, workerCount: 1, latencyMs: 6000 } },
  { id: 'retry', name: 'Retries & dead letter', short: 'Retries & dead letter', icon: 'alert',
    description: 'Make the mock upstream return HTTP 429 every time. Watch each failure consume an attempt, wait for Retry-After, and eventually move the job to dead letter.',
    proof: 'The number of claims equals the failure budget and the job reaches dead_letter at that budget.',
    defaults: { jobCount: 1, workerCount: 1, latencyMs: 1000 } },
  { id: 'rate-limit', name: 'Local rate limiting', short: 'Rate limiting', icon: 'clock',
    description: 'Share a token bucket with capacity one and refill one token per second across workers. Jobs without a token return to pending without consuming a failure attempt.',
    proof: 'Local deferrals occur, all jobs complete, and their failed-attempt counts remain zero.',
    defaults: { jobCount: 10, workerCount: 4, latencyMs: 1000, claimDelayMs: 500 } },
];

export function defaultsFor(id) {
  return { scenario: id, jobCount: 10, workerCount: 2, latencyMs: 3000, claimDelayMs: 1000,
    leaseSeconds: 2, maxAttempts: 3, retryAfterSeconds: 2,
    ...scenarios.find((scenario) => scenario.id === id).defaults };
}

export function validateOptions(options) {
  if (options.scenario === 'batch' && options.jobCount <= options.workerCount) return 'Choose more jobs than worker threads.';
  if (options.scenario === 'locking' && (options.workerCount < 2 || options.jobCount <= options.workerCount)) return 'Choose at least two workers and more jobs than workers.';
  if (options.scenario === 'heartbeat' && options.latencyMs <= options.leaseSeconds * 1000 + 1000) return 'Choose latency more than one second longer than the initial lease.';
  if (options.jobCount * (options.latencyMs + options.claimDelayMs) / options.workerCount > 140000) return 'Choose fewer jobs, more workers, or lower latency to keep this test under three minutes.';
  return '';
}
