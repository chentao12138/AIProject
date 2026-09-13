interface LoadingStateProps {
  text?: string;
}

export function LoadingState({ text = '加载中…' }: LoadingStateProps) {
  return (
    <div className="loading-state" role="status" aria-live="polite">
      <span className="loading-state__spinner" aria-hidden="true" />
      <span>{text}</span>
    </div>
  );
}
