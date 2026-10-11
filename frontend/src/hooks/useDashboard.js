import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../lib/api.js';
import { createPoller } from '../lib/poller.js';
import { isConnectionFailure } from '../lib/connection.js';

export default function useDashboard({ status, limit, offset, selectedId, enabled, onConnectionLost }) {
  const queryKey = `${status}:${limit}:${offset}`;
  const [snapshot, setSnapshot] = useState({
    jobs: [], stats: null, detail: null, queryKey: null, detailId: null,
    errors: {}, errorQueryKey: null, errorDetailId: null, lastRefresh: null, refreshing: false,
  });
  const poller = useRef(null);

  useEffect(() => {
    if (!enabled) return;
    const instance = createPoller(async (signal) => {
      if (signal.aborted) return;
      setSnapshot((previous) => ({ ...previous, refreshing: true }));
      const calls = [
        ['jobs', api.listJobs({ status, limit, offset, signal })],
        ['stats', api.getStats({ signal })],
      ];
      if (selectedId !== null) calls.push(['detail', api.getJob(selectedId, { signal })]);
      const results = await Promise.allSettled(calls.map(([, promise]) => promise));
      // Aborting an old query also prevents clients that ignore abort from applying it.
      if (signal.aborted) return;
      setSnapshot((previous) => {
        const next = { ...previous, errors: {}, errorQueryKey: queryKey, errorDetailId: selectedId, refreshing: false };
        results.forEach((result, index) => {
          const section = calls[index][0];
          if (result.status === 'fulfilled') {
            next[section] = result.value;
            if (section === 'jobs') next.queryKey = queryKey;
            if (section === 'detail') next.detailId = selectedId;
          } else if (section === 'detail' || !isConnectionFailure(result.reason)) {
            next.errors[section] = result.reason.message;
          }
        });
        if (results.every((result) => result.status === 'fulfilled')) next.lastRefresh = Date.now();
        return next;
      });
      const failure = results.find((result, index) => calls[index][0] !== 'detail'
        && result.status === 'rejected' && isConnectionFailure(result.reason));
      if (failure) onConnectionLost(failure.reason);
    });
    poller.current = instance;
    return () => { instance.stop(); poller.current = null; };
  }, [status, limit, offset, selectedId, queryKey, enabled, onConnectionLost]);

  const refresh = useCallback(() => poller.current?.refresh(), []);
  return {
    ...snapshot,
    refreshing: enabled && snapshot.refreshing,
    errors: {
      ...snapshot.errors,
      jobs: snapshot.errorQueryKey === queryKey ? snapshot.errors.jobs : undefined,
      detail: snapshot.errorDetailId === selectedId ? snapshot.errors.detail : undefined,
    },
    // A previous page must never appear under a new filter or page number.
    jobs: snapshot.queryKey === queryKey ? snapshot.jobs : [],
    jobsLoaded: snapshot.queryKey === queryKey,
    detail: snapshot.detailId === selectedId ? snapshot.detail : null,
    refresh,
  };
}
