/**
 * Date / size formatting (FE-001 PHASE J, FE-002A). No date library.
 */

const EMPTY = '—';

export function formatDateTime(value?: string | null): string {
  if (!value) {
    return EMPTY;
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return EMPTY;
  }
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * Human-readable byte size (FE-002A PHASE 11).
 * undefined / invalid / negative → "—". 0 → "0 B".
 * Binary units (1024), one decimal for KB+.
 */
export function formatBytes(value?: number | null): string {
  if (value === undefined || value === null) {
    return EMPTY;
  }
  if (!Number.isFinite(value) || value < 0) {
    return EMPTY;
  }
  if (value === 0) {
    return '0 B';
  }
  if (value < 1024) {
    return `${value} B`;
  }
  const units = ['KB', 'MB', 'GB', 'TB'];
  let size = value;
  let unitIndex = -1;
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex += 1;
  }
  const rounded = size >= 100 ? Math.round(size) : Math.round(size * 10) / 10;
  return `${rounded} ${units[unitIndex]}`;
}
