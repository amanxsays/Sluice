import { useEffect, useRef, useState } from 'react';
import { formatDate, formatPayload } from '../lib/format.js';
import StatusBadge from './StatusBadge.jsx';
import Icon from './Icon.jsx';

export default function JobDetails({ id, job, error, refreshing, connection, onClose, onRefresh }) {
  const dialogRef = useRef(null);
  const closeRef = useRef(null);
  const [copyMessage, setCopyMessage] = useState('');
  const mounted = useRef(true);

  useEffect(() => {
    const dialog = dialogRef.current;
    const trigger = document.activeElement;
    const overflow = document.body.style.overflow;
    mounted.current = true;
    document.body.style.overflow = 'hidden';
    dialog.showModal();
    closeRef.current?.focus();
    return () => {
      mounted.current = false;
      dialog.close();
      document.body.style.overflow = overflow;
      if (trigger?.isConnected) trigger.focus();
      else document.getElementById('refresh-button')?.focus();
    };
  }, []);

  async function copy(value, label) {
    try {
      await navigator.clipboard.writeText(String(value));
      if (mounted.current) setCopyMessage(`${label} copied.`);
    } catch {
      if (mounted.current) setCopyMessage(`Could not copy ${label.toLowerCase()}. Select the text to copy it manually.`);
    }
  }

  const payload = job ? formatPayload(job.payload) : null;
  const fields = job ? [
    ['Job type', job.jobType],
    ['Attempts', job.attempts],
    ['Created at', formatDate(job.createdAt)],
    ['Updated at', formatDate(job.updatedAt)],
  ] : [];
  const workerFields = job ? [
    ['Priority', job.priority],
    ['Idempotency key', job.idempotencyKey ?? 'Not provided'],
    ['Claimed by', job.claimedBy ?? 'No claim recorded'],
    ['Claimed at', formatDate(job.claimedAt)],
    ['Lease expires at', formatDate(job.leaseExpiresAt)],
    ['Available at', formatDate(job.availableAt)],
  ] : [];

  return <dialog ref={dialogRef} className="details-dialog" aria-labelledby="details-title" aria-describedby="details-description" onCancel={(event) => { event.preventDefault(); onClose(); }} onClick={(event) => {
    if (event.target !== event.currentTarget) return;
    const rect = event.currentTarget.getBoundingClientRect();
    if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) onClose();
  }}>
    <div className="details-header"><div><h2 id="details-title">Job <span className="mono">#{id}</span></h2><p id="details-description">Job details</p></div><button type="button" ref={closeRef} className="icon-button" aria-label="Close job details" onClick={onClose}><Icon name="close" size={20} /></button></div>
    <div className="details-content">
      {error && connection.ready && <div className="detail-error" role="alert"><Icon name="alert" size={17} /><div><strong>{job ? 'Details could not refresh' : 'Details unavailable'}</strong><p>{error}</p>{job && <p>The last loaded details remain below.</p>}<button type="button" className="text-button" onClick={onRefresh}>Try again</button></div></div>}
      {!job && (!error || !connection.ready) && <div className="details-loading" role="status">{connection.phase === 'unavailable' ? <><span>Connection unavailable.</span><button type="button" className="text-button" onClick={connection.retry}>Try again</button></> : <><span className="spinner" />{connection.ready ? 'Loading job details…' : 'Waiting for connection…'}</>}</div>}
      {job && <>
        <div className="detail-summary"><StatusBadge status={job.status} /><span className="detail-live"><span className={`status-dot ${refreshing ? 'pulsing' : ''}`} />{error || !connection.ready ? 'Last loaded state' : 'Updates every 3s'}</span></div>
        <div className="detail-id"><span>Job ID</span><div><code>{job.id}</code><button type="button" className="icon-button" aria-label="Copy job ID" onClick={() => copy(job.id, 'Job ID')}><Icon name="copy" size={15} /></button></div></div>
        <dl className="detail-fields">{fields.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
        <details className="advanced-options worker-details"><summary>Worker & lease details<Icon name="chevron" size={15} /></summary><dl className="detail-fields">{workerFields.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl></details>
        <div className="detail-payload"><div className="payload-heading"><h3>Payload</h3><button type="button" className="button button-small" onClick={() => copy(job.payload, 'Payload')}><Icon name="copy" size={14} />Copy JSON</button></div><pre>{payload.text}</pre>{!payload.valid && <p className="field-help">This payload could not be parsed as JSON. Its original text is shown.</p>}</div>
      </>}
      <p className="copy-feedback" role="status">{copyMessage}</p>
    </div>
    <div className="details-footer"><span>Local timezone</span><button type="button" className="button" onClick={onClose}>Close</button></div>
  </dialog>;
}
