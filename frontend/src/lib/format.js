export const statuses = [
  { value: 'pending', label: 'Pending', description: 'Ready or waiting to retry' },
  { value: 'claimed', label: 'Claimed', description: 'Owned by a worker' },
  { value: 'completed', label: 'Completed', description: 'Successfully processed' },
  { value: 'dead_letter', label: 'Dead letter', description: 'Failure budget exhausted' },
];

export function formatDate(value, { compact = false } = {}) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat(undefined, {
    month: 'short', day: 'numeric', ...(compact ? {} : { year: 'numeric' }),
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).format(date);
}

export function formatTime(value) {
  return new Intl.DateTimeFormat(undefined, {
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).format(new Date(value));
}

export function formatPayload(value) {
  try {
    return { text: JSON.stringify(JSON.parse(value), null, 2), valid: true };
  } catch {
    return { text: String(value ?? ''), valid: false };
  }
}
