import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../lib/api.js';
import { createConnection } from '../lib/connection.js';

export default function useConnection() {
  const [state, setState] = useState({ phase: 'connecting', hasConnected: false, error: '' });
  const connection = useRef(null);
  useEffect(() => {
    const instance = createConnection({
      probe: (signal) => api.getStats({ signal }),
      onChange: setState,
    });
    connection.current = instance;
    return () => instance.stop();
  }, []);
  const retry = useCallback(() => connection.current?.retry(), []);
  const disconnected = useCallback((error) => connection.current?.disconnected(error), []);
  return { ...state, ready: state.phase === 'ready', retry, disconnected };
}
