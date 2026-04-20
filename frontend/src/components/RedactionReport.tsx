interface Props {
  redactedCount: number | null;
  filename: string | null;
}

export function RedactionReport({ redactedCount, filename }: Props) {
  return (
    <div className="card redaction-report">
      <h3>Redaction Report</h3>

      {filename && (
        <p className="report-filename" title={filename}>{filename}</p>
      )}

      <div className="report-stat">
        <span className="stat-value">{redactedCount ?? '—'}</span>
        <span className="stat-label">PII entities redacted</span>
      </div>

      <p className="report-note">
        Detected by Microsoft Presidio<br />
        Sanitized by ZeroTrust Engine v1
      </p>
    </div>
  );
}
