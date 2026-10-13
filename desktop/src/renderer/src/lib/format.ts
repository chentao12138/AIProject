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
 * What the renderer can actually do with each backend question type, given
 * the published contract and nothing else.
 *
 * `authorable` — the create form can produce a question of this type.
 * `publishable` — a question of this type can be published *and* later graded
 * from contract fields, so publishing it is not a lie.
 * `answerable` — `QuestionRenderer` collects an answer for it.
 *
 * ORDERING and MATCHING are false everywhere except `answerable` for ORDERING
 * because the contract has no way to carry a correct order or a pair set.
 * `question-type-contract.test.ts` asserts the two "cannot" claims by proving
 * the fields stay ABSENT from openapi.json; the day the backend publishes
 * `correctOrder` / a pair structure, that guard fails on purpose and this
 * matrix has to be re-decided.
 */
export interface QuestionTypeSupport {
  authorable: boolean;
  publishable: boolean;
  answerable: boolean;
  /** Contract-gap copy shown wherever the type appears. Null when complete. */
  gap: string | null;
}

/**
 * The subset the authoring form offers. FILL_BLANK belongs here: the contract
 * already carries its reference answer (`CreateQuestionRequest.referenceAnswer`)
 * and its answer (`AnswerPayloadView.textAnswer`), so create → answer → grade →
 * review all close. ORDERING / MATCHING do not — see their rows below.
 */
export const AUTHORABLE_QUESTION_TYPES = [
  'SINGLE_CHOICE',
  'MULTIPLE_CHOICE',
  'TRUE_FALSE',
  'SHORT_ANSWER',
  'FILL_BLANK',
] as const satisfies readonly QuestionType[];

export type AuthorableQuestionType =
  (typeof AUTHORABLE_QUESTION_TYPES)[number];

/**
 * Copy for the contract gaps this line cannot close. Each string names the
 * exact contract fields that are missing, so a user (and the audit report)
 * can tell "not built yet" apart from "the contract cannot express it".
 */
export const CONTRACT_GAP_NOTICES = {
  ORDERING_AUTHORING:
    '排序题暂不能在本题库创建：契约的 CreateQuestionRequest 没有任何表达标准顺序的字段（后端 Java 侧的 correctOrder 未进入已发布契约，缺口 C09）。没有标准顺序就无法判分，所以这里不把「能创建」说成「能发布」。',
  ORDERING_ANSWER:
    '排序题作答：你的顺序通过 AnswerPayloadView.selectedOptionKeys（有序数组）表达。契约无法提交标准顺序，后端目前无法判分（缺口 C09）。',
  ORDERING_REVIEW:
    '无法显示标准顺序：QuestionAnswerView 只有 correctOptionKey / correctOptionKeys / correctBoolean / referenceAnswer，没有顺序字段（缺口 C09）。下面展示的是你提交的顺序（来自 AnswerPayloadView.selectedOptionKeys 的数组下标）。',
  MATCHING:
    '配对题暂不能创建、也不能作答：契约里不存在配对结构 —— CreateQuestionRequest 没有 matches、AnswerPayloadView 没有配对字段、QuestionAnswerView 没有 pairs。当前只读展示题干与选项。',
  QUESTION_PAGING:
    '列表没有分页：GET /questions 只接受 status / questionType / knowledgePointId，没有 page / size（缺口 C-Paging）。前端不会拉全量自己做假分页。',
  QUESTION_UPDATE:
    '题目不能编辑也不能删除或归档：契约在这里只有 GET 与 POST（含 /publish），没有 PUT /questions/{questionId}，也没有 DELETE / archive（缺口 G04 / G09）。用「新建」代替编辑会复制出一道新题，所以不提供。',
} as const;

/** Per-type support matrix — the single source for every gate in this line. */
export const QUESTION_TYPE_SUPPORT: Record<QuestionType, QuestionTypeSupport> =
  {
    SINGLE_CHOICE: {
      authorable: true,
      publishable: true,
      answerable: true,
      gap: null,
    },
    MULTIPLE_CHOICE: {
      authorable: true,
      publishable: true,
      answerable: true,
      gap: null,
    },
    TRUE_FALSE: {
      authorable: true,
      publishable: true,
      answerable: true,
      gap: null,
    },
    SHORT_ANSWER: {
      authorable: true,
      publishable: true,
      answerable: true,
      gap: null,
    },
    FILL_BLANK: {
      authorable: true,
      publishable: true,
      answerable: true,
      gap: null,
    },
    ORDERING: {
      authorable: false,
      publishable: false,
      answerable: true,
      gap: CONTRACT_GAP_NOTICES.ORDERING_AUTHORING,
    },
    MATCHING: {
      authorable: false,
      publishable: false,
      answerable: false,
      gap: CONTRACT_GAP_NOTICES.MATCHING,
    },
  };

/**
 * Support for a `questionType` string. Unknown / free-form values return null:
 * the contract types the field as a plain string, and a type this app has
 * never seen must keep rendering as its options instead of being gated by a
 * guess (see `QuestionRenderer`).
 */
export function questionTypeSupport(
  type?: string | null
): QuestionTypeSupport | null {
  if (!type) return null;
  return QUESTION_TYPE_SUPPORT[type.trim().toUpperCase() as QuestionType] ?? null;
}

/** True only for a type the create form may offer. Unknown types: false. */
export function isAuthorableQuestionType(type?: string | null): boolean {
  return questionTypeSupport(type)?.authorable === true;
}

/** True only when publishing this type yields a gradeable question. */
export function isPublishableQuestionType(type?: string | null): boolean {
  return questionTypeSupport(type)?.publishable === true;
}

/** The contract-gap copy for a type, or null when there is nothing to flag. */
export function questionTypeGap(type?: string | null): string | null {
  return questionTypeSupport(type)?.gap ?? null;
}

/**
 * Copy for the display of a type the contract cannot close, per surface:
 * `gap` is the authoring-side sentence (what the create form must refuse and
 * why), `reviewGap` is what a reader of a stored question is owed. They differ
 * because a question can already exist (imported, AI-derived) while the form
 * still cannot make one.
 */
export const QUESTION_TYPE_DISPLAY_GAP: Partial<
  Record<QuestionType, { answer: string; review: string }>
> = {
  ORDERING: {
    answer: CONTRACT_GAP_NOTICES.ORDERING_ANSWER,
    review: CONTRACT_GAP_NOTICES.ORDERING_REVIEW,
  },
  MATCHING: {
    answer: CONTRACT_GAP_NOTICES.MATCHING,
    review: CONTRACT_GAP_NOTICES.MATCHING,
  },
};

/**
 * Length caps copied out of openapi.json — `maxLength` on a string,
 * `maxItems` on an array. Never invent a value here: the guard test compares
 * every entry against the contract and fails on drift. `stem` is deliberately
 * absent because the contract declares only `minLength: 1` for it, so a UI cap
 * would be a rule the server does not have.
 */
export const QUESTION_FIELD_LIMITS = {
  questionType: 32,
  explanation: 4000,
  difficulty: 32,
  optionKey: 16,
  optionContent: 500,
  correctOptionKey: 16,
  /** `maxItems` of CreateQuestionRequest.correctOptionKeys. */
  correctOptionKeys: 16,
  referenceAnswer: 4000,
  /** `maxItems` of AnswerPayloadView.selectedOptionKeys. */
  selectedOptionKeys: 16,
  textAnswer: 4000,
} as const;

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
