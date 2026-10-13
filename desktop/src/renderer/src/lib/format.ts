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
 * ORDERING and MATCHING became authorable when the published contract grew
 * `correctOrder` / `matches` (create) and `orderingAnswer` / `matchingAnswer`
 * (answer). What is still one-way is the read side — `QuestionAnswerView` has
 * no order or pair field — which is why those two keep a review-only notice in
 * `QUESTION_TYPE_DISPLAY_GAP`. `question-type-contract.test.ts` asserts the
 * matrix against the contract fields themselves, so removing either side of the
 * wire format fails the guard instead of silently closing the loop.
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
  'ORDERING',
  'MATCHING',
] as const satisfies readonly QuestionType[];

export type AuthorableQuestionType =
  (typeof AUTHORABLE_QUESTION_TYPES)[number];

/**
 * Copy for the contract gaps this line cannot close. Each string names the
 * exact contract fields that are missing, so a user (and the audit report)
 * can tell "not built yet" apart from "the contract cannot express it".
 */
export const CONTRACT_GAP_NOTICES = {
  ORDERING_REVIEW:
    '无法回显标准顺序：契约的读侧 QuestionAnswerView 只有 correctOptionKey / correctOptionKeys / correctBoolean / referenceAnswer，没有 correctOrder，所以题库详情看不到创建时设的顺序（缺口 C09b）。批改结果里的 correctAnswer 仍会给出服务端判分用的顺序。',
  MATCHING_REVIEW:
    '无法回显标准配对：QuestionAnswerView 不携带 matchesJson，创建时设定的配对读不回来（缺口 C09b）。批改结果里的 correctAnswer 仍会给出服务端的配对。',
  QUESTION_UPDATE_FIELDS:
    '编辑只能改题干 / 解析 / 难度：契约的 UpdateQuestionRequest 只有 stem、explanation、difficulty 三个字段，选项与正确答案无法修改。要改答案只能新建一道题。',
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
      authorable: true,
      publishable: true,
      answerable: true,
      gap: null,
    },
    MATCHING: {
      authorable: true,
      publishable: true,
      answerable: true,
      gap: null,
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
 * Copy for the display of a type the contract cannot close, per surface.
 * Answering is complete for every type now, so only the review of a *stored*
 * question still owes the user an explanation: `QuestionAnswerView` has no
 * `correctOrder` / `matchesJson`, so what the author set cannot be read back.
 */
export const QUESTION_TYPE_DISPLAY_GAP: Partial<
  Record<QuestionType, { review: string }>
> = {
  ORDERING: { review: CONTRACT_GAP_NOTICES.ORDERING_REVIEW },
  MATCHING: { review: CONTRACT_GAP_NOTICES.MATCHING_REVIEW },
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
  /** `maxItems` of CreateQuestionRequest.correctOrder / AnswerPayloadView.orderingAnswer. */
  correctOrder: 100,
  orderingAnswer: 100,
  /** `maxLength` of CreateQuestionRequest.matches (a JSON pair array) and of the answer. */
  matches: 4000,
  matchingAnswer: 10000,
} as const;

/**
 * `size` default of `GET /questions` (openapi.json declares page 0 / size 20).
 * The contract guard compares it, so widening the server default has to widen
 * this deliberately instead of leaving the list page narrower than the server.
 */
export const QUESTIONS_PAGE_SIZE = 20;

/**
 * Practice session size bounds, copied from `CreatePracticeSessionRequest.count` * (`minimum`/`maximum`) in the generated contract. The contract guard compares
 * these numbers against openapi.json, so a deployment that widens the range
 * fails the guard instead of leaving the form silently tighter than the server.
 */
export const PRACTICE_COUNT_LIMITS = { min: 1, max: 100 } as const;

/**
 * Exam creation bounds, copied out of the generated contract
 * (`CreateExamRequest` and `ExamQuestionInput`). `question-type-contract.test.ts`
 * compares every entry against openapi.json, because a form that is tighter
 * than the server denies legitimate input and a form that is looser only fails
 * as a 400 after the user pressed 创建.
 */
export const EXAM_FORM_LIMITS = {
  titleMaxLength: 255,
  descriptionMaxLength: 1000,
  timeLimitMinMinutes: 1,
  maxQuestions: 100,
  questionScoreMin: 1,
} as const;

/**
 * Notes form bounds (G01). `titleMaxLength` / `contentMaxLength` are the
 * contract's `maxLength` on `NoteCreateRequest`; `contentFormats` is NOT in the
 * contract (the field is a plain string) — it is the server's own
 * `NoteService.CONTENT_FORMATS`, and anything else comes back as a 400.
 */
export const NOTE_FORM_LIMITS = {
  titleMaxLength: 255,
  contentMaxLength: 16000,
  contentFormats: ['MARKDOWN', 'PLAIN_TEXT'] as const,
  defaultContentFormat: 'PLAIN_TEXT',
  /** `size` default declared on GET /notes. */
  pageSize: 20,
} as const;

/**
 * Field ceilings for the space / knowledge / source creation forms, copied from
 * the generated contract's `maxLength` values. The contract guard compares each
 * entry against openapi.json; the forms pass them as `maxLength` so an
 * over-long name is stopped while typing instead of coming back as a 400.
 */
export const SPACE_FORM_LIMITS = {
  nameMaxLength: 128,
  descriptionMaxLength: 512,
} as const;

export const KNOWLEDGE_FORM_LIMITS = {
  categoryNameMaxLength: 128,
  categoryDescriptionMaxLength: 512,
  pointTitleMaxLength: 255,
  pointSummaryMaxLength: 1000,
  pointDifficultyMaxLength: 32,
} as const;

export const SOURCE_FORM_LIMITS = {
  titleMaxLength: 255,
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
