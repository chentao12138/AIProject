import { useEffect } from 'react';
import type { ReactNode } from 'react';

interface DialogProps {
  title: string;
  onClose: () => void;
  children: ReactNode;
}

/**
 * Lightweight accessible modal (FE-001 F3). No focus-trap library —
 * Escape closes, overlay click closes, content is a labeled dialog.
 */
export function Dialog({ title, onClose, children }: DialogProps) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  return (
    <div className="dialog-overlay" onClick={onClose}>
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(event) => event.stopPropagation()}
      >
        <header className="dialog__header">
          <h2 className="dialog__title">{title}</h2>
          <button
            type="button"
            className="dialog__close"
            aria-label="关闭"
            onClick={onClose}
          >
            ×
          </button>
        </header>
        <div className="dialog__body">{children}</div>
      </div>
    </div>
  );
}
