import { useEffect, useId, useRef } from 'react';
import type { ReactNode } from 'react';

interface DialogProps {
  title: string;
  onClose: () => void;
  children: ReactNode;
  /**
   * While true (pending submission), Escape / overlay-click / the close
   * button are blocked so an in-flight request is never silently
   * abandoned by a stray close.
   */
  busy?: boolean;
}

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * Lightweight accessible modal (FE-001 F3, FE-001.5 PHASE 9).
 *
 * - labeled dialog with a real title (aria-labelledby)
 * - initial focus lands on the first editable field (not the close
 *   button), focus returns to the trigger on close
 * - Tab is trapped inside the dialog while it is open
 * - Escape closes when safe (blocked while `busy`)
 */
export function Dialog({ title, onClose, children, busy = false }: DialogProps) {
  const titleId = useId();
  const panelRef = useRef<HTMLDivElement | null>(null);
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  });

  useEffect(() => {
    const panel = panelRef.current;
    if (!panel) {
      return;
    }
    const previouslyFocused = document.activeElement as HTMLElement | null;

    const focusables = Array.from(
      panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)
    );
    // Initial focus lands on a real editable field when one exists;
    // otherwise fall back to the panel itself (never a stray button).
    const firstField =
      focusables.find((el) => el.matches('input,select,textarea')) ?? panel;
    firstField.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        if (!busy) {
          onCloseRef.current();
        }
        return;
      }
      if (event.key !== 'Tab') {
        return;
      }
      // Focus trap: cycle within the dialog only.
      const items = Array.from(
        panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)
      );
      if (items.length === 0) {
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement as HTMLElement | null;
      const inside = active !== null && panel.contains(active);
      if (event.shiftKey) {
        if (!inside || active === first) {
          event.preventDefault();
          last.focus();
        }
      } else if (!inside || active === last) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      previouslyFocused?.focus?.();
    };
  }, [busy]);

  return (
    <div
      className="dialog-overlay"
      onClick={busy ? undefined : onClose}
    >
      <div
        ref={panelRef}
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onClick={(event) => event.stopPropagation()}
      >
        <header className="dialog__header">
          <h2 className="dialog__title" id={titleId}>
            {title}
          </h2>
          <button
            type="button"
            className="dialog__close"
            aria-label="关闭"
            disabled={busy}
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
