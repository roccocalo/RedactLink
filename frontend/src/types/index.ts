export type FileStatus = 'IDLE' | 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface UploadRequest {
  filename: string;
  size: number;
  contentType: string;
  sha256: string;
}

export interface UploadResponse {
  fileId: string;
  uploadUrl?: string;
  downloadUrl?: string;
  alreadySanitized: boolean;
  expiresAt?: string;
}

export interface SseEvent {
  status: 'COMPLETED' | 'FAILED';
  downloadUrl?: string;
  redactedCount?: number;
  error?: string;
}

export interface LinkResponse {
  downloadUrl: string;
}
