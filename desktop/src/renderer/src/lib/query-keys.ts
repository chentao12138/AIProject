/**
 * Query key contract (FE-001 PHASE 9, FE-002A extension).
 *
 * Server data always flows through the TanStack Query cache. Mutations
 * invalidate the exact keys below.
 *
 * FE-002A invalidation relationships:
 * - upload success → invalidate sourceAssets + ingestionJobs (list)
 * - ingestion terminal → invalidate sourcePages + contentBlocks
 * - retry → invalidate that ingestionJob + list
 * Keys never contain File/FormData/JWT/raw content.
 */

/**
 * Ids come from route params and are `number | null` until a URL resolves.
 * Encoding the missing one as `'none'` (instead of `?? 0`) keeps a
 * disabled query's cache entry readable in devtools and stops every page
 * from inventing its own placeholder. Ids are positive integers, so a real
 * id can never collide with `'none'`.
 */
function part(value: number | null): number | 'none' {
  return value ?? 'none';
}

/**
 * Flatten the filters that are actually applied into labeled key slots.
 *
 * Padding optional filters with `undefined` breaks invalidation: TanStack
 * compares every index of the filter key, so `['questions', 7, undefined]`
 * does NOT match a cached `['questions', 7, 'PUBLISHED']`, and creating a
 * question while a filter was active left the list stale. Labeled, sparse
 * keys keep `queryKeys.questions(spaceId)` a true prefix of every filtered
 * variant, which is what mutations invalidate.
 */
function labelled(filters: Record<string, unknown>): unknown[] {
  return Object.entries(filters)
    .filter(([, value]) => value !== undefined && value !== '')
    .flatMap(([name, value]) => [name, value]);
}

export const queryKeys = {
  spaces: ['spaces'] as const,
  space: (spaceId: number | null) => ['space', part(spaceId)] as const,
  sources: (spaceId: number | null) => ['sources', part(spaceId)] as const,
  source: (spaceId: number | null, sourceId: number | null) =>
    ['source', part(spaceId), part(sourceId)] as const,
  sourceAssets: (spaceId: number | null, sourceId: number | null) =>
    ['source-assets', part(spaceId), part(sourceId)] as const,
  sourceAsset: (
    spaceId: number | null,
    sourceId: number | null,
    assetId: number
  ) => ['source-asset', part(spaceId), part(sourceId), assetId] as const,
  ingestionJobs: (spaceId: number | null, sourceId: number | null) =>
    ['ingestion-jobs', part(spaceId), part(sourceId)] as const,
  ingestionJob: (spaceId: number | null, jobId: number | null) =>
    ['ingestion-job', part(spaceId), part(jobId)] as const,
  sourcePages: (spaceId: number | null, sourceId: number | null) =>
    ['source-pages', part(spaceId), part(sourceId)] as const,
  contentBlocks: (
    spaceId: number | null,
    sourceId: number | null,
    pageId?: number
  ) =>
    [
      'content-blocks',
      part(spaceId),
      part(sourceId),
      ...labelled({ page: pageId }),
    ] as const,
  knowledgeCategories: (spaceId: number | null) =>
    ['knowledge-categories', part(spaceId)] as const,
  knowledgePoints: (spaceId: number | null) =>
    ['knowledge-points', part(spaceId)] as const,
  knowledgePoint: (spaceId: number | null, knowledgePointId: number | null) =>
    ['knowledge-point', part(spaceId), part(knowledgePointId)] as const,
  knowledgePointSources: (
    spaceId: number | null,
    knowledgePointId: number | null
  ) =>
    ['knowledge-point-sources', part(spaceId), part(knowledgePointId)] as const,
  // FE-AI-001
  aiSettings: ['ai-settings'] as const,
  aiConversations: (spaceId: number | null) =>
    ['ai-conversations', part(spaceId)] as const,
  aiConversation: (spaceId: number | null, conversationId: number | null) =>
    ['ai-conversation', part(spaceId), part(conversationId)] as const,
  aiMessages: (
    spaceId: number | null,
    conversationId: number | null,
    page?: number
  ) =>
    ['ai-messages', part(spaceId), part(conversationId), ...labelled({ page })] as const,
  questions: (
    spaceId: number | null,
    filters: {
      status?: string;
      questionType?: string;
      knowledgePointId?: number;
      page?: number;
      size?: number;
    } = {}
  ) => ['questions', part(spaceId), ...labelled(filters)] as const,
  question: (spaceId: number | null, questionId: number | null) =>
    ['question', part(spaceId), part(questionId)] as const,
  practiceSessions: (spaceId: number | null) =>
    ['practice-sessions', part(spaceId)] as const,
  practiceSession: (spaceId: number | null, sessionId: number | null) =>
    ['practice-session', part(spaceId), part(sessionId)] as const,
  /**
   * Answers the server already holds for a session. Keyed apart from the
   * session detail because a refreshed page reads this to resume, and submitting
   * an answer must not invalidate the question snapshot.
   */
  practiceAnswers: (spaceId: number | null, sessionId: number | null) =>
    ['practice-answers', part(spaceId), part(sessionId)] as const,
  /**
   * The score summary of a session, read apart from its question snapshot: a
   * finished session is re-opened for its result, not for its questions.
   */
  practiceResult: (spaceId: number | null, sessionId: number | null) =>
    ['practice-result', part(spaceId), part(sessionId)] as const,
  /**
   * Attempt history is per exam (W07), so the exam id belongs in the key.
   */
  examAttempts: (
    spaceId: number | null,
    examId: number | null,
    filters: { status?: string; page?: number; size?: number } = {}
  ) =>
    ['exam-attempts', part(spaceId), part(examId), ...labelled(filters)] as const,
  // G01 — Notes. The list is paged, so the page belongs in the key.
  notes: (
    spaceId: number | null,
    filters: {
      page?: number;
      size?: number;
      keyword?: string;
      archived?: boolean;
    } = {}
  ) => ['notes', part(spaceId), ...labelled(filters)] as const,
  note: (spaceId: number | null, noteId: number | null) =>
    ['note', part(spaceId), part(noteId)] as const,
  noteKnowledgePoints: (spaceId: number | null, noteId: number | null) =>
    ['note-knowledge-points', part(spaceId), part(noteId)] as const,
  noteSourceBlocks: (spaceId: number | null, noteId: number | null) =>
    ['note-source-blocks', part(spaceId), part(noteId)] as const,
  exams: (spaceId: number | null) => ['exams', part(spaceId)] as const,
  exam: (spaceId: number | null, examId: number | null) =>
    ['exam', part(spaceId), part(examId)] as const,
  examAttempt: (spaceId: number | null, attemptId: number | null) =>
    ['exam-attempt', part(spaceId), part(attemptId)] as const,
  examResult: (spaceId: number | null, attemptId: number | null) =>
    ['exam-result', part(spaceId), part(attemptId)] as const,
  /**
   * Search must key on everything that changes the result set. With only
   * `query` in the key, page 2 overwrote page 1's cache entry and the
   * pager showed stale rows.
   */
  search: (
    spaceId: number | null,
    query: string,
    filters: { types?: string; page?: number; size?: number } = {}
  ) => ['search', part(spaceId), query, ...labelled(filters)] as const,
};
