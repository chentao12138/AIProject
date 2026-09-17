/**
 * SearchPage — query input and result rendering.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SearchPage } from './SearchPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

describe('SearchPage', () => {
  it('renders the search form when no query is entered', async () => {
    renderWithProviders(<SearchPage />, {
      apiClient: mockApiClient({
        search: vi.fn(async () => ({
          data: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
          response: { status: 200 },
        })),
      }),
      initialEntries: ['/spaces/7/search'],
      routePath: '/spaces/:spaceId/search',
    });

    expect(await screen.findByLabelText('Query')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Search' })).toBeInTheDocument();
  });

  it('renders search results and deep-links to entities', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SearchPage />, {
      apiClient: mockApiClient({
        search: vi.fn(async () => ({
          data: {
            content: [
              { type: 'SOURCE', sourceId: 1, title: 'Sample Source', snippet: '...' },
              { type: 'KNOWLEDGE_POINT', knowledgePointId: 10, title: 'Bayes theorem', snippet: '...' },
            ],
            page: 0,
            size: 20,
            totalElements: 2,
            totalPages: 1,
          },
          response: { status: 200 },
        })),
      }),
      initialEntries: ['/spaces/7/search'],
      routePath: '/spaces/:spaceId/search',
    });

    await user.type(await screen.findByLabelText('Query'), 'bayes');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText('Sample Source')).toBeInTheDocument();
    expect(screen.getByText('Bayes theorem')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Sample Source/ }).getAttribute('href')).toBe('/spaces/7/sources/1');
    expect(screen.getByRole('link', { name: /Bayes theorem/ }).getAttribute('href')).toBe('/spaces/7/knowledge/10');
  });
});
