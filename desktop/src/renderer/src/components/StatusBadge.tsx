interface StatusBadgeProps {
  status?: string;
}

/**
 * Status badge for backend status strings (DRAFT/PUBLISHED/ACTIVE/...).
 * Unknown values render neutrally instead of crashing.
 */
export function StatusBadge({ status }: StatusBadgeProps) {
  if (!status) {
    return <span className="badge badge--unknown">—</span>;
  }
  const normalized = status.toLowerCase();
  const variant = normalized.includes('published')
    ? 'published'
    : normalized.includes('draft')
      ? 'draft'
      : normalized.includes('active')
        ? 'active'
        : 'unknown';
  return (
    <span className={`badge badge--${variant}`} title={status}>
      {status}
    </span>
  );
}
