/**
 * K1.8 — Knowledge list renders DRAFT / PUBLISHED badges.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { KnowledgePage } from './KnowledgePage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

describe('KnowledgePage', () => {
  it('renders DRAFT and PUBLISHED status badges (K1.8)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [{ id: 10, name: 'Algebra' }],
        response: { status: 200 },
      })),
      listKnowledgePoints: vi.fn(async () => ({
        data: [
          {
            id: 1,
            title: 'Draft point',
            status: 'DRAFT',
            originType: 'USER_CURATED',
          },
          {
            id: 2,
            title: 'Published point',
            status: 'PUBLISHED',
            originType: 'USER_CURATED',
            publishedAt: '2026-09-01T10:00:00Z',
          },
        ],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePage />, {
      apiClient,
      initialEntries: ['/spaces/7/knowledge'],
      routePath: '/spaces/:spaceId/knowledge',
    });

    expect(await screen.findByText('Draft point')).toBeInTheDocument();
    expect(screen.getByText('Published point')).toBeInTheDocument();
    expect(screen.getAllByText('DRAFT')).toHaveLength(1);
    expect(screen.getAllByText('PUBLISHED')).toHaveLength(1);
  });

  it('shows empty state when no points exist', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      listKnowledgePoints: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePage />, {
      apiClient,
      initialEntries: ['/spaces/7/knowledge'],
      routePath: '/spaces/:spaceId/knowledge',
    });

    expect(
      await screen.findByText('No knowledge points yet')
    ).toBeInTheDocument();
  });
});
