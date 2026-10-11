import { useEffect, useRef, useState } from 'react';
import { api } from '../lib/api.js';
import { createPoller } from '../lib/poller.js';
import { defaultsFor, scenarios, validateOptions } from '../lib/scenarios.js';
import Icon from './Icon.jsx';
import InfoTip from './InfoTip.jsx';
import TestVisualization from './TestVisualization.jsx';

function SelectField({ name, label, value, choices, onChange, help }) {
  return <div className="field"><label htmlFor={`test-${name}`}>{label}</label><select id={`test-${name}`} value={value} onChange={(event) => onChange(name, Number(event.target.value))}>{choices.map(([number, text]) => <option value={number} key={number}>{text}</option>)}</select>{help && <p className="field-help">{help}</p>}</div>;
}

export default function TestLab({ selection, onSelectionChange, onOpenJob, onChanged }) {
  const [optionsByTest, setOptionsByTest] = useState({});
  const [run, setRun] = useState(null);
  const [error, setError] = useState('');
  const [submissionError, setSubmissionError] = useState('');
  const [pending, setPending] = useState(false);
  const [connected, setConnected] = useState(false);
  const request = useRef(null);
  const poll = useRef(null);
  const lastResult = useRef(null);
  const changed = useRef(onChanged);
  useEffect(() => { changed.current = onChanged; }, [onChanged]);
  useEffect(() => {
    const poller = createPoller(async (signal) => {
      const current = await api.currentTest({ signal });
      if (signal.aborted) return;
      setRun(current);
      setConnected(true);
      setError('');
      if (current && current.status !== 'running' && lastResult.current !== `${current.id}:${current.status}`) {
        lastResult.current = `${current.id}:${current.status}`;
        changed.current();
      }
    }, { intervalMs: 600, onError: (failure) => {
      setConnected(false);
      setError(failure.status === 404 ? 'Test lab is unavailable. Rebuild the API and check sluice.lab.enabled. (HTTP 404)' : failure.message);
    } });
    poll.current = poller;
    return () => { poller.stop(); request.current?.abort(); };
  }, []);

  const scenario = scenarios.find((item) => item.id === selection);
  const options = optionsByTest[selection] || defaultsFor(selection);
  const single = ['heartbeat', 'lease-expiry', 'retry'].includes(selection);
  const active = run?.status === 'running';
  const validation = validateOptions(options);
  function change(name, value) { setSubmissionError(''); setOptionsByTest((previous) => ({ ...previous, [selection]: { ...options, [name]: value } })); }

  async function start(event) {
    event.preventDefault();
    if (request.current || active || validation) return;
    const controller = new AbortController();
    request.current = controller;
    setPending(true);
    setSubmissionError('');
    try {
      const report = await api.startTest(options, { signal: controller.signal });
      if (controller.signal.aborted) return;
      setRun(report);
      poll.current?.refresh();
      changed.current();
    } catch (failure) {
      if (!controller.signal.aborted) { setSubmissionError(`${failure.message} Check the current test before trying again.`); poll.current?.refresh(); }
    } finally {
      if (!controller.signal.aborted) setPending(false);
      request.current = null;
    }
  }

  const shownScenario = run ? scenarios.find((item) => item.id === run.options.scenario) : scenario;
  return <section className="test-lab" id="test-lab" aria-labelledby="test-lab-title">
    <h2 id="test-lab-title" className="sr-only">Test configuration and results</h2>
    <div className="lab-grid">
      <section className="card lab-controls" aria-label="Test configuration"><form onSubmit={start}>
        <div className="field"><div className="field-label"><label htmlFor="test-scenario">Test scenario</label><InfoTip label={scenario.name}>{scenario.description}</InfoTip></div><select id="test-scenario" value={selection} onChange={(event) => onSelectionChange(event.target.value)}>{scenarios.map((item) => <option key={item.id} value={item.id}>{item.short}</option>)}</select></div>
        <fieldset disabled={active || pending}><legend className="sr-only">Settings for {scenario.name}</legend>
          {single ? <div className="single-job-note"><Icon name="jobs" size={16} /><span>1 job · 1 {selection === 'lease-expiry' ? 'recovery' : 'processing'} worker</span></div> : <>
            <SelectField name="jobCount" label={selection === 'idempotency' ? 'Concurrent submissions' : 'Number of jobs'} value={options.jobCount} choices={[5, 10, 20, 40].map((value) => [value, `${value} ${selection === 'idempotency' ? 'submissions' : 'jobs'}`])} onChange={change} />
            <SelectField name="workerCount" label="Worker threads" value={options.workerCount} choices={Array.from({ length: 8 }, (_, index) => [index + 1, `${index + 1} thread${index === 0 ? '' : 's'}`])} onChange={change} />
          </>}
          <SelectField name="latencyMs" label={selection === 'lease-expiry' ? 'Recovery job latency' : 'Upstream latency'} value={options.latencyMs} choices={[1000, 3000, 6000, 10000].map((value) => [value, `${value / 1000} second${value === 1000 ? '' : 's'}`])} onChange={change} />
          <SelectField name="claimDelayMs" label="Pause before claiming" value={options.claimDelayMs} choices={[250, 500, 1000, 2000].map((value) => [value, `${value / 1000} seconds`])} onChange={change} />
          {['heartbeat', 'lease-expiry'].includes(selection) && <SelectField name="leaseSeconds" label="Initial lease duration" value={options.leaseSeconds} choices={[1, 2, 5].map((value) => [value, `${value} second${value === 1 ? '' : 's'}`])} onChange={change} help={selection === 'heartbeat' ? 'Choose latency longer than the lease to see renewals.' : 'No heartbeat is sent by the abandoned worker.'} />}
          {selection === 'retry' && <><SelectField name="maxAttempts" label="Failure budget" value={options.maxAttempts} choices={[2, 3, 5].map((value) => [value, `${value} attempts`])} onChange={change} /><SelectField name="retryAfterSeconds" label="Retry-After delay" value={options.retryAfterSeconds} choices={[1, 2, 5].map((value) => [value, `${value} seconds`])} onChange={change} /></>}
          {selection === 'rate-limit' && <p className="scenario-fixed-setting"><Icon name="clock" size={14} />Token bucket: capacity 1, refill 1 / second.</p>}
        </fieldset>
        {validation && <p className="form-error" role="alert">{validation}</p>}
        {error && <p className="form-error" role="alert">{error}</p>}
        {submissionError && <p className="form-error" role="alert">{submissionError}</p>}
        <button className="button button-primary submit-button" type="submit" disabled={pending || active || Boolean(validation) || !connected}>{pending ? <><span className="spinner" />Starting test…</> : active ? <><span className="spinner" />Test running</> : <>Run {scenario.short.toLowerCase()}<Icon name="arrow" size={17} /></>}</button>
        <div className="lab-scope-note"><span>One active test at a time.</span><InfoTip label="Test reports">Test jobs remain in the queue. Reports reset when the backend restarts.</InfoTip></div>
      </form></section>
      <section className="card lab-visuals" aria-label="Test visualization"><div className="card-heading"><h3>{run ? shownScenario.short : 'Live preview'}</h3><span className={`lab-run-badge run-${run?.status || 'idle'}`} aria-live="polite"><span className="status-dot" />{run?.status || 'Ready'}</span></div>
        {run && selection !== run.options.scenario && <p className="viewing-run-note">Showing the {shownScenario.short.toLowerCase()} {active ? 'run in progress' : 'last report'}. Run your selected test to switch the visualization.</p>}
        <TestVisualization run={run} scenario={scenario} onOpenJob={onOpenJob} />
      </section>
    </div>
  </section>;
}
