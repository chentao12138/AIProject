/**
 * K1.7 — Source page: Desktop createSource payload must carry
 * sourceType = DESKTOP_UPLOAD (no admin sourceType dropdown).
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SourcesPage } from './SourcesPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

describe('SourcesPage', () => {
  it('submits createSource with sourceType DESKTOP_UPLOAD (K1.7)', async () => {
    const user = userEvent.setup();
    const listSources = vi.fn(async () => ({
      data: [],
      response: { status: 200 },
    }));
    const createSource = vi.fn(async () => ({
      data: {
        id: 1,
        title: 'Notes',
        sourceType: 'DESKTOP_UPLOAD',
        status: 'PROCESSING',
      },
      response: { status: 201 },
    }));

    const apiClient = mockApiClient({
      listSources,
      createSource,
    });

    renderWithProviders(<SourcesPage />, {
      apiClient,
      initialEntries: ['/spaces/7/sources'],
      routePath: '/spaces/:spaceId/sources',
    });

    await user.click(
      await screen.findByRole('button', { name: 'Create Source' })
    );
    await user.type(await screen.findByLabelText('Title'), 'Chapter notes');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => {
      expect(createSource).toHaveBeenCalledWith(7, {
        title: 'Chapter notes',
        sourceType: 'DESKTOP_UPLOAD',
      });
    });
  });

  it('does not expose an admin sourceType dropdown to the user', async () => {
    const apiClient = mockApiClient({
      listSources: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SourcesPage />, {
      apiClient,
      initialEntries: ['/spaces/7/sources'],
      routePath: '/spaces/:spaceId/sources',
    });

    await userEvent.setup().click(
      await screen.findByRole('button', { name: 'Create Source' })
    );

    // The dialog must NOT offer a source type picker.
    expect(screen.queryByLabelText(/source type/i)).not.toBeInTheDocument();
    expect(
      screen.getByText(/Source type is fixed to DESKTOP_UPLOAD/)
    ).toBeInTheDocument();
  });

  it('renders an empty state when there are no sources (PHASE 11)', async () => {
    const apiClient = mockApiClient({
      listSources: vi.fn(async () => ({ data: [], response: { status: 200 } })),
    });

    renderWithProviders(<SourcesPage />, {
      apiClient,
      initialEntries: ['/spaces/7/sources'],
      routePath: '/spaces/:spaceId/sources',
    });

    expect(await screen.findByText('No sources yet')).toBeInTheDocument();
  });

  it('renders source rows with safe optional fields (PHASE 11)', async () => {
    const apiClient = mockApiClient({
      listSources: vi.fn(async () => ({
        data: [
          {
            id: 1,
            title: 'Chapter 3 notes',
            sourceType: 'DESKTOP_UPLOAD',
            status: 'REGISTERED',
            createdAt: '2026-09-01T08:00:00Z',
          },
          {
            id: 2,
            title: undefined,
            sourceType: undefined,
            status: undefined,
            createdAt: undefined,
          },
        ],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SourcesPage />, {
      apiClient,
      initialEntries: ['/spaces/7/sources'],
      routePath: '/spaces/:spaceId/sources',
    });

    expect(await screen.findByText('Chapter 3 notes')).toBeInTheDocument();
    expect(screen.getByText('DESKTOP_UPLOAD')).toBeInTheDocument();
    expect(screen.getByText('REGISTERED')).toBeInTheDocument();
    // second row: all optionals missing -> dash fallbacks, no #undefined
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(3);
    expect(document.body.textContent).not.toMatch(/#undefined|#null|undefined/);
  });

  it('links valid sources into the workbench (FE-002A PHASE 8)', async () => {
    const apiClient = mockApiClient({
      listSources: vi.fn(async () => ({
        data: [
          { id: 5, title: 'Notes', sourceType: 'DESKTOP_UPLOAD', status: 'ACTIVE' },
          { id: undefined, title: 'Ghost' },
        ],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SourcesPage />, {
      apiClient,
      initialEntries: ['/spaces/7/sources'],
      routePath: '/spaces/:spaceId/sources',
    });

    const openLink = await screen.findByRole('link', { name: 'Open' });
    expect(openLink).toHaveAttribute('href', '/spaces/7/sources/5');
    // Ghost row has no navigable id — no second Open link.
    expect(screen.getAllByRole('link', { name: 'Open' })).toHaveLength(1);
    // Old FE-001 placeholder note is gone.
    expect(
      screen.queryByText(/File upload will become available/)
    ).not.toBeInTheDocument();
  });

  it('rejects an invalid space id with zero API calls (PHASE 11)', async () => {
    const listSources = vi.fn(async () => ({ data: [], response: { status: 200 } }));
    const apiClient = mockApiClient({ listSources });

    renderWithProviders(<SourcesPage />, {
      apiClient,
      initialEntries: ['/spaces/not-a-number/sources'],
      routePath: '/spaces/:spaceId/sources',
    });

    expect(
      await screen.findByText('资源不存在或当前不可访问。')
    ).toBeInTheDocument();
    expect(listSources).not.toHaveBeenCalled();
  });

  it('shows a create error inside the dialog (PHASE 11)', async () => {
    const user = userEvent.setup();
    const createSource = vi.fn(async () => ({
      data: undefined,
      error: { message: 'conflict' },
      response: { status: 409 },
    }));
    const apiClient = mockApiClient({
      listSources: vi.fn(async () => ({ data: [], response: { status: 200 } })),
      createSource,
    });

    renderWithProviders(<SourcesPage />, {
      apiClient,
      initialEntries: ['/spaces/7/sources'],
      routePath: '/spaces/:spaceId/sources',
    });

    await user.click(
      await screen.findByRole('button', { name: 'Create Source' })
    );
    await user.type(await screen.findByLabelText('Title'), 'Notes');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('请求失败，请稍后重试。');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('shows a pending state on create (PHASE 11)', async () => {
    const user = userEvent.setup();
    let resolveCreate: (value: unknown) => void = () => undefined;
    const createSource = vi.fn(
      () =>
        new Promise((resolve) => {
          resolveCreate = resolve;
        })
    );
    const apiClient = mockApiClient({
      listSources: vi.fn(async () => ({ data: [], response: { status: 200 } })),
      createSource,
    });

    renderWithProviders(<SourcesPage />, {
      apiClient,
      initialEntries: ['/spaces/7/sources'],
      routePath: '/spaces/:spaceId/sources',
    });

    await user.click(
      await screen.findByRole('button', { name: 'Create Source' })
    );
    await user.type(await screen.findByLabelText('Title'), 'Notes');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    const submit = await screen.findByRole('button', { name: 'Creating…' });
    expect(submit).toBeDisabled();
    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    resolveCreate({
      data: { id: 1, title: 'Notes', sourceType: 'DESKTOP_UPLOAD', status: 'REGISTERED' },
      response: { status: 201 },
    });
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });

  it('editing a field clears the stale create error (PHASE 11)', async () => {
    const user = userEvent.setup();
    const createSource = vi
      .fn()
      .mockResolvedValueOnce({
        data: undefined,
        error: { message: 'boom' },
        response: { status: 500 },
      })
      .mockResolvedValue({
        data: { id: 1, title: 'Notes', sourceType: 'DESKTOP_UPLOAD', status: 'REGISTERED' },
        response: { status: 201 },
      });
    const apiClient = mockApiClient({
      listSources: vi.fn(async () => ({ data: [], response: { status: 200 } })),
      createSource,
    });

    renderWithProviders(<SourcesPage />, {
      apiClient,
      initialEntries: ['/spaces/7/sources'],
      routePath: '/spaces/:spaceId/sources',
    });

    await user.click(
      await screen.findByRole('button', { name: 'Create Source' })
    );
    const title = await screen.findByLabelText('Title');
    await user.type(title, 'Notes');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();

    await user.type(title, '!');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
