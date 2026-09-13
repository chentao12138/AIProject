/**
 * SpaceScopeGuard tests (FE-001 pre-commit review fix #3).
 *
 * The guard gates every /spaces/:spaceId child route: an invalid id or
 * a failed getLearningSpace (404 / unowned / error) renders the
 * inaccessible UI and child resource APIs are never called.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import type { ApiClient } from '@aistudy/api-client';
import { SpaceScopeGuard } from './SpaceScopeGuard';
import { mockApiClient, renderWithProviders } from '../test/test-utils';

function renderGuard(apiClient: ApiClient, initialEntry: string) {
  renderWithProviders(
    <Routes>
      <Route path="/spaces/:spaceId" element={<SpaceScopeGuard />}>
        <Route index element={<div>GUARD_CHILD_VISIBLE</div>} />
      </Route>
    </Routes>,
    { apiClient, initialEntries: [initialEntry] }
  );
}

describe('SpaceScopeGuard', () => {
  it('404 on getLearningSpace -> inaccessible UI, child APIs never called (review #3)', async () => {
    const apiClient = mockApiClient({
      getLearningSpace: vi.fn(async () => ({
        data: undefined,
        error: { message: 'not found' },
        response: { status: 404 },
      })),
    });

    renderGuard(apiClient, '/spaces/3');

    expect(
      await screen.findByText('资源不存在或当前不可访问。')
    ).toBeInTheDocument();
    expect(screen.queryByText('GUARD_CHILD_VISIBLE')).not.toBeInTheDocument();
    expect(apiClient.listSources).not.toHaveBeenCalled();
    expect(apiClient.listKnowledgePoints).not.toHaveBeenCalled();
    expect(apiClient.listKnowledgeCategories).not.toHaveBeenCalled();
  });

  it('invalid space id -> inaccessible UI, no API call at all (review #3)', async () => {
    const apiClient = mockApiClient();

    renderGuard(apiClient, '/spaces/abc');

    expect(
      await screen.findByText('资源不存在或当前不可访问。')
    ).toBeInTheDocument();
    expect(apiClient.getLearningSpace).not.toHaveBeenCalled();
    expect(apiClient.listSources).not.toHaveBeenCalled();
  });

  it('success -> renders child routes (review #3)', async () => {
    const apiClient = mockApiClient({
      getLearningSpace: vi.fn(async () => ({
        data: { id: 3, name: 'Math', status: 'ACTIVE' },
        response: { status: 200 },
      })),
    });

    renderGuard(apiClient, '/spaces/3');

    expect(await screen.findByText('GUARD_CHILD_VISIBLE')).toBeInTheDocument();
  });

  it('shows a loading state while the space query is pending (PHASE 23)', async () => {
    let resolveGet: (value: unknown) => void = () => undefined;
    const apiClient = mockApiClient({
      getLearningSpace: vi.fn(
        () =>
          new Promise((resolve) => {
            resolveGet = resolve;
          })
      ),
    });

    renderGuard(apiClient, '/spaces/3');

    expect(await screen.findByText('加载空间…')).toBeInTheDocument();
    expect(screen.queryByText('GUARD_CHILD_VISIBLE')).not.toBeInTheDocument();

    resolveGet({ data: { id: 3, name: 'Math', status: 'ACTIVE' }, response: { status: 200 } });
    expect(await screen.findByText('GUARD_CHILD_VISIBLE')).toBeInTheDocument();
  });

  it('renders a network error state with a retry action (PHASE 23)', async () => {
    const user = userEvent.setup();
    const getLearningSpace = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('fetch failed'))
      .mockResolvedValue({
        data: { id: 3, name: 'Math', status: 'ACTIVE' },
        response: { status: 200 },
      });
    const apiClient = mockApiClient({ getLearningSpace });

    renderGuard(apiClient, '/spaces/3');

    expect(
      await screen.findByText('无法连接到后端服务。请确认服务已启动后重试。')
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByText('GUARD_CHILD_VISIBLE')).toBeInTheDocument();
  });

  it.each(['/spaces/0', '/spaces/-3', '/spaces/3.5', '/spaces/0x10'])(
    'rejects id %s before any API call (PHASE 23)',
    async (entry) => {
      const apiClient = mockApiClient();
      renderGuard(apiClient, entry);
      expect(
        await screen.findByText('资源不存在或当前不可访问。')
      ).toBeInTheDocument();
      expect(apiClient.getLearningSpace).not.toHaveBeenCalled();
    }
  );
});
