/**
 * A styled dropdown, because the platform one cannot be styled here.
 *
 * `<select>`'s expanded list is drawn by the OS, so the panel in the screenshot
 * — full-bleed system-blue highlight, square corners, painted over the next
 * field — was outside this design system's reach. `appearance: base-select`
 * would fix it in pure CSS, but Electron 44's Chromium parses the property
 * without enabling the feature (`'open' in select` is false and
 * `::selectedcontent` is unsupported), so the only way to own that surface is to
 * render it.
 *
 * The keyboard contract follows the ARIA select-a-listbox pattern: the trigger
 * carries the focus and `aria-activedescendant`, arrows/Home/End move the
 * highlight, Enter/Space commit, Escape closes, and closing returns focus to the
 * trigger.
 */

import { useEffect, useId, useRef, useState } from 'react';
import type { KeyboardEvent, MouseEvent } from 'react';

export interface SelectMenuOption {
  value: string;
  label: string;
}

interface SelectMenuProps {
  label: string;
  value: string;
  options: SelectMenuOption[];
  onValueChange: (value: string) => void;
  hint?: string;
  disabled?: boolean;
  /** Kept for the smoke drivers that locate controls by test id. */
  testId?: string;
  className?: string;
}

export function SelectMenu({
  label,
  value,
  options,
  onValueChange,
  hint,
  disabled = false,
  testId,
  className = '',
}: SelectMenuProps) {
  const autoId = useId();
  const triggerId = `select-menu${autoId}`;
  const labelId = `${triggerId}-label`;
  const listId = `${triggerId}-list`;
  const hintId = `${triggerId}-hint`;

  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);

  const selected = Math.max(
    0,
    options.findIndex((option) => option.value === value)
  );
  const current = options[selected];

  useEffect(() => {
    if (open) {
      setActive(selected);
    }
  }, [open, selected]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const onPointerDown = (event: globalThis.MouseEvent) => {
      if (!root.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onPointerDown);
    return () => document.removeEventListener('mousedown', onPointerDown);
  }, [open]);

  function commit(index: number) {
    const option = options[index];
    if (!option) {
      return;
    }
    onValueChange(option.value);
    setOpen(false);
    trigger.current?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (event.key === 'Escape' && open) {
      event.preventDefault();
      setOpen(false);
      return;
    }
    if (!open) {
      if (['Enter', ' ', 'ArrowDown', 'ArrowUp'].includes(event.key)) {
        event.preventDefault();
        setOpen(true);
      }
      return;
    }
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        setActive((index) => Math.min(options.length - 1, index + 1));
        break;
      case 'ArrowUp':
        event.preventDefault();
        setActive((index) => Math.max(0, index - 1));
        break;
      case 'Home':
        event.preventDefault();
        setActive(0);
        break;
      case 'End':
        event.preventDefault();
        setActive(options.length - 1);
        break;
      case 'Enter':
      case ' ':
        event.preventDefault();
        commit(active);
        break;
      case 'Tab':
        setOpen(false);
        break;
    }
  }

  function onOptionClick(event: MouseEvent, index: number) {
    // The trigger keeps focus (it owns aria-activedescendant), so the option's
    // own click must not steal it.
    event.preventDefault();
    commit(index);
  }

  return (
    <div
      className={`select-menu${open ? ' select-menu--open' : ''}${className ? ` ${className}` : ''}`}
      ref={root}
    >
      <span className="select-menu__label" id={labelId}>
        {label}
      </span>
      <button
        type="button"
        id={triggerId}
        ref={trigger}
        className="select-menu__trigger"
        disabled={disabled}
        data-testid={testId}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-labelledby={`${labelId} ${triggerId}`}
        aria-describedby={hint ? hintId : undefined}
        aria-activedescendant={open ? `${listId}-${active}` : undefined}
        onClick={() => setOpen((isOpen) => !isOpen)}
        onKeyDown={onKeyDown}
      >
        <span className="select-menu__value">{current?.label ?? '—'}</span>
        <span className="select-menu__arrow" aria-hidden="true" />
      </button>
      {open && (
        <ul className="select-menu__list" role="listbox" id={listId} aria-labelledby={labelId}>
          {options.map((option, index) => (
            <li
              key={option.value}
              id={`${listId}-${index}`}
              role="option"
              aria-selected={option.value === value}
              data-active={index === active || undefined}
              className="select-menu__option"
              onMouseEnter={() => setActive(index)}
              onClick={(event) => onOptionClick(event, index)}
            >
              <span className="select-menu__option-label">{option.label}</span>
              {option.value === value && (
                <span className="select-menu__check" aria-hidden="true">
                  ✓
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {hint && (
        <p className="select-menu__hint" id={hintId}>
          {hint}
        </p>
      )}
    </div>
  );
}
