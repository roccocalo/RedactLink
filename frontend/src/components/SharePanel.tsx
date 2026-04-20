import { useState } from 'react';

interface Props {
  downloadUrl: string | null;
  onRefresh: () => Promise<string | null>;
}

export function SharePanel({ downloadUrl, onRefresh }: Props) {
  const [copied, setCopied]       = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  async function handleCopy() {
    if (!downloadUrl) return;
    await navigator.clipboard.writeText(downloadUrl);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  async function handleRefresh() {
    setRefreshing(true);
    await onRefresh();
    setRefreshing(false);
  }

  return (
    <div className="card share-panel">
      <h3>Share Sanitized File</h3>
      <p className="share-sub">
        Presigned S3 link · expires in 1 hour<br />
        Direct download — backend never touches file bytes
      </p>

      <div className="share-actions">
        <a
          className="btn btn-primary"
          href={downloadUrl ?? '#'}
          target="_blank"
          rel="noopener noreferrer"
          aria-disabled={!downloadUrl}
          style={!downloadUrl ? { pointerEvents: 'none', opacity: 0.45 } : {}}
        >
          Download file
        </a>

        <button
          className="btn btn-ghost"
          onClick={handleCopy}
          disabled={!downloadUrl}
        >
          {copied ? 'Copied!' : 'Copy link'}
        </button>

        <button
          className="btn btn-ghost"
          onClick={handleRefresh}
          disabled={refreshing}
        >
          {refreshing ? 'Refreshing…' : 'Get fresh link'}
        </button>
      </div>
    </div>
  );
}
