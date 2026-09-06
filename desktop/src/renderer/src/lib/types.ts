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

/** Single KnowledgeCategory, inferred from listKnowledgeCategories(). */
export type KnowledgeCategory =
  ResultData<ApiClient['listKnowledgeCategories']>[number];

/** Single KnowledgePoint, inferred from listKnowledgePoints(). */
export type KnowledgePoint = ResultData<ApiClient['listKnowledgePoints']>[number];

/** Space detail (same shape as list entries in this contract). */
export type SpaceDetail = ResultData<ApiClient['getLearningSpace']>;
