import type { UploadRequest, UploadResponse, LinkResponse } from '../types';

export const API_BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw Object.assign(new Error(text || res.statusText), { status: res.status });
  }
  return res.json() as Promise<T>;
}

export const api = {
  requestUploadUrl: (body: UploadRequest): Promise<UploadResponse> =>
    fetch(`${API_BASE}/api/v1/uploads/request-url`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).then(handleResponse<UploadResponse>),

  generateLink: (fileId: string): Promise<LinkResponse> =>
    fetch(`${API_BASE}/api/v1/links/${fileId}`, {
      method: 'POST',
    }).then(handleResponse<LinkResponse>),
};
