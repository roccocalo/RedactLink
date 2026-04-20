interface Props {
  redactedCount: number | null;
  filename: string | null;
  alreadySanitized: boolean;
}

export function RedactionReport({ redactedCount, filename, alreadySanitized }: Props) {
  return (
    <div className="card redaction-report">
      <h3>Redaction Report</h3>

      {filename && (
        <p className="report-filename" title={filename}>{filename}</p>
      )}

      {alreadySanitized ? (
        <div className="report-cached">
          <span className="cached-badge">Already processed</span>
          <p className="report-note" style={{ marginTop: 12 }}>
            This file was previously sanitized.<br />
            Serving the existing result from cache.
          </p>
        </div>
      ) : (
        <>
          <div className="report-stat">
            <span className="stat-value">{redactedCount ?? '—'}</span>
            <span className="stat-label">PII entities redacted</span>
          </div>
          <p className="report-note">
            Detected by Microsoft Presidio<br />
            Sanitized by ZeroTrust Engine v1
          </p>
        </>
      )}
    </div>
  );
}
