import { useState, useCallback, useEffect, useRef } from 'react';
import type { FileStatus } from '../types';
import { api, API_BASE } from '../api/client';

const ACCEPTED_MIME = new Set([
  'application/pdf',
  'text/plain',
  'text/csv',
  'text/x-log',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
]);

const EXT_MIME: Record<string, string> = {
  '.log': 'text/x-log',
  '.txt': 'text/plain',
  '.csv': 'text/csv',
};

function resolveContentType(file: File): string {
  if (file.type) return file.type;
  const ext = file.name.slice(file.name.lastIndexOf('.')).toLowerCase();
  return EXT_MIME[ext] ?? '';
}

const MAX_SIZE = 50 * 1024 * 1024;

async function sha256hex(file: File): Promise<string> {
  const buf = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(buf))
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
}

interface UploadState {
  status: FileStatus;
  fileId: string | null;
  filename: string | null;
  downloadUrl: string | null;
  redactedCount: number | null;
  alreadySanitized: boolean;
  error: string | null;
}

const INITIAL: UploadState = {
  status: 'IDLE',
  fileId: null,
  filename: null,
  downloadUrl: null,
  redactedCount: null,
  alreadySanitized: false,
  error: null,
};

export function useUpload() {
  const [state, setState] = useState<UploadState>(INITIAL);
  const esRef = useRef<EventSource | null>(null);

  useEffect(() => () => { esRef.current?.close(); }, []);

  const upload = useCallback(async (file: File) => {
    esRef.current?.close();

    const contentType = resolveContentType(file);
    if (!ACCEPTED_MIME.has(contentType)) {
      setState({ ...INITIAL, status: 'FAILED', error: `Unsupported file type: ${contentType || 'unknown'}` });
      return;
    }
    if (file.size > MAX_SIZE) {
      setState({ ...INITIAL, status: 'FAILED', error: 'File exceeds the 50 MB limit.' });
      return;
    }

    setState({ ...INITIAL, status: 'PENDING', filename: file.name });

    try {
      const sha256 = await sha256hex(file);

      const response = await api.requestUploadUrl({
        filename: file.name,
        size: file.size,
        contentType,
        sha256,
      });

      if (response.alreadySanitized && response.downloadUrl) {
        setState(s => ({
          ...s,
          status: 'COMPLETED',
          fileId: response.fileId,
          downloadUrl: response.downloadUrl ?? null,
          redactedCount: null,
          alreadySanitized: true,
        }));
        return;
      }

      setState(s => ({ ...s, fileId: response.fileId, status: 'PROCESSING' }));

      // Open SSE BEFORE the S3 PUT to avoid missing the terminal event
      const es = new EventSource(`${API_BASE}/api/v1/updates/${response.fileId}`);
      esRef.current = es;

      es.onmessage = (e) => {
        const event = JSON.parse(e.data);
        if (event.status === 'COMPLETED') {
          setState(s => ({
            ...s,
            status: 'COMPLETED',
            downloadUrl: event.downloadUrl ?? null,
            redactedCount: event.redactedCount ?? null,
          }));
        } else {
          setState(s => ({
            ...s,
            status: 'FAILED',
            error: event.error ?? 'Processing failed',
          }));
        }
        es.close();
      };

      es.onerror = () => {
        setState(s => ({ ...s, status: 'FAILED', error: 'Lost connection to server.' }));
        es.close();
      };

      // Skip PUT if this is a duplicate in-flight (uploadUrl absent)
      if (response.uploadUrl) {
        await fetch(response.uploadUrl, {
          method: 'PUT',
          body: file,
          headers: { 'Content-Type': contentType },
        });
      }
    } catch (err) {
      const e = err as Error & { status?: number };
      let msg = e.message || 'Upload failed';
      if (e.status === 429) msg = 'Rate limit reached — try again in an hour.';
      if (e.status === 415) msg = 'File type not supported.';
      if (e.status === 400) msg = 'Invalid request (file too large or bad hash).';
      setState(s => ({ ...s, status: 'FAILED', error: msg }));
    }
  }, []);

  const reset = useCallback(() => {
    esRef.current?.close();
    setState(INITIAL);
  }, []);

  const refreshLink = useCallback(async (): Promise<string | null> => {
    if (!state.fileId) return null;
    try {
      const { downloadUrl } = await api.generateLink(state.fileId);
      setState(s => ({ ...s, downloadUrl }));
      return downloadUrl;
    } catch {
      return null;
    }
  }, [state.fileId]);

  return { ...state, upload, reset, refreshLink };
}
