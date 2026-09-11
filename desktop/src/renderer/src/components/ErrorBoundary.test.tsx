/**
 * ErrorBoundary tests (FE-001.5 STRETCH A).
 *
 * - normal children render through
 * - a throwing child swaps in the safe recovery screen
 * - the thrown error/stack never appears in the UI (production-safe)
 * - retry recovers once the underlying cause is fixed (transient)
 * - retry re-catches when the error persists
 */

import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import type { ReactNode } from 'react';
import { ErrorBoundary } from './ErrorBoundary';

function Bomb({ message }: { message: string }): ReactNode {
  throw new Error(message);
}

/** Throws while `data` is "bad" — simulates a transient data problem. */
function DataDriven({ data }: { data: string }): ReactNode {
  if (data === 'bad') {
    throw new Error('bad-data');
  }
  return <div>content-{data}</div>;
}

/** Parent that can fix the data while the boundary stays mounted. */
function Harness() {
  const [data, setData] = useState<'bad' | 'good'>('bad');
  return (
    <div>
      <button type="button" onClick={() => setData('good')}>
        Fix data
      </button>
      <ErrorBoundary>
        <DataDriven data={data} />
      </ErrorBoundary>
    </div>
  );
}

describe('ErrorBoundary', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });

  it('renders children normally when nothing throws', () => {
    render(
      <ErrorBoundary>
        <div>healthy-content</div>
      </ErrorBoundary>
    );
    expect(screen.getByText('healthy-content')).toBeInTheDocument();
  });

  it('swaps in a safe recovery screen when a child throws', () => {
    render(
      <ErrorBoundary>
        <Bomb message="secret-bomb-message" />
      </ErrorBoundary>
    );
    expect(screen.getByText('页面出现异常，请重试。')).toBeInTheDocument();
  });

  it('never exposes the thrown error message or stack in the UI', () => {
    render(
      <ErrorBoundary>
        <Bomb message="secret-bomb-message" />
      </ErrorBoundary>
    );
    const uiText = document.body.textContent ?? '';
    expect(uiText).not.toContain('secret-bomb-message');
    expect(uiText).not.toContain('ErrorBoundary');
    expect(uiText).not.toMatch(/at /);
  });

  it('recovers via retry once the underlying cause is fixed', async () => {
    const user = userEvent.setup();
    render(<Harness />);

    // initial bad data -> fallback
    expect(screen.getByText('页面出现异常，请重试。')).toBeInTheDocument();

    // fixing the data alone does not dismiss the boundary...
    await user.click(screen.getByRole('button', { name: 'Fix data' }));
    expect(screen.getByText('页面出现异常，请重试。')).toBeInTheDocument();

    // ...but retry then re-renders children with the good data
    await user.click(screen.getByRole('button', { name: '重试' }));
    expect(screen.getByText('content-good')).toBeInTheDocument();
  });

  it('re-catches when the error persists after retry', async () => {
    const user = userEvent.setup();
    render(
      <ErrorBoundary>
        <Bomb message="persistent-boom" />
      </ErrorBoundary>
    );

    await user.click(screen.getByRole('button', { name: '重试' }));
    expect(screen.getByText('页面出现异常，请重试。')).toBeInTheDocument();
  });
});
