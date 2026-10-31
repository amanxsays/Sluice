const states = [
  { key: 'pending', label: 'Pending' },
  { key: 'claimed', label: 'Claimed' },
  { key: 'completed', label: 'Completed' },
  { key: 'dead_letter', label: 'Dead letter' },
];

export default function QueueOverview({ stats, ready, onSelectStatus }) {
  const maximum = stats ? Math.max(1, ...states.map(({ key }) => stats[key])) : 1;
  const percentage = stats?.total > 0 ? Math.round(stats.completed / stats.total * 100) : null;
  return <section className="card queue-overview" aria-labelledby="queue-overview-title">
    <div className="queue-distribution">
      <div className="snapshot-heading"><h2 id="queue-overview-title">Queue overview</h2><span className="snapshot-caption">{ready ? 'Current snapshot' : stats ? 'Last loaded snapshot' : 'Waiting for data'}</span></div>
      <div className="status-chart" aria-label="Job counts by status">
        {states.map(({ key, label }) => <button type="button" key={key} className={`chart-column chart-${key}`} onClick={() => onSelectStatus(key)} aria-label={`Show ${label.toLowerCase()} jobs${stats ? `: ${stats[key].toLocaleString()}` : ''}`}>
          <span className="chart-count" aria-hidden="true">{stats ? stats[key].toLocaleString() : '—'}</span><span className="chart-track" aria-hidden="true"><span className={`chart-fill ${stats ? '' : 'chart-placeholder'}`} style={stats ? { height: `${stats[key] / maximum * 100}%` } : undefined} /></span>
          <span className="chart-label">{label}</span>
        </button>)}
      </div>
    </div>
    <div className="completion-summary">
      <h3>Job completion</h3>
      <div className="completion-gauge" role="img" aria-label={stats ? `${stats.completed.toLocaleString()} of ${stats.total.toLocaleString()} jobs completed${percentage === null ? '' : ` (${percentage}%)`}` : 'Completion data not loaded'}>
        <svg viewBox="0 0 200 120" aria-hidden="true"><path className="gauge-track" d="M 20 100 A 80 80 0 0 1 180 100" /><path className="gauge-fill" d="M 20 100 A 80 80 0 0 1 180 100" pathLength="100" strokeDasharray={`${percentage ?? 0} 100`} /></svg>
        <div className="gauge-label" aria-hidden="true"><strong>{percentage === null ? '—' : `${percentage}%`}</strong><span>{stats?.total === 0 ? 'No jobs yet' : 'of all jobs'}</span></div>
      </div>
      <div className="completion-legend"><span className="status-dot" />Completed<span className="legend-unfinished" />Other states</div>
    </div>
  </section>;
}
