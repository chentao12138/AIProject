import type { ReactNode } from 'react';

interface CardProps {
  title?: string;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}

/**
 * Non-interactive panel. Cards never attach click handlers — anything
 * clickable/navigable must use a real <Link> or <button> (FE-001 F4
 * accessibility contract, pre-commit review fix #4).
 */
export function Card({ title, actions, children, className = '' }: CardProps) {
  return (
    <section className={`card ${className}`} aria-label={title}>
      {(title || actions) && (
        <header className="card__header">
          {title && <h2 className="card__title">{title}</h2>}
          {actions && <div className="card__actions">{actions}</div>}
        </header>
      )}
      <div className="card__body">{children}</div>
    </section>
  );
}
