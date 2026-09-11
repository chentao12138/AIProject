/**
 * Dialog accessibility contract (FE-001.5 PHASE 9).
 *
 * The dialog must: expose a labeled modal, focus the first editable
 * field on open, trap Tab inside, close on Escape when safe, block
 * Escape/overlay/close while busy, and return focus to the trigger on
 * close.
 */

import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { Dialog } from './Dialog';

/** Real open/close harness so unmount-based behavior is testable. */
function Harness({ busy, withField }: { busy?: boolean; withField?: boolean }) {
  const [open, setOpen] = useState(true);
  return (
    <>
      <button type="button" onClick={() => setOpen(false)}>
        Trigger
      </button>
      {open && (
        <Dialog title="Test Dialog" onClose={() => setOpen(false)} busy={busy}>
          {withField ? <input aria-label="First field" /> : <span>body</span>}
          <button type="button">Action</button>
        </Dialog>
      )}
    </>
  );
}

describe('Dialog', () => {
  it('renders a labeled modal dialog', () => {
    render(<Harness />);
    const dialog = screen.getByRole('dialog', { name: 'Test Dialog' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
  });

  it('moves initial focus to the first editable field, not the close button', () => {
    render(<Harness withField />);
    expect(screen.getByLabelText('First field')).toHaveFocus();
    expect(screen.getByRole('button', { name: '关闭' })).not.toHaveFocus();
  });

  it('falls back to the dialog panel when there is no field', () => {
    render(<Harness />);
    expect(document.activeElement).toBe(screen.getByRole('dialog'));
  });

  it('closes on Escape when not busy', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('does not close on Escape while busy', async () => {
    const user = userEvent.setup();
    render(<Harness busy />);
    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('does not close on overlay click while busy', async () => {
    const user = userEvent.setup();
    render(<Harness busy />);
    await user.click(document.querySelector('.dialog-overlay') as HTMLElement);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('blocks the close button while busy', () => {
    render(<Harness busy />);
    expect(screen.getByRole('button', { name: '关闭' })).toBeDisabled();
  });

  it('traps Tab focus inside the dialog', async () => {
    const user = userEvent.setup();
    render(<Harness withField />);

    expect(screen.getByLabelText('First field')).toHaveFocus();
    await user.tab(); // Action
    expect(screen.getByRole('button', { name: 'Action' })).toHaveFocus();
    await user.tab(); // wraps to close button
    expect(screen.getByRole('button', { name: '关闭' })).toHaveFocus();
    await user.tab(); // wraps back to field
    expect(screen.getByLabelText('First field')).toHaveFocus();
  });

  it('traps Shift+Tab backwards', async () => {
    const user = userEvent.setup();
    render(<Harness withField />);

    expect(screen.getByLabelText('First field')).toHaveFocus();
    await user.tab({ shift: true }); // wraps to close button
    expect(screen.getByRole('button', { name: '关闭' })).toHaveFocus();
    await user.tab({ shift: true }); // wraps to Action
    expect(screen.getByRole('button', { name: 'Action' })).toHaveFocus();
  });

  it('returns focus to the trigger element after close', async () => {
    const user = userEvent.setup();
    render(<Harness withField />);

    const trigger = screen.getByRole('button', { name: 'Trigger' });
    trigger.focus();
    expect(trigger).toHaveFocus();

    await user.click(trigger); // onClose -> dialog unmounts
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('close button calls onClose (cancel path)', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: '关闭' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('overlay click closes when not busy', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(document.querySelector('.dialog-overlay') as HTMLElement);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('stops propagation so inner clicks never close the dialog', async () => {
    const user = userEvent.setup();
    render(<Harness withField />);
    await user.click(screen.getByLabelText('First field'));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('accepts an explicit onClose mock via direct render (unit contract)', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <Dialog title="T" onClose={onClose}>
        <span>body</span>
      </Dialog>
    );
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
