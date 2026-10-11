import { formatDate, statuses } from '../lib/format.js';
import StatusBadge from './StatusBadge.jsx';
import Icon from './Icon.jsx';

export default function JobsTable({ jobs, loaded, error, status, limit, offset, onStatusChange, onLimitChange, onPageChange, onOpenJob, onRefresh, selectedId }) {
  const page = Math.floor(offset / limit) + 1;
  return <section className="card jobs-card" id="jobs" aria-labelledby="jobs-title">
    <div className="jobs-heading"><h2 id="jobs-title">Jobs</h2><div className="filter-field"><label className="sr-only" htmlFor="status-filter">Filter by status</label><select id="status-filter" value={status} onChange={(event) => onStatusChange(event.target.value)}><option value="">All statuses</option>{statuses.map((item) => <option value={item.value} key={item.value}>{item.label}</option>)}</select></div></div>
    {error && <div className="table-error" role="alert"><Icon name="alert" size={16} /><div><strong>{loaded ? 'Could not refresh this page.' : 'Could not load jobs.'}</strong><p>{error} {loaded ? 'The last loaded jobs are still shown.' : ''}</p></div><button type="button" className="text-button" onClick={onRefresh}>Retry</button></div>}
    <div className="table-scroll" tabIndex={0} role="region" aria-label="Jobs table, scroll horizontally on smaller screens" aria-busy={!loaded && !error}>
      <table>
        <caption className="sr-only">Recent jobs, ordered by descending ID. Select a job ID to view its details.</caption>
        <thead><tr><th scope="col">Job ID</th><th scope="col" className="type-column">Type</th><th scope="col">Status</th><th scope="col">Updated</th><th scope="col"><span className="sr-only">View details</span></th></tr></thead>
        <tbody>
          {!loaded && !error && Array.from({ length: 5 }, (_, index) => <tr className="skeleton-row" key={index} aria-hidden="true">{Array.from({ length: 5 }, (_, cell) => <td className={cell === 1 ? 'type-column' : undefined} key={cell}><span className="skeleton" /></td>)}</tr>)}
          {loaded && jobs.map((job) => <tr key={job.id} className={selectedId === job.id ? 'selected-row' : ''} onClick={(event) => { if (!event.target.closest('button')) onOpenJob(job.id); }}>
            <td><button type="button" className="job-id-button" onClick={() => onOpenJob(job.id)} aria-label={`View job ${job.id}`}><span className="id-hash">#</span>{job.id}</button></td>
            <td className="type-column"><span className="job-type" title={job.jobType}>{job.jobType.startsWith('sluice-lab:') ? 'Test job' : job.jobType}</span></td>
            <td><StatusBadge status={job.status} /></td>
            <td className="date-cell"><time dateTime={job.updatedAt || undefined} title={formatDate(job.updatedAt)}>{formatDate(job.updatedAt, { compact: true })}</time></td>
            <td className="row-chevron"><Icon name="chevron" size={14} /></td>
          </tr>)}
        </tbody>
      </table>
      {!loaded && !error && <p className="sr-only" role="status">Loading jobs…</p>}
    </div>
    {((loaded && jobs.length === 0) || (!loaded && error)) && <div className="table-empty"><span className="empty-icon"><Icon name={error && !loaded ? 'alert' : 'jobs'} size={24} /></span><h3>{error && !loaded ? 'Jobs are unavailable' : offset > 0 ? 'No more jobs on this page' : status ? `No ${statuses.find((item) => item.value === status)?.label.toLowerCase()} jobs yet` : 'Your queue starts here'}</h3><p>{error && !loaded ? 'We’ll retry automatically. You can also refresh manually.' : offset > 0 ? 'Go to the previous page to see earlier results.' : status ? 'Jobs in this state will appear as the queue processes work.' : 'Submit your first job and follow it from pending to completed.'}</p>{!error && offset === 0 && !status && <a className="text-button" href="#submit">Submit a job <Icon name="arrow" size={14} /></a>}</div>}
    <div className="table-footer"><div className="page-size"><label htmlFor="page-size">Rows per page</label><select id="page-size" value={limit} onChange={(event) => onLimitChange(Number(event.target.value))}>{[10, 25, 50, 100].map((size) => <option key={size} value={size}>{size}</option>)}</select></div><div className="pagination"><span>{loaded ? `${jobs.length} ${jobs.length === 1 ? 'job' : 'jobs'} · ` : ''}Page {page}</span><button type="button" className="icon-button previous" aria-label="Previous page" disabled={offset === 0} onClick={() => onPageChange(Math.max(0, offset - limit))}><Icon name="chevron" size={16} /></button><button type="button" className="icon-button" aria-label="Next page" disabled={!loaded || !!error || jobs.length < limit} onClick={() => onPageChange(offset + limit)}><Icon name="chevron" size={16} /></button></div></div>
  </section>;
}
