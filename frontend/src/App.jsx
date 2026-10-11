import { useCallback, useEffect, useState } from 'react';
import useDashboard from './hooks/useDashboard.js';
import { formatTime } from './lib/format.js';
import Icon from './components/Icon.jsx';
import JobForm from './components/JobForm.jsx';
import JobsTable from './components/JobsTable.jsx';
import JobDetails from './components/JobDetails.jsx';
import TestLab from './components/TestLab.jsx';
import InfoTip from './components/InfoTip.jsx';
import { scenarios } from './lib/scenarios.js';

const metrics = [
  { key: 'total', label: 'All jobs', icon: 'jobs', description: 'Show every job in the queue' },
  { key: 'pending', label: 'Pending', icon: 'clock', description: 'Queued or waiting to retry' },
  { key: 'claimed', label: 'Claimed', icon: 'activity', description: 'Owned by a worker' },
  { key: 'completed', label: 'Completed', icon: 'check', description: 'Successfully processed' },
  { key: 'dead_letter', label: 'Dead letter', icon: 'alert', description: 'Failure budget exhausted' },
];

function Logo() {
  return <svg viewBox="0 0 40 40" width="36" height="36" fill="none" aria-hidden="true"><rect width="40" height="40" rx="10" fill="currentColor" /><path d="M11 10v20m9-20v20m9-20v20M7 16h26M7 24h26" stroke="var(--lime)" strokeWidth="2.6" strokeLinecap="round" /></svg>;
}

export default function App() {
  const [query, setQuery] = useState({ status: '', limit: 10, offset: 0 });
  const [selectedId, setSelectedId] = useState(null);
  const [testSelection, setTestSelection] = useState('batch');
  const [view, setView] = useState(() => window.location.hash === '#test-lab' ? 'tests' : 'queue');
  useEffect(() => {
    const navigate = () => setView(window.location.hash === '#test-lab' ? 'tests' : 'queue');
    window.addEventListener('hashchange', navigate);
    return () => window.removeEventListener('hashchange', navigate);
  }, []);
  const dashboard = useDashboard({ ...query, selectedId });
  const closeDetails = useCallback(() => setSelectedId(null), []);
  const connectionError = dashboard.errors.jobs || dashboard.errors.stats;
  const connection = connectionError ? 'Reconnecting' : dashboard.lastRefresh ? 'Live' : 'Connecting';

  function selectStatus(status) {
    setQuery((previous) => ({ ...previous, status, offset: 0 }));
    document.getElementById('jobs')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function changeView(nextView) {
    setView(nextView);
    if (window.matchMedia('(max-width: 760px)').matches) {
      document.querySelector('.test-nav')?.removeAttribute('open');
    }
  }

  return <>
    <a className="skip-link" href="#main-content">Skip to dashboard</a>
    <aside className="sidebar" aria-label="Sluice navigation">
      <a className="brand" href="#overview" onClick={() => changeView('queue')} aria-label="Sluice overview"><Logo /><span>sluice<span className="brand-dot">.</span></span></a>
      <div className="sidebar-label">WORKSPACE</div>
      <nav><a className={`nav-link ${view === 'queue' ? 'active' : ''}`} aria-current={view === 'queue' ? 'page' : undefined} href="#overview" onClick={() => changeView('queue')}><Icon name="overview" />Queue</a><a className="nav-link" href="#submit" onClick={() => changeView('queue')}><Icon name="plus" />New job</a><details className="test-nav"><summary className={`nav-link ${view === 'tests' ? 'active' : ''}`}><Icon name="activity" />Tests<span className="test-nav-chevron">⌄</span></summary><div className="test-nav-items">{scenarios.map((scenario) => <div className="test-nav-row" key={scenario.id}><a className={view === 'tests' && testSelection === scenario.id ? 'selected-test' : ''} aria-current={view === 'tests' && testSelection === scenario.id ? 'page' : undefined} href="#test-lab" onClick={() => { setTestSelection(scenario.id); changeView('tests'); }}>{scenario.short}</a><InfoTip label={scenario.name}>{scenario.description}</InfoTip></div>)}</div></details></nav>
      <div className="sidebar-bottom"><div className="sidebar-footer"><span>Queue console</span><span>v0.1.0</span></div></div>
    </aside>
    <div className="workspace" id="overview">
      <header className="topbar"><div className="breadcrumb"><span>Sluice</span><Icon name="chevron" size={13} /><strong>{view === 'queue' ? 'Queue' : 'Tests'}</strong></div><div className="connection-status" data-state={connectionError ? 'error' : dashboard.lastRefresh ? 'live' : 'connecting'}><span className="status-dot" />{connection}</div></header>
      <main id="main-content" className="main-content">
        <section className="page-heading" aria-labelledby="page-title"><div><h1 id="page-title">{view === 'queue' ? 'Queue dashboard' : 'Test lab'}</h1><p>{view === 'queue' ? 'Manage jobs and monitor processing.' : 'Run a scenario. Watch the queue in action.'}</p></div>{view === 'queue' ? <a className="button button-primary new-job-button" href="#submit"><Icon name="plus" size={17} />New job</a> : <a className="button" href="#overview" onClick={() => changeView('queue')}><Icon name="jobs" size={16} />View queue</a>}</section>
        {connectionError && <div className="connection-error" role="alert"><Icon name="alert" size={18} /><div><strong>Cannot reach the queue</strong><p>{dashboard.errors.stats || connectionError} Retrying automatically.</p></div></div>}
        <section className="queue-view" hidden={view !== 'queue'} aria-label="Queue dashboard">
        <div className="overview-toolbar"><span className="section-label">JOB COUNTS</span><div className="refresh-tools"><span className="last-refresh">{dashboard.lastRefresh ? <>Updated <time dateTime={new Date(dashboard.lastRefresh).toISOString()}>{formatTime(dashboard.lastRefresh)}</time></> : 'Loading'}</span><button type="button" id="refresh-button" className="button button-small" disabled={dashboard.refreshing} onClick={dashboard.refresh}><Icon name="refresh" size={14} className={dashboard.refreshing ? 'rotating' : ''} />{dashboard.refreshing ? 'Refreshing' : 'Refresh'}</button></div></div>
        <section className="metrics-grid" aria-label="Filter jobs by status">{metrics.map((metric) => <button type="button" className={`metric-card metric-${metric.key}`} key={metric.key} title={metric.description} aria-label={`Show ${metric.key === 'total' ? 'all' : metric.label.toLowerCase()} jobs`} aria-pressed={query.status === (metric.key === 'total' ? '' : metric.key)} onClick={() => selectStatus(metric.key === 'total' ? '' : metric.key)}><span className="metric-top"><span>{metric.label}</span><span className="metric-icon"><Icon name={metric.icon} size={16} /></span></span><span className="metric-value">{dashboard.stats ? dashboard.stats[metric.key].toLocaleString() : <span className="metric-placeholder" aria-label="Not loaded">—</span>}</span></button>)}</section>
        <div className="dashboard-grid"><JobsTable jobs={dashboard.jobs} loaded={dashboard.jobsLoaded} refreshing={dashboard.refreshing} error={dashboard.errors.jobs} {...query} selectedId={selectedId} onStatusChange={(status) => setQuery((previous) => ({ ...previous, status, offset: 0 }))} onLimitChange={(limit) => setQuery((previous) => ({ ...previous, limit, offset: 0 }))} onPageChange={(offset) => setQuery((previous) => ({ ...previous, offset }))} onOpenJob={setSelectedId} onRefresh={dashboard.refresh} /><JobForm onSubmitted={dashboard.refresh} onOpenJob={setSelectedId} /></div>
        </section>
        <div hidden={view !== 'tests'}><TestLab selection={testSelection} onSelectionChange={setTestSelection} onOpenJob={setSelectedId} onChanged={dashboard.refresh} /></div>
        <footer className="page-footer"><span>Automatic updates</span><span>Times shown in your local timezone</span></footer>
      </main>
    </div>
    {selectedId !== null && <JobDetails key={selectedId} id={selectedId} job={dashboard.detail} error={dashboard.errors.detail} refreshing={dashboard.refreshing} onClose={closeDetails} onRefresh={dashboard.refresh} />}
  </>;
}
