/**
 * PageHeader — consistent page title + actions row (FE-001.5 PHASE 17).
 *
 * Extracted because every page repeated the same
 * `.page__header > h1 + actions` structure (Spaces/Sources/Knowledge/
 * Detail). Keeps the h1 as the single page-level heading.
 */

import type { ReactNode } from 'react';

interface PageHeaderProps {
  title: string;
  actions?: ReactNode;
}

export function PageHeader({ title, actions }: PageHeaderProps) {
  return (
    <div className="page__header">
      <h1 className="page__title">{title}</h1>
      {actions && <div className="page__actions">{actions}</div>}
    </div>
  );
}
