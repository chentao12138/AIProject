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
