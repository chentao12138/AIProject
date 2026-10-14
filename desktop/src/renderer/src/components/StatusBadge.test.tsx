/**
 * StatusBadge tests (FE-001.5 PRE-COMMIT REVIEW FIX-01).
 *
 * Exact status mapping: DRAFT/PUBLISHED/ACTIVE get their variants;
 * EVERYTHING else (including substring lookalikes like INACTIVE and
 * UNPUBLISHED) renders as the neutral unknown badge. Missing/empty
 * status renders the "—" placeholder.
 *
 * Queried by `title`, which always carries the raw backend value — the
 * visible label is display copy and is free to change.
 */

import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatusBadge } from './StatusBadge';

function renderBadge(status?: string) {
  return render(<StatusBadge status={status} />);
}

describe('StatusBadge — exact known statuses', () => {
  it('maps DRAFT to the draft variant', () => {
    renderBadge('DRAFT');
    expect(screen.getByTitle('DRAFT')).toHaveClass('badge--draft');
  });

  it('maps PUBLISHED to the published variant', () => {
    renderBadge('PUBLISHED');
    expect(screen.getByTitle('PUBLISHED')).toHaveClass('badge--published');
  });

  it('maps ACTIVE to the active variant', () => {
    renderBadge('ACTIVE');
    expect(screen.getByTitle('ACTIVE')).toHaveClass('badge--active');
  });
});

describe('StatusBadge — no substring misclassification (FIX-01)', () => {
  it('INACTIVE must NOT render as active', () => {
    renderBadge('INACTIVE');
    const badge = screen.getByTitle('INACTIVE');
    expect(badge).toHaveClass('badge--unknown');
    expect(badge).not.toHaveClass('badge--active');
  });

  it('UNPUBLISHED must NOT render as published', () => {
    renderBadge('UNPUBLISHED');
    const badge = screen.getByTitle('UNPUBLISHED');
    expect(badge).toHaveClass('badge--unknown');
    expect(badge).not.toHaveClass('badge--published');
  });

  it('REGISTERED renders as the neutral unknown badge', () => {
    renderBadge('REGISTERED');
    expect(screen.getByTitle('REGISTERED')).toHaveClass('badge--unknown');
  });

  it('any unknown backend string stays neutral', () => {
    renderBadge('ARCHIVED');
    expect(screen.getByTitle('ARCHIVED')).toHaveClass('badge--unknown');
  });
});

describe('StatusBadge — edge input', () => {
  it('renders — for a missing status', () => {
    renderBadge(undefined);
    expect(screen.getByText('—')).toHaveClass('badge--unknown');
  });

  it('renders — for an empty status', () => {
    renderBadge('');
    expect(screen.getByText('—')).toHaveClass('badge--unknown');
  });

  it('normalizes case and surrounding whitespace', () => {
    renderBadge(' draft ');
    // Title queries also whitespace-normalize — use a regex.
    expect(screen.getByTitle(/draft/)).toHaveClass('badge--draft');
  });
});
