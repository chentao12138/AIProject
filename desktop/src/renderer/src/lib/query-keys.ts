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

export const queryKeys = {
  spaces: ['spaces'] as const,
  space: (spaceId: number) => ['space', spaceId] as const,
  sources: (spaceId: number) => ['sources', spaceId] as const,
  source: (spaceId: number, sourceId: number) =>
    ['source', spaceId, sourceId] as const,
  sourceAssets: (spaceId: number, sourceId: number) =>
    ['source-assets', spaceId, sourceId] as const,
  sourceAsset: (spaceId: number, sourceId: number, assetId: number) =>
    ['source-asset', spaceId, sourceId, assetId] as const,
  ingestionJobs: (spaceId: number, sourceId: number) =>
    ['ingestion-jobs', spaceId, sourceId] as const,
  ingestionJob: (spaceId: number, jobId: number) =>
    ['ingestion-job', spaceId, jobId] as const,
  sourcePages: (spaceId: number, sourceId: number) =>
    ['source-pages', spaceId, sourceId] as const,
  contentBlocks: (spaceId: number, sourceId: number, pageId?: number) =>
    pageId === undefined
      ? (['content-blocks', spaceId, sourceId] as const)
      : (['content-blocks', spaceId, sourceId, pageId] as const),
  knowledgeCategories: (spaceId: number) =>
    ['knowledge-categories', spaceId] as const,
  knowledgePoints: (spaceId: number) => ['knowledge-points', spaceId] as const,
  knowledgePoint: (spaceId: number, knowledgePointId: number) =>
    ['knowledge-point', spaceId, knowledgePointId] as const,
  knowledgePointSources: (spaceId: number, knowledgePointId: number) =>
    ['knowledge-point-sources', spaceId, knowledgePointId] as const,
};
