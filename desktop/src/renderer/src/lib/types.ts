/**
 * Domain types derived from the shared ApiClient method return values
 * (FE-001 PHASE D1). We NEVER copy generated DTO interfaces — types
 * are inferred so they cannot drift from the OpenAPI contract.
 */

import type { ApiClient } from '@aistudy/api-client';

type ResultData<T> = T extends (...args: never[]) => Promise<infer R>
  ? R extends { data: infer D }
    ? NonNullable<D>
    : never
  : never;

/** Single LearningSpace, inferred from listLearningSpaces(). */
export type LearningSpace = ResultData<ApiClient['listLearningSpaces']>[number];

/** Single Source, inferred from listSources(). */
export type Source = ResultData<ApiClient['listSources']>[number];

/** Source detail, inferred from getSource(). */
export type SourceDetail = ResultData<ApiClient['getSource']>;

/** Single SourceAsset, inferred from listSourceAssets() (FE-002A). */
export type SourceAsset = ResultData<ApiClient['listSourceAssets']>[number];

/** Single IngestionJob, inferred from listIngestionJobs() (FE-002A). */
export type IngestionJob = ResultData<ApiClient['listIngestionJobs']>[number];

/** Ingestion job detail, inferred from getIngestionJob(). */
export type IngestionJobDetail = ResultData<ApiClient['getIngestionJob']>;

/** Single SourcePage, inferred from listSourcePages() (FE-002A). */
export type SourcePage = ResultData<ApiClient['listSourcePages']>[number];

/** Single ContentBlock, inferred from listContentBlocks() (FE-002A). */
export type ContentBlock = ResultData<ApiClient['listContentBlocks']>[number];

/** Single KnowledgePointSource link (BUSINESS-007 provenance). */
export type KnowledgePointSourceLink =
  ResultData<ApiClient['listKnowledgePointSources']>[number];

/** Single KnowledgeCategory, inferred from listKnowledgeCategories(). */
export type KnowledgeCategory =
  ResultData<ApiClient['listKnowledgeCategories']>[number];

/** Single KnowledgePoint, inferred from listKnowledgePoints(). */
export type KnowledgePoint = ResultData<ApiClient['listKnowledgePoints']>[number];

/** Space detail (same shape as list entries in this contract). */
export type SpaceDetail = ResultData<ApiClient['getLearningSpace']>;

/** Single Exam, inferred from listExams(). */
export type Exam = ResultData<ApiClient['listExams']>[number];

/** Exam detail (question list included), inferred from getExam(). */
export type ExamDetail = ResultData<ApiClient['getExam']>;

/** One started attempt, inferred from createExamAttempt(). */
export type ExamAttempt = ResultData<ApiClient['createExamAttempt']>;

/** Attempt as re-read from the server (question paper + deadlines). */
export type ExamAttemptDetail = ResultData<ApiClient['getExamAttempt']>;

/** Graded exam result. */
export type ExamResult = ResultData<ApiClient['getExamResult']>;

/**
 * One saved answer on an attempt — the only thing that proves an answer
 * exists (C10: it carries `answeredAt`, never the answer content). Derived
 * from the single method that produces it so it cannot drift.
 */
export type ExamAnswerRecord = ResultData<ApiClient['submitExamAnswer']>;

/** Contract `ExamAnswerRequest`: the body the answer endpoint accepts. */
export type ExamAnswerBody = NonNullable<
  Parameters<ApiClient['submitExamAnswer']>[2]
>;

/** Contract `AnswerPayloadView`, reached through its request wrapper. */
export type ExamAnswerPayload = NonNullable<ExamAnswerBody['answer']>;

/**
 * One paper slot of a live attempt. The contract types both ids optional, so
 * they are intersected back to required: save state is keyed by
 * `examQuestionId`, and a slot without an id cannot be rendered at all.
 */
export type ExamQuestionSlot = NonNullable<
  ExamAttemptDetail['questions']
>[number] & { examQuestionId: number; questionId: number };

/** Practice session detail: status, counts and its question paper. */
export type PracticeSessionDetail = ResultData<ApiClient['getPracticeSession']>;

/**
 * Practice score summary. NOTE: only `finishPracticeSession` returns this —
 * the session detail does NOT carry score/answered/correct counts, so a
 * finished session re-read from the server has no score to display. See
 * BACKEND-CONTRACT-REQUESTS.md (A5.3).
 */
export type PracticeSubmitSummary = ResultData<
  ApiClient['finishPracticeSession']
>;

/** Question as stored (options + answer key + provenance ids). */
export type QuestionDetail = ResultData<ApiClient['getQuestion']>;

/** One paged search hit-set, inferred from search(). */
export type SearchPageResult = ResultData<ApiClient['search']>;

/** AI provider settings as the server reports them (never the key). */
export type AiProviderSettings = ResultData<ApiClient['getAiSettings']>;

/** Outcome of the backend-originated provider probe. */
export type AiConnectionTest = ResultData<ApiClient['testAiConnection']>;

/** Paged AI conversation list / message page. */
export type AiConversationPage = ResultData<ApiClient['listConversations']>;
export type AiMessagePage = ResultData<ApiClient['listMessages']>;

/**
 * Provenance link fields that are NOT in the contract yet: the backend does
 * not return `sourceId` / `sourcePageId` on KnowledgePointSourceResponse
 * (BACKEND-CONTRACT-REQUESTS.md A2.1), so the deep link can only be built
 * when they appear. This is the one sanctioned place to look for them —
 * pages must not scatter `as unknown as` over contract fields.
 */
export interface ProvenanceLinkExtras {
  sourceId?: number;
  sourcePageId?: number;
}

export function provenanceExtras(
  link: KnowledgePointSourceLink
): ProvenanceLinkExtras {
  return link as unknown as ProvenanceLinkExtras;
}
