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

/** Display copy only — `title` still carries the raw backend value. */
const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
  ACTIVE: '启用',
  INACTIVE: '停用',
  CREATED: '待开始',
  NOT_STARTED: '未开始',
  IN_PROGRESS: '进行中',
  SUBMITTED: '已交卷',
  FINISHED: '已完成',
  PARTIAL_FAILED: '部分失败',
  FAILED: '失败',
};

export function StatusBadge({ status }: StatusBadgeProps) {
  if (!status) {
    return <span className="badge badge--unknown">—</span>;
  }
  const normalized = status.trim().toUpperCase();
  const variant = KNOWN_STATUS_VARIANTS[normalized] ?? 'unknown';
  return (
    <span className={`badge badge--${variant}`} title={status}>
      {STATUS_LABELS[normalized] ?? status}
    </span>
  );
}
