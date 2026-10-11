import { useEffect, useState } from 'react';
import Icon from './Icon.jsx';

function elapsed(at, start) { return `${Math.max(0, (Date.parse(at) - Date.parse(start)) / 1000).toFixed(1)}s`; }

function LeaseView({ run, now, expiry }) {
  const job = run.jobs[0];
  const heartbeats = run.events.filter((event) => event.kind === 'heartbeat');
  const abandoned = run.events.find((event) => event.kind === 'abandoned');
  const lease = expiry && !run.events.some((event) => event.kind === 'recovered') ? abandoned?.leaseExpiresAt : job?.leaseExpiresAt;
  const remaining = lease ? Math.max(0, (Date.parse(lease) - now) / 1000) : 0;
  const active = job?.status === 'claimed';
  const percent = active ? Math.min(100, remaining / run.options.leaseSeconds * 100) : 0;
  return <div className={`scenario-stage lease-stage ${expiry ? 'expiry-stage' : ''}`}>
    <div className={`lease-dial ${active && remaining > 0 ? 'lease-active' : ''}`} style={{ '--lease-fill': `${percent}%` }}><div><strong>{job?.status === 'completed' ? <Icon name="check" size={25} /> : active ? `${remaining.toFixed(1)}s` : '—'}</strong><span>{job?.status === 'completed' ? 'Completed' : 'Lease left'}</span></div></div>
    <div className="lease-explanation"><span className="section-label">{expiry ? 'ABANDON → EXPIRE → RECOVER' : 'RENEW → PROTECT → COMPLETE'}</span><h3>{expiry ? run.events.some((event) => event.kind === 'recovered') ? 'Recovery worker takes over' : abandoned ? 'Heartbeat stopped. Lease counting down.' : 'Waiting for the abandoned claim' : `${heartbeats.length} real heartbeat renewals`}</h3><p>{expiry ? 'The scheduled reaper must observe the expired lease before the recovery worker starts.' : `Upstream work takes ${run.options.latencyMs / 1000}s; the initial lease lasts ${run.options.leaseSeconds}s. Each renewal moves the deadline forward.`}</p>
      {lease && <p className="lease-deadline">{expiry ? 'Lease deadline' : 'Latest lease deadline'} <time>{new Date(lease).toLocaleTimeString()}</time></p>}
      <div className="heartbeat-beats" role="group" aria-label={expiry ? 'Recovery stages' : 'Successful heartbeat events'}>{expiry ? ['abandoned', 'recovered', 'completed'].map((kind) => <span className={`lease-step ${run.events.some((event) => event.kind === kind) ? 'observed' : ''}`} key={kind}>{kind === 'abandoned' ? 'Abandoned' : kind === 'recovered' ? 'Requeued' : 'Completed'}</span>) : heartbeats.slice(-12).map((event) => <span className="heartbeat-beat" key={event.sequence} title={`Renewed at ${elapsed(event.at, run.startedAt)}`}><Icon name="activity" size={14} /></span>)}</div>
    </div>
  </div>;
}

function ScenarioStage({ run, now }) {
  const scenario = run.options.scenario;
  if (scenario === 'heartbeat' || scenario === 'lease-expiry') return <LeaseView run={run} now={now} expiry={scenario === 'lease-expiry'} />;
  if (scenario === 'idempotency') return <div className="scenario-stage dedup-stage"><div className="submission-fan"><span className="section-label">CONCURRENT SUBMISSIONS</span><div>{run.returnedIds.map((id, index) => <span className="request-chip" key={index} style={{ '--stagger': `${Math.min(index, 8) * 55}ms` }}>#{id}<Icon name="arrow" size={12} /></span>)}</div></div><div className="merge-connector" aria-hidden="true"><Icon name="arrow" size={26} /></div><div className="dedup-target"><Icon name="database" size={24} /><strong>{run.jobs.length} persisted job{run.jobs.length === 1 ? '' : 's'}</strong><p>One shared key. One identity.</p>{run.jobs[0] && <code>#{run.jobs[0].id}</code>}</div></div>;
  if (scenario === 'locking') {
    const released = run.events.find((event) => event.kind === 'unlocked');
    const skipped = run.events.filter((event) => event.kind === 'claimed' && event.jobId !== run.lockedJobId && (!released || Date.parse(event.at) < Date.parse(released.at))).length;
    return <div className="scenario-stage lock-stage"><div className={`row-lock ${released ? 'lock-released' : ''}`}><span aria-hidden="true">{released ? '↗' : '⊠'}</span><strong>{run.lockedJobId ? `Job #${run.lockedJobId}` : 'First job'}</strong><small>{released ? 'Row lock released' : 'Held by a separate transaction'}</small></div><div className="lock-bypass"><span className="bypass-line" aria-hidden="true" /><span className="section-label">FOR UPDATE SKIP LOCKED</span><h3>{skipped} claims bypassed the locked row</h3><p>Concurrent workers take unlocked jobs. Once the lock opens, the first job joins the flow.</p></div></div>;
  }
  if (scenario === 'retry') {
    const job = run.jobs[0];
    const claims = run.events.filter((event) => event.kind === 'claimed').length;
    const waiting = job?.status === 'pending' && Date.parse(job.availableAt) > now;
    const remaining = waiting ? Math.max(0, (Date.parse(job.availableAt) - now) / 1000) : 0;
    return <div className="scenario-stage retry-stage"><span className="section-label">FAILURE BUDGET / RETRY-AFTER</span><div className="attempt-rail">{Array.from({ length: run.options.maxAttempts }, (_, index) => <div className={`attempt-node ${index < (job?.attempts || 0) ? 'attempt-failed' : index < claims ? 'attempt-processing' : ''}`} key={index}><span>{index + 1}</span><small>{index < (job?.attempts || 0) ? 'Failed' : index < claims ? 'Running' : 'Waiting'}</small></div>)}<Icon name="arrow" size={18} /><div className={`dead-letter-target ${job?.status === 'dead_letter' ? 'reached' : ''}`}><Icon name="alert" size={20} /><span>Dead letter</span></div></div><p>{waiting ? `Backoff: ${remaining.toFixed(1)}s until the next claim is eligible.` : job?.status === 'dead_letter' ? 'Failure budget exhausted. No further retry.' : 'Upstream returns HTTP 429. Each failure consumes one attempt.'}</p><div className="backoff-track"><span style={{ width: `${waiting ? Math.min(100, remaining / run.options.retryAfterSeconds * 100) : 0}%` }} /></div></div>;
  }
  if (scenario === 'rate-limit') {
    const deferrals = run.events.filter((event) => event.kind === 'deferred');
    return <div className="scenario-stage throttle-stage"><div className="token-gate"><Icon name="clock" size={27} /><strong>1 token / second</strong><span>Shared bucket · capacity 1</span></div><div><span className="section-label">DEFER WITHOUT SPENDING ATTEMPTS</span><h3>{deferrals.length} local throttle deferrals</h3><p>Workers without a token return jobs to pending. The retry timestamp determines when they can try again.</p><div className="deferred-chips">{deferrals.slice(-6).map((event) => <span key={event.sequence}>#{event.jobId} <Icon name="refresh" size={12} /> requeued</span>)}</div></div></div>;
  }
  return <div className="scenario-stage batch-stage"><div className="flow-symbol" aria-hidden="true"><span /><span /><span /><Icon name="arrow" size={21} /></div><div><span className="section-label">BATCH PROCESSING</span><h3>{run.options.jobCount} jobs. {run.options.workerCount} worker threads.</h3><p>Workers pause {run.options.claimDelayMs / 1000}s before each claim. Jobs stay in pending until a thread is ready.</p></div></div>;
}

export default function TestVisualization({ run, scenario, onOpenJob }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (run?.status !== 'running') return;
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, [run?.status]);
  if (!run) return <div className="lab-empty"><div className="empty-flow" aria-hidden="true"><span /><span /><span /><Icon name="arrow" size={26} /><Icon name={scenario.icon} size={34} /></div><h3>{scenario.short}</h3><p>Choose your settings and run the test.</p><span>Jobs, workers, and results will appear here.</span></div>;

  const lanes = [{ status: 'pending', label: 'Pending', subtitle: 'Waiting in the queue', icon: 'clock' }, { status: 'claimed', label: 'Claimed', subtitle: 'Owned by a worker', icon: 'activity' }, { status: run.options.scenario === 'retry' ? 'dead_letter' : 'completed', label: run.options.scenario === 'retry' ? 'Dead letter' : 'Completed', subtitle: run.options.scenario === 'retry' ? 'Failure budget exhausted' : 'Acknowledged successfully', icon: run.options.scenario === 'retry' ? 'alert' : 'check' }];
  return <>
    <ScenarioStage run={run} now={now} />
    <div className="queue-lanes" role="group" aria-label="Live status of this test's jobs">{lanes.map((lane) => {
      const jobs = run.jobs.filter((job) => job.status === lane.status);
      return <section className={`queue-lane lane-${lane.status}`} key={lane.status}><div className="lane-heading"><span><Icon name={lane.icon} size={15} />{lane.label}</span><strong>{jobs.length}</strong></div><div className="lane-jobs">{jobs.map((job) => {
        const lastEvent = run.events.filter((event) => event.jobId === job.id).at(-1);
        const deferred = job.status === 'pending' && lastEvent?.kind === 'deferred';
        const locked = job.id === run.lockedJobId && !run.events.some((event) => event.kind === 'unlocked');
        return <button type="button" className={`flow-job ${deferred ? 'job-deferred' : ''} ${locked ? 'job-locked' : ''}`} key={`${job.id}-${job.status}`} onClick={() => onOpenJob(job.id)} aria-label={`Open job ${job.id}, ${job.status}`}><span className="flow-job-id">#{job.id}<Icon name={locked ? 'database' : lane.icon} size={12} /></span><small>{locked ? 'Row locked' : job.status === 'claimed' ? job.claimedBy === 'abandoned-worker' ? 'Abandoned worker' : `Worker ${job.claimedBy?.split('-').at(-1)}` : deferred ? 'Rate limit wait' : job.attempts > 0 ? `${job.attempts} failed attempt${job.attempts === 1 ? '' : 's'}` : lane.status === 'pending' ? 'Ready to be claimed' : 'Done'}</small>{job.status === 'claimed' && job.claimedBy !== 'abandoned-worker' && <span className="processing-track" aria-hidden="true"><span /></span>}</button>;
      })}{jobs.length === 0 && <span className="lane-empty">No jobs here</span>}</div></section>;
    })}</div>
    <div className="worker-pool"><div className="worker-pool-heading"><span className="section-label">WORKERS</span><span>Peak claimed: <strong>{run.peakClaimed}</strong> / {run.options.workerCount}</span></div><div className="worker-slots">{Array.from({ length: run.options.workerCount }, (_, index) => {
      const job = run.jobs.find((item) => item.status === 'claimed' && item.claimedBy?.endsWith(`-worker-${index + 1}`));
      return <div className={`worker-slot ${job ? 'worker-busy' : ''}`} key={index}><span className="worker-light" /><div><strong>Thread {index + 1}</strong><span>{job ? `Processing #${job.id}` : run.status === 'running' ? 'Waiting to claim' : 'Finished'}</span></div>{job && <Icon name="activity" size={17} />}</div>;
    })}</div></div>
    <details className="lab-event-log"><summary>Event log <span>{run.events.length} events</span></summary><ol tabIndex={0} aria-label="Recent test events">{run.events.slice(-12).reverse().map((event) => <li className={`event-${event.kind}`} key={event.sequence}><time dateTime={event.at}>{elapsed(event.at, run.startedAt)}</time><span className="event-dot" /><div><strong>{event.kind.replaceAll('_', ' ')}{event.jobId ? ` · #${event.jobId}` : ''}</strong><p>{event.detail}</p>{event.workerId && <small>{event.workerId}</small>}</div></li>)}</ol><p className="event-log-note">Latest 12 events</p></details>
    {run.status !== 'running' && <div className={`test-report report-${run.status}`} role="status"><h3><Icon name={run.status === 'passed' ? 'check' : 'alert'} />Test {run.status}<span className="checks-count">{run.checks.filter((check) => check.passed).length} / {run.checks.length} checks passed</span></h3>{run.error && <p>{run.error}</p>}<details className="report-details" open={run.status === 'failed'}><summary>Verification details</summary><ul>{run.checks.map((check) => <li key={check.label}><Icon name={check.passed ? 'check' : 'alert'} size={16} /><div><strong>{check.label}</strong><p>{check.detail}</p></div></li>)}</ul></details></div>}
  </>;
}
