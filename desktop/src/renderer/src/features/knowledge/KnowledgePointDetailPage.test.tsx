/**
 * K1.9–K1.11 — KnowledgePoint detail + publish flow.
 *
 * - DRAFT detail shows a Publish action
 * - PUBLISHED detail shows NO active publish CTA
 * - publish mutation calls publishKnowledgePoint(spaceId, pointId)
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { KnowledgePointDetailPage } from './KnowledgePointDetailPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

describe('KnowledgePointDetailPage', () => {
  it('shows Publish for a DRAFT point (K1.9)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 5,
          spaceId: 3,
          title: 'Draft note',
          content: 'body',
          status: 'DRAFT',
          originType: 'USER_CURATED',
        },
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByText('Draft note')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Publish' })).toBeInTheDocument();
  });

  it('shows no active publish CTA for a PUBLISHED point (K1.10)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 6,
          spaceId: 3,
          title: 'Published note',
          content: 'body',
          status: 'PUBLISHED',
          originType: 'USER_CURATED',
          publishedAt: '2026-09-02T08:00:00Z',
        },
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/6'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByText('Published note')).toBeInTheDocument();
    // badge appears in the page header and in the Status row
    expect(screen.getAllByText('PUBLISHED').length).toBeGreaterThan(0);
    expect(
      screen.queryByRole('button', { name: 'Publish' })
    ).not.toBeInTheDocument();
  });

  it('publish mutation calls publishKnowledgePoint(spaceId, pointId) (K1.11)', async () => {
    const user = userEvent.setup();
    const publishKnowledgePoint = vi.fn(async () => ({
      data: {
        id: 5,
        spaceId: 3,
        title: 'Draft note',
        content: 'body',
        status: 'PUBLISHED',
        originType: 'USER_CURATED',
        publishedAt: '2026-09-02T08:00:00Z',
      },
      response: { status: 200 },
    }));

    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 5,
          spaceId: 3,
          title: 'Draft note',
          content: 'body',
          status: 'DRAFT',
          originType: 'USER_CURATED',
        },
        response: { status: 200 },
      })),
      publishKnowledgePoint,
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    await user.click(await screen.findByRole('button', { name: 'Publish' }));

    await waitFor(() => {
      expect(publishKnowledgePoint).toHaveBeenCalledWith(3, 5);
    });
  });

  it('shows — when categoryId is null (final polish)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 8,
          spaceId: 3,
          title: 'No category',
          content: 'body',
          status: 'DRAFT',
          originType: 'USER_CURATED',
          categoryId: null,
        },
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/8'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByText('No category')).toBeInTheDocument();
    const row = screen.getByText('Category').closest('div');
    expect(row).toBeTruthy();
    expect(row!.textContent).toBe('Category—');
    expect(screen.queryByText(/#null|#undefined|#NaN/)).not.toBeInTheDocument();
  });

  it('shows the resolved category name when the id matches (final polish)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [{ id: 7, name: 'Algebra' }],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 9,
          spaceId: 3,
          title: 'Categorized',
          content: 'body',
          status: 'DRAFT',
          originType: 'USER_CURATED',
          categoryId: 7,
        },
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/9'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByText('Categorized')).toBeInTheDocument();
    expect(await screen.findByText('Algebra')).toBeInTheDocument();
  });

  it('falls back to #<id> when the category id is missing from the list (final polish)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 10,
          spaceId: 3,
          title: 'Orphan category',
          content: 'body',
          status: 'DRAFT',
          originType: 'USER_CURATED',
          categoryId: 7,
        },
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/10'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByText('Orphan category')).toBeInTheDocument();
    expect(await screen.findByText('#7')).toBeInTheDocument();
  });
});
