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
/** Backend question types -> display copy. Unknown values show as-is. */
const QUESTION_TYPE_LABELS: Record<string, string> = {
  SINGLE_CHOICE: '单选',
  MULTIPLE_CHOICE: '多选',
  BOOLEAN: '判断',
  TEXT_ANSWER: '主观题',
};

export function questionTypeLabel(type?: string | null): string {
  if (!type) return '—';
  return QUESTION_TYPE_LABELS[type.trim().toUpperCase()] ?? type;
}

/** Backend difficulty levels -> display copy. */
const DIFFICULTY_LABELS: Record<string, string> = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
};

export function difficultyLabel(difficulty?: string | null): string {
  if (!difficulty) return '—';
  return DIFFICULTY_LABELS[difficulty.trim().toUpperCase()] ?? difficulty;
}

/** Search hit types -> display copy. */
const RESULT_TYPE_LABELS: Record<string, string> = {
  SOURCE: '学习来源',
  CONTENT_BLOCK: '正文片段',
  KNOWLEDGE_POINT: '知识点',
  QUESTION: '题目',
  NOTE: '笔记',
};

export function resultTypeLabel(type?: string | null): string {
  if (!type) return '—';
  return RESULT_TYPE_LABELS[type.trim().toUpperCase()] ?? type;
}

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
