export class ApiError extends Error {
  constructor(message, status = null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

export function serializeJob({ jobType, payload, idempotencyKey }) {
  if (!jobType.trim()) throw new Error('Enter a job type.');
  let parsed;
  try {
    parsed = JSON.parse(payload);
  } catch {
    throw new Error('Payload must be valid JSON. Check quotes, commas, and brackets.');
  }
  return {
    jobType: jobType.trim(),
    payload: JSON.stringify(parsed),
    idempotencyKey: idempotencyKey?.trim() || null,
    // EnqueueRequest has a primitive int; Boot's JSON mapper rejects it when omitted.
    priority: 0,
  };
}

export function createApi({
  baseUrl = import.meta.env?.VITE_API_BASE_URL ?? '/api',
  fetchImpl = globalThis.fetch,
  timeoutMs = 10000,
} = {}) {
  const base = baseUrl.replace(/\/+$/, '');

  async function request(path, { signal, ...options } = {}) {
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (signal?.aborted) controller.abort();
    signal?.addEventListener('abort', abort, { once: true });
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, timeoutMs);

    try {
      const response = await fetchImpl(`${base}${path}`, {
        cache: 'no-store',
        ...options,
        signal: controller.signal,
        headers: { Accept: 'application/json', ...options.headers },
      });
      const body = await response.text();
      let data;
      try {
        data = body ? JSON.parse(body) : null;
      } catch {
        if (response.ok) throw new ApiError('The API returned invalid JSON.', response.status);
      }
      if (!response.ok) {
        const detail = data?.detail || data?.message || data?.error;
        const fallback = response.status === 404
          ? 'This job could not be found.'
          : response.status >= 500
            ? 'The API is unavailable. Check that Sluice and PostgreSQL are running.'
            : 'The API rejected this request.';
        throw new ApiError(
          `${typeof detail === 'string' ? detail.slice(0, 240) : fallback} (HTTP ${response.status})`,
          response.status,
        );
      }
      return data;
    } catch (error) {
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError');
      if (timedOut) throw new ApiError('The API took too long to respond. Live updates will retry.');
      if (error instanceof ApiError) throw error;
      throw new ApiError('Cannot reach Sluice. Check that the API is running and the API URL is correct.');
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', abort);
    }
  }

  return {
    async listJobs({ status = '', limit = 50, offset = 0, signal } = {}) {
      const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
      if (status) params.set('status', status);
      const data = await request(`/jobs?${params}`, { signal });
      if (!Array.isArray(data)) throw new ApiError('The jobs API must return a JSON array.');
      return data;
    },
    async getStats({ signal } = {}) {
      const data = await request('/jobs/stats', { signal });
      const keys = ['total', 'pending', 'claimed', 'completed', 'dead_letter'];
      if (!data || keys.some((key) => !Number.isFinite(data[key]) || data[key] < 0)) {
        throw new ApiError('The statistics API returned an unexpected response.');
      }
      return data;
    },
    async getJob(id, { signal } = {}) {
      const data = await request(`/jobs/${encodeURIComponent(id)}`, { signal });
      if (!data || typeof data.id !== 'number' || typeof data.status !== 'string') {
        throw new ApiError('The job API returned an unexpected response.');
      }
      return data;
    },
    submitJob(fields, { signal } = {}) {
      return request('/jobs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(serializeJob(fields)),
        signal,
      });
    },
    submitBatch(fields, count, { signal } = {}) {
      return request('/jobs/batch', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...serializeJob(fields), count }), signal,
      });
    },
    startTest(options, { signal } = {}) {
      return request('/tests/runs', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(options), signal,
      });
    },
    getTest(id, { signal } = {}) {
      return request(`/tests/runs/${encodeURIComponent(id)}`, { signal });
    },
    currentTest({ signal } = {}) { return request('/tests/runs/current', { signal }); },
  };
}

export const api = createApi();
