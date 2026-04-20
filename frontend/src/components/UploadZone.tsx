import { useRef, useState } from 'react';
import type { DragEvent } from 'react';
import type { FileStatus } from '../types';

interface Props {
  status: FileStatus;
  onFile: (file: File) => void;
}

export function UploadZone({ status, onFile }: Props) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);

  const disabled = status === 'PENDING' || status === 'PROCESSING';

  function handleDrop(e: DragEvent<HTMLDivElement>) {
    e.preventDefault();
    setDragging(false);
    if (disabled) return;
    const file = e.dataTransfer.files[0];
    if (file) onFile(file);
  }

  function handleChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (file) onFile(file);
    e.target.value = '';
  }

  function handleClick() {
    if (!disabled) inputRef.current?.click();
  }

  return (
    <div
      className={`upload-zone ${dragging ? 'dragging' : ''} ${disabled ? 'disabled' : ''}`}
      onClick={handleClick}
      onDragOver={(e) => { e.preventDefault(); if (!disabled) setDragging(true); }}
      onDragLeave={() => setDragging(false)}
      onDrop={handleDrop}
      role="button"
      tabIndex={disabled ? -1 : 0}
      aria-label="Upload file for sanitization"
      onKeyDown={(e) => e.key === 'Enter' && handleClick()}
    >
      <input
        ref={inputRef}
        type="file"
        accept=".pdf,.txt,.log,.csv,.docx"
        onChange={handleChange}
        style={{ display: 'none' }}
        tabIndex={-1}
      />

      <div className="upload-icon">
        <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
          <polyline points="17 8 12 3 7 8" />
          <line x1="12" y1="3" x2="12" y2="15" />
        </svg>
      </div>

      <p className="upload-title">
        {disabled ? 'Processing…' : 'Drop a file or click to browse'}
      </p>
      <p className="upload-sub">PDF · TXT · LOG · CSV — max 50 MB</p>
    </div>
  );
}
