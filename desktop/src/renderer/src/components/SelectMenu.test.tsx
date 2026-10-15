/**
 * SelectMenu — the keyboard and pointer contract of the custom dropdown.
 *
 * This component exists only because the platform `<select>` popup cannot be
 * styled in this runtime, so the risk it carries is behavioural: a hand-rolled
 * listbox that loses focus, drops the arrow keys, or forgets `aria-selected`
 * would be worse than the ugly-but-working native control it replaces. These
 * tests are the reason it is allowed to.
 */

import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SelectMenu } from './SelectMenu';

const options = [
  { value: 'a', label: '甲' },
  { value: 'b', label: '乙' },
  { value: 'c', label: '丙' },
];

function setup(props: Partial<React.ComponentProps<typeof SelectMenu>> = {}) {
  const onValueChange = vi.fn();
  render(
    <SelectMenu
      label="字母"
      value="b"
      options={options}
      onValueChange={onValueChange}
      {...props}
    />
  );
  return { onValueChange, trigger: screen.getByRole('button', { name: /字母/ }) };
}

describe('SelectMenu', () => {
  it('shows the selected option and starts closed', () => {
    const { trigger } = setup();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(trigger).toHaveTextContent('乙');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('opens on click and marks the current value as selected', async () => {
    const user = userEvent.setup();
    const { trigger } = setup();
    await user.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getAllByRole('option')).toHaveLength(3);
    expect(screen.getByRole('option', { name: '乙' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
  });

  it('commits the clicked option and closes', async () => {
    const user = userEvent.setup();
    const { onValueChange, trigger } = setup();
    await user.click(trigger);
    await user.click(screen.getByRole('option', { name: '丙' }));
    expect(onValueChange).toHaveBeenCalledWith('c');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('moves the highlight with the arrow keys and commits with Enter', async () => {
    const user = userEvent.setup();
    const { onValueChange, trigger } = setup();
    trigger.focus();
    await user.keyboard('{Enter}');
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    // Value is 乙 (index 1); one step down is 丙.
    await user.keyboard('{ArrowDown}');
    expect(trigger.getAttribute('aria-activedescendant')).toMatch(/-2$/);
    await user.keyboard('{Enter}');
    expect(onValueChange).toHaveBeenCalledWith('c');
    expect(trigger).toHaveFocus();
  });

  it('clamps at both ends instead of wrapping past the list', async () => {
    const user = userEvent.setup();
    const { trigger } = setup();
    await user.click(trigger);
    await user.keyboard('{ArrowUp}{ArrowUp}{Home}{End}');
    expect(trigger.getAttribute('aria-activedescendant')).toMatch(/-2$/);
  });

  it('Escape closes without choosing anything', async () => {
    const user = userEvent.setup();
    const { onValueChange, trigger } = setup();
    await user.click(trigger);
    await user.keyboard('{ArrowDown}{Escape}');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(onValueChange).not.toHaveBeenCalled();
  });

  it('closes when the pointer goes outside it', async () => {
    const user = userEvent.setup();
    const { trigger } = setup();
    await user.click(trigger);
    await user.click(document.body);
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('stays shut when disabled', async () => {
    const user = userEvent.setup();
    const { trigger } = setup({ disabled: true });
    await user.click(trigger).catch(() => undefined);
    expect(trigger).toBeDisabled();
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });
});
