import { useId } from 'react';
import type { ReactNode, SelectHTMLAttributes } from 'react';

interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  label: string;
  hint?: string;
  children: ReactNode;
}

export function Select({ label, hint, id, className = '', children, ...rest }: SelectProps) {
  const autoId = useId();
  const selectId = id ?? autoId;
  return (
    <div className="field">
      <label className="field__label" htmlFor={selectId}>
        {label}
      </label>
      <select id={selectId} className={`select ${className}`} {...rest}>
        {children}
      </select>
      {hint && <p className="field__hint">{hint}</p>}
    </div>
  );
}
