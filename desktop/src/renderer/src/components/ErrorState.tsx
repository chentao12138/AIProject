import type { ReactNode } from 'react';

interface ErrorStateProps {
  message: string;
  detail?: string;
  onRetry?: () => void;
  children?: ReactNode;
}

export function ErrorState({ message, detail, onRetry, children }: ErrorStateProps) {
  return (
    <div className="error-state" role="alert">
      <p className="error-state__message">{message}</p>
      {detail && <p className="error-state__detail">{detail}</p>}
      {onRetry && (
        <button type="button" className="btn btn--secondary" onClick={onRetry}>
          重试
        </button>
      )}
      {children}
    </div>
  );
}
