import Icon from './Icon.jsx';

export default function ConnectionNotice({ connection }) {
  if (connection.ready) return null;
  const waiting = connection.phase === 'connecting';
  return <div className={`connection-notice ${waiting ? 'connection-starting' : 'connection-unavailable'}`} role={waiting ? 'status' : 'alert'}>
    <span className="connection-notice-icon" aria-hidden="true">{waiting ? <span className="spinner" /> : <Icon name="alert" size={20} />}</span>
    <div><strong>{waiting ? connection.hasConnected ? 'Reconnecting to Sluice…' : 'Connecting to Sluice…' : 'Still unable to connect'}</strong>
      <p>{waiting ? 'The demo server may be waking up. This can take 1–2 minutes.' : 'The server has not become ready after 3 minutes. Check your connection or try again.'}</p>
      {!waiting && connection.error && <details className="connection-diagnostics"><summary>Connection details</summary><p>{connection.error}</p><p>Check the backend status, frontend API URL, and allowed CORS origin.</p></details>}
    </div>
    {!waiting && <button type="button" className="button" onClick={connection.retry}><Icon name="refresh" size={14} />Try again</button>}
  </div>;
}
