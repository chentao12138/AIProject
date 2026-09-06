/**
 * Query key contract (FE-001 PHASE 9).
 *
 * Server data always flows through the TanStack Query cache. Mutations
 * invalidate the exact keys below.
 */

export const queryKeys = {
  spaces: ['spaces'] as const,
  space: (spaceId: number) => ['space', spaceId] as const,
  sources: (spaceId: number) => ['sources', spaceId] as const,
  knowledgeCategories: (spaceId: number) =>
    ['knowledge-categories', spaceId] as const,
  knowledgePoints: (spaceId: number) => ['knowledge-points', spaceId] as const,
  knowledgePoint: (spaceId: number, knowledgePointId: number) =>
    ['knowledge-point', spaceId, knowledgePointId] as const,
};
