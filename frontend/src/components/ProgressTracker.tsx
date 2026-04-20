import type { FileStatus } from '../types';

const STEPS: { key: FileStatus; label: string }[] = [
  { key: 'PENDING',    label: 'Hashing & validating' },
  { key: 'PROCESSING', label: 'AI analysis & redaction' },
  { key: 'COMPLETED',  label: 'Complete' },
];

function stepIndex(status: FileStatus): number {
  if (status === 'PENDING')    return 0;
  if (status === 'PROCESSING') return 1;
  if (status === 'COMPLETED')  return 2;
  return -1;
}

interface Props {
  status: FileStatus;
  error: string | null;
}

export function ProgressTracker({ status, error }: Props) {
  if (status === 'IDLE') return null;

  if (status === 'FAILED') {
    return (
      <div className="progress-tracker">
        <div className="progress-error" role="alert">
          <span className="progress-error-icon" aria-hidden="true">✕</span>
          <span>{error ?? 'Processing failed'}</span>
        </div>
      </div>
    );
  }

  const current = stepIndex(status);

  return (
    <div className="progress-tracker" aria-label="Processing status">
      <div className="progress-steps">
        {STEPS.map((step, i) => {
          const done   = i < current;
          const active = i === current;
          return (
            <div
              key={step.key}
              className={`progress-step ${done ? 'done' : ''} ${active ? 'active' : ''}`}
            >
              <div className="step-indicator" aria-hidden="true">
                {done ? (
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                ) : (
                  <span className="step-num">{i + 1}</span>
                )}
              </div>
              <span className="step-label">{step.label}</span>
              {active && status !== 'COMPLETED' && (
                <span className="step-spinner" aria-hidden="true" />
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
