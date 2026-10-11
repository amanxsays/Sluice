import { useEffect, useRef, useState } from 'react';
import { api, serializeJob } from '../lib/api.js';
import Icon from './Icon.jsx';

const presets = [
  { key: 'success', label: 'Success', icon: 'check', payload: { latencyMs: 100, shouldFail: false, retryAfterSeconds: 1 }, description: 'Completes in about 100 ms.' },
  { key: 'slow', label: 'Slow job', icon: 'clock', payload: { latencyMs: 6000, shouldFail: false, retryAfterSeconds: 1 }, description: 'Runs for 6 seconds so you can follow its progress.' },
  { key: 'failure', label: 'Failure', icon: 'alert', payload: { latencyMs: 0, shouldFail: true, retryAfterSeconds: 3 }, description: 'Retries every 3 seconds, then moves to dead letter.' },
];

export default function JobForm({ onSubmitted, onOpenJob }) {
  const [jobType, setJobType] = useState('call-api');
  const [payload, setPayload] = useState(JSON.stringify(presets[0].payload, null, 2));
  const [idempotencyKey, setIdempotencyKey] = useState('');
  const [count, setCount] = useState(1);
  const [preset, setPreset] = useState('success');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [submitted, setSubmitted] = useState(null);
  const request = useRef(null);
  const errorRef = useRef(null);
  const advancedRef = useRef(null);

  useEffect(() => () => request.current?.abort(), []);

  function applyPreset(item) {
    setPreset(item.key);
    setJobType('call-api');
    setPayload(JSON.stringify(item.payload, null, 2));
    setError('');
  }

  async function submit(event) {
    event.preventDefault();
    if (request.current) return;
    const fields = { jobType, payload, idempotencyKey };
    try {
      serializeJob(fields);
    } catch (validationError) {
      setError(validationError.message);
      if (advancedRef.current) advancedRef.current.open = true;
      queueMicrotask(() => errorRef.current?.focus());
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    setPending(true);
    setError('');
    setSubmitted(null);
    try {
      const result = count === 1 ? await api.submitJob(fields, { signal: controller.signal })
        : await api.submitBatch(fields, count, { signal: controller.signal });
      if (controller.signal.aborted) return;
      setSubmitted(Array.isArray(result) ? result : [result]);
      onSubmitted();
    } catch (submissionError) {
      if (!controller.signal.aborted) {
        setError(`${submissionError.message} If the request reached Sluice, it may already be queued; check recent jobs before resubmitting, or reuse an idempotency key.`);
      }
    } finally {
      if (!controller.signal.aborted) setPending(false);
      request.current = null;
    }
  }

  let validJson = true;
  try { JSON.parse(payload); } catch { validJson = false; }
  const currentPreset = presets.find((item) => item.key === preset);

  return <section className="card submit-card" id="submit" aria-labelledby="submit-title">
    <div className="card-heading"><div className="section-icon"><Icon name="plus" /></div><h2 id="submit-title">New job</h2></div>
    <form onSubmit={submit}>
      <fieldset className="preset-fieldset" disabled={pending}>
        <legend>Job preset</legend>
        <div className="preset-buttons">{presets.map((item) => <button className={`preset-button ${preset === item.key ? 'selected' : ''}`} type="button" aria-pressed={preset === item.key} onClick={() => applyPreset(item)} key={item.key}><Icon name={item.icon} size={14} />{item.label}</button>)}</div>
        <p className="preset-description">{currentPreset?.description || 'Custom job configuration.'}</p>
      </fieldset>
      <div className="field"><label htmlFor="batch-size">Number of jobs</label><select id="batch-size" value={count} onChange={(event) => setCount(Number(event.target.value))} disabled={pending}>{[1, 5, 10, 20, 40].map((size) => <option value={size} key={size}>{size === 1 ? '1 job' : `${size} jobs`}</option>)}</select></div>
      <details ref={advancedRef} className="advanced-options"><summary>Advanced options<Icon name="chevron" size={15} /></summary><div className="advanced-content">
      <div className="field"><label htmlFor="job-type">Job type</label><input id="job-type" name="jobType" value={jobType} onChange={(event) => { setJobType(event.target.value); setPreset('custom'); }} disabled={pending} autoComplete="off" spellCheck={false} aria-describedby="job-type-help" /><p id="job-type-help" className="field-help">Demo handler: <code>call-api</code></p></div>
      <div className="field"><div className="field-label"><label htmlFor="payload">JSON payload</label><span className={`json-indicator ${validJson ? '' : 'invalid'}`}>{validJson ? <Icon name="check" size={12} /> : <Icon name="alert" size={12} />}{validJson ? 'Valid JSON' : 'Invalid JSON'}</span></div><textarea id="payload" name="payload" className="payload-editor" rows={7} value={payload} onChange={(event) => { setPayload(event.target.value); setPreset('custom'); }} disabled={pending} spellCheck={false} autoComplete="off" aria-describedby="payload-help" aria-invalid={!validJson} /><p id="payload-help" className="field-help">Use a preset or enter your own JSON.</p></div>
      <div className="field"><label htmlFor="idempotency-key">{count > 1 ? 'Idempotency key prefix' : 'Idempotency key'} <span className="optional">optional</span></label><input id="idempotency-key" name="idempotencyKey" value={idempotencyKey} onChange={(event) => setIdempotencyKey(event.target.value)} placeholder="e.g. demo-01" disabled={pending} autoComplete="off" aria-describedby="idempotency-help" /><p id="idempotency-help" className="field-help">{count > 1 ? 'Reuse this prefix and batch size to avoid duplicate jobs.' : 'Reuse this key to avoid duplicate jobs.'}</p></div>
      </div></details>
      {error && <p ref={errorRef} tabIndex={-1} className="form-error" role="alert"><Icon name="alert" size={16} /><span>{error}</span></p>}
      <button className="button button-primary submit-button" type="submit" disabled={pending}>{pending ? <><span className="spinner" />Submitting…</> : <>Enqueue {count === 1 ? 'job' : `${count} jobs`}<Icon name="arrow" size={17} /></>}</button>
      {submitted && <div className="submission-result" role="status"><span className="result-icon"><Icon name="check" size={16} /></span><div><strong>{submitted.length === 1 ? `Job #${submitted[0].id} received` : `${submitted.length} jobs received`}</strong><p>Follow {submitted.length === 1 ? 'its' : 'their'} progress in the queue.</p><button type="button" className="text-button" onClick={() => onOpenJob(submitted[0].id)}>Open {submitted.length > 1 ? 'first job' : 'job'} details <Icon name="arrow" size={13} /></button></div></div>}
    </form>
  </section>;
}
