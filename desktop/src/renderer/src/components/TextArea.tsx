import { useId } from 'react';
import type { TextareaHTMLAttributes } from 'react';

interface TextAreaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  label: string;
  hint?: string;
}

export function TextArea({ label, hint, id, className = '', ...rest }: TextAreaProps) {
  const autoId = useId();
  const textareaId = id ?? autoId;
  return (
    <div className="field">
      <label className="field__label" htmlFor={textareaId}>
        {label}
      </label>
      <textarea id={textareaId} className={`textarea ${className}`} {...rest} />
      {hint && <p className="field__hint">{hint}</p>}
    </div>
  );
}
