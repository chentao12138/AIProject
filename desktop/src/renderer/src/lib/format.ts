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
/**
 * Question types the backend accepts (`QuestionService.TYPE_*`). The contract
 * types `questionType` as a free-form string, so this list — not a guess —
 * is pinned to the Java constants by `question-type-contract.test.ts`.
 */
export const QUESTION_TYPES = [
  'SINGLE_CHOICE',
  'MULTIPLE_CHOICE',
  'TRUE_FALSE',
  'SHORT_ANSWER',
  'FILL_BLANK',
  'ORDERING',
  'MATCHING',
] as const;

export type QuestionType = (typeof QUESTION_TYPES)[number];

/**
 * The subset the renderer can author and answer. The backend also accepts
 * FILL_BLANK / ORDERING / MATCHING, which have no editor yet — offering them
 * would let users create questions they cannot fill in.
 */
export const AUTHORABLE_QUESTION_TYPES = [
  'SINGLE_CHOICE',
  'MULTIPLE_CHOICE',
  'TRUE_FALSE',
  'SHORT_ANSWER',
] as const satisfies readonly QuestionType[];

export type AuthorableQuestionType =
  (typeof AUTHORABLE_QUESTION_TYPES)[number];

/** Backend question types -> display copy. Unknown values show as-is. */
const QUESTION_TYPE_LABELS: Record<QuestionType, string> = {
  SINGLE_CHOICE: '单选',
  MULTIPLE_CHOICE: '多选',
  TRUE_FALSE: '判断',
  SHORT_ANSWER: '简答',
  FILL_BLANK: '填空',
  ORDERING: '排序',
  MATCHING: '配对',
};

export function questionTypeLabel(type?: string | null): string {
  if (!type) return '—';
  const normalized = type.trim().toUpperCase();
  return QUESTION_TYPE_LABELS[normalized as QuestionType] ?? type;
}

/**
 * Exam attempt statuses the backend can report (`ExamAttemptService.STATUS_*`).
 * Pinned to that Java file by `backend-vocabulary-contract.test.ts`; the
 * attempt page once matched CREATED/PENDING, which never occur, so a fresh
 * attempt skipped its start screen and every answer was rejected with 409.
 */
export const EXAM_ATTEMPT_STATUSES = {
  NOT_STARTED: 'NOT_STARTED',
  IN_PROGRESS: 'IN_PROGRESS',
  SUBMITTED: 'SUBMITTED',
} as const;

export type ExamAttemptStatus =
  (typeof EXAM_ATTEMPT_STATUSES)[keyof typeof EXAM_ATTEMPT_STATUSES];

/**
 * Practice session statuses (`PracticeSessionService.STATUS_*`). The answer
 * page once matched FINISHED/COMPLETED — neither exists — so a submitted
 * session reopened as a blank answer form.
 */
export const PRACTICE_SESSION_STATUSES = {
  CREATED: 'CREATED',
  IN_PROGRESS: 'IN_PROGRESS',
  SUBMITTED: 'SUBMITTED',
} as const;

export type PracticeSessionStatus =
  (typeof PRACTICE_SESSION_STATUSES)[keyof typeof PRACTICE_SESSION_STATUSES];

/**
 * Knowledge origin types (`KnowledgePoint.ORIGIN_TYPE_*`), pinned to the
 * backend by `backend-vocabulary-contract.test.ts`.
 */
export const KNOWLEDGE_ORIGIN_TYPES = {
  USER_CURATED: 'USER_CURATED',
  ADMIN_CURATED: 'ADMIN_CURATED',
  AI_DERIVED: 'AI_DERIVED',
  SOURCE_DERIVED: 'SOURCE_DERIVED',
} as const;

export type KnowledgeOriginType =
  (typeof KNOWLEDGE_ORIGIN_TYPES)[keyof typeof KNOWLEDGE_ORIGIN_TYPES];

const ORIGIN_TYPE_LABELS: Record<KnowledgeOriginType, string> = {
  USER_CURATED: '本人整理',
  ADMIN_CURATED: '管理员整理',
  AI_DERIVED: 'AI 生成',
  SOURCE_DERIVED: '来源提取',
};

export function originLabel(origin?: string | null): string {
  if (!origin) return '—';
  const normalized = origin.trim().toUpperCase();
  return ORIGIN_TYPE_LABELS[normalized as KnowledgeOriginType] ?? origin;
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
