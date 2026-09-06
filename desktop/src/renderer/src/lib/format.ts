/**
 * Date formatting (FE-001 PHASE J). No date library dependency.
 */

const EMPTY_DATE = '—';

export function formatDateTime(value?: string | null): string {
  if (!value) {
    return EMPTY_DATE;
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return EMPTY_DATE;
  }
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}
