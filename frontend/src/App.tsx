import { useUpload } from './hooks/useUpload';
import { UploadZone } from './components/UploadZone';
import { ProgressTracker } from './components/ProgressTracker';
import { RedactionReport } from './components/RedactionReport';
import { FilePreview } from './components/FilePreview';
import { SharePanel } from './components/SharePanel';
import './App.css';

function App() {
  const {
    status,
    filename,
    downloadUrl,
    redactedCount,
    error,
    upload,
    reset,
    refreshLink,
  } = useUpload();

  const showResults = status === 'COMPLETED';

  return (
    <div className="app-layout">
      <header className="app-header">
        <div className="header-logo">
          <span className="logo-mark">RL</span>
          <span className="logo-text">RedactLink</span>
        </div>
        <p className="header-sub">Zero-Trust Document Sanitizer</p>
      </header>

      <main className="app-main">
        <UploadZone status={status} onFile={upload} />

        <ProgressTracker status={status} error={error} />

        {showResults && (
          <div className="results-grid">
            <FilePreview filename={filename} />
            <RedactionReport redactedCount={redactedCount} filename={filename} />
            <SharePanel downloadUrl={downloadUrl} onRefresh={refreshLink} />
          </div>
        )}

        {(status === 'COMPLETED' || status === 'FAILED') && (
          <button className="btn btn-ghost reset-btn" onClick={reset}>
            Upload another file
          </button>
        )}
      </main>

      <footer className="app-footer">
        RedactLink — Portfolio Project · Spring Boot 4 · React 19 · AWS S3 · Presidio
      </footer>
    </div>
  );
}

export default App;
