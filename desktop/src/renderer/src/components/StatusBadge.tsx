interface StatusBadgeProps {
  status?: string;
}

/**
 * Status badge for backend status strings (DRAFT/PUBLISHED/ACTIVE/...).
 *
 * EXACT status matching only (FE-001.5 PRE-COMMIT REVIEW FIX-01):
 * substring matching is banned because it misclassifies lookalikes —
 * INACTIVE must never render as active, UNPUBLISHED never as published.
 * The frontend invents NO new backend status enum: anything outside the
 * three known statuses renders as a neutral unknown badge.
 */
const KNOWN_STATUS_VARIANTS: Record<string, 'published' | 'draft' | 'active'> = {
  DRAFT: 'draft',
  PUBLISHED: 'published',
  ACTIVE: 'active',
};

export function StatusBadge({ status }: StatusBadgeProps) {
  if (!status) {
    return <span className="badge badge--unknown">—</span>;
  }
  const normalized = status.trim().toUpperCase();
  const variant = KNOWN_STATUS_VARIANTS[normalized] ?? 'unknown';
  return (
    <span className={`badge badge--${variant}`} title={status}>
      {status}
    </span>
  );
}
