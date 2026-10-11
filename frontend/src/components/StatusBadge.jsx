import { statuses } from '../lib/format.js';

export default function StatusBadge({ status }) {
  const known = statuses.find((item) => item.value === status);
  return <span className={`status-badge status-${known ? status : 'unknown'}`}><span className="status-dot" />{known?.label || status || 'Unknown'}</span>;
}
