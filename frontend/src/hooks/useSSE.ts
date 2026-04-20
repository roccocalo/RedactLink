import { useEffect, useRef } from 'react';
import type { SseEvent } from '../types';
import { API_BASE } from '../api/client';

export function useSSE(
  fileId: string | null,
  onEvent: (event: SseEvent) => void,
) {
  const callbackRef = useRef(onEvent);
  callbackRef.current = onEvent;

  useEffect(() => {
    if (!fileId) return;
    const es = new EventSource(`${API_BASE}/api/v1/updates/${fileId}`);
    es.onmessage = (e) => {
      callbackRef.current(JSON.parse(e.data) as SseEvent);
      es.close();
    };
    es.onerror = () => {
      callbackRef.current({ status: 'FAILED', error: 'Connection lost' });
      es.close();
    };
    return () => es.close();
  }, [fileId]);
}
