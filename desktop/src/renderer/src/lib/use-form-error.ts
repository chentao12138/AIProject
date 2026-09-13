/**
 * Form error state (FE-001.5 PHASE 9/10).
 *
 * A mutation error arrives via props and stays until the mutation
 * state clears — but once the user edits a field, a stale "create
 * failed" message is misleading. This hook mirrors the prop error into
 * local state and clears it on the next edit (clear()).
 */

import { useEffect, useState } from 'react';

export function useFormError(error: { message: string } | null): {
  message: string | null;
  clear: () => void;
} {
  const [message, setMessage] = useState<string | null>(null);

  // Sync from the prop; depends on the message STRING so re-renders
  // with a freshly normalized (same-text) error are no-ops.
  useEffect(() => {
    setMessage(error?.message ?? null);
  }, [error?.message]);

  return {
    message,
    clear: () => setMessage(null),
  };
}
