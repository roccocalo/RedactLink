interface Props {
  filename: string | null;
}

export function FilePreview({ filename }: Props) {
  if (!filename) return null;

  const ext = filename.split('.').pop()?.toUpperCase() ?? 'FILE';
  const sanitizedName = filename.replace(/(\.[^.]+)$/, '_sanitized$1');

  return (
    <div className="card file-preview">
      <h3>File</h3>

      <div className="file-row">
        <div className="file-badge">{ext}</div>
        <div className="file-info">
          <span className="file-name" title={filename}>{filename}</span>
          <span className="file-label">Original</span>
        </div>
      </div>

      <div className="file-arrow" aria-hidden="true">↓</div>

      <div className="file-row">
        <div className="file-badge sanitized-badge">{ext}</div>
        <div className="file-info">
          <span className="file-name" title={sanitizedName}>{sanitizedName}</span>
          <span className="file-label">Sanitized · stored in S3</span>
        </div>
      </div>
    </div>
  );
}
