/**
 * K1.4–K1.6 + K1.12 — Spaces page behavior tests.
 *
 * - empty state
 * - displays returned spaces
 * - create LearningSpace: submit -> ApiClient method called -> success
 *   state / invalidate behavior
 * - 401 -> auth/session error UI
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SpacesPage } from './SpacesPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

describe('SpacesPage', () => {
  it('shows empty state when there are no spaces (K1.4)', async () => {
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    expect(
      await screen.findByText('No learning spaces yet')
    ).toBeInTheDocument();
    // header button + empty-state CTA share the same label
    expect(
      screen.getAllByRole('button', { name: 'Create Learning Space' }).length
    ).toBeGreaterThanOrEqual(1);
  });

  it('displays returned spaces (K1.5)', async () => {
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: [
          { id: 1, name: 'Math 101', description: 'Basics', status: 'ACTIVE' },
          { id: 2, name: 'Physics', status: 'ACTIVE' },
        ],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    expect(await screen.findByText('Math 101')).toBeInTheDocument();
    expect(screen.getByText('Physics')).toBeInTheDocument();
    expect(screen.getByText('Basics')).toBeInTheDocument();
  });

  it('create LearningSpace calls the ApiClient and invalidates the list (K1.6)', async () => {
    const user = userEvent.setup();
    const listLearningSpaces = vi
      .fn()
      .mockResolvedValueOnce({
        data: [],
        response: { status: 200 },
      })
      .mockResolvedValue({
        data: [
          { id: 1, name: 'Math 101', description: 'Basics', status: 'ACTIVE' },
        ],
        response: { status: 200 },
      });
    const createLearningSpace = vi.fn(async () => ({
      data: { id: 1, name: 'Math 101', status: 'ACTIVE' },
      response: { status: 201 },
    }));

    const apiClient = mockApiClient({
      listLearningSpaces,
      createLearningSpace,
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    const createButtons = await screen.findAllByRole('button', {
      name: 'Create Learning Space',
    });
    await user.click(createButtons[0]);

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toBeInTheDocument();

    await user.type(screen.getByLabelText('Name'), 'Math 101');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => {
      expect(createLearningSpace).toHaveBeenCalledWith({
        name: 'Math 101',
        description: undefined,
      });
    });
    // invalidate -> list is refetched
    await waitFor(() => {
      expect(listLearningSpaces).toHaveBeenCalledTimes(2);
    });
  });

  it('shows auth/session error UI on 401 (K1.12)', async () => {
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: undefined,
        error: { message: 'unauthorized' },
        response: { status: 401 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    expect(
      await screen.findByText(/当前开发会话未认证或令牌已失效/)
    ).toBeInTheDocument();
  });

  it('renders space cards as real links into the space (review #4)', async () => {
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: [{ id: 1, name: 'Math 101', status: 'ACTIVE' }],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    const link = await screen.findByRole('link', { name: /Math 101/ });
    expect(link.tagName).toBe('A');
    expect(link).toHaveAttribute('href', '/spaces/1/sources');
  });

  it('space card links are keyboard-focusable via Tab (review #4)', async () => {
    const user = userEvent.setup();
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: [{ id: 1, name: 'Math 101', status: 'ACTIVE' }],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    const link = await screen.findByRole('link', { name: /Math 101/ });
    // first Tab lands on the header CTA, second Tab on the space link
    await user.tab();
    await user.tab();
    expect(link).toHaveFocus();
  });

  it('space without a valid id renders non-navigable card (review #6)', async () => {
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: [{ id: undefined, name: 'Broken', status: 'ACTIVE' }],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    expect(await screen.findByText('Broken')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Broken/ })).not.toBeInTheDocument();
  });

  it('rejects whitespace-only names client-side (PHASE 10)', async () => {
    const user = userEvent.setup();
    const createLearningSpace = vi.fn(async () => ({
      data: { id: 1, name: 'X', status: 'ACTIVE' },
      response: { status: 201 },
    }));
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({ data: [], response: { status: 200 } })),
      createLearningSpace,
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    const createButtons = await screen.findAllByRole('button', {
      name: 'Create Learning Space',
    });
    await user.click(createButtons[0]);

    const nameInput = await screen.findByLabelText('Name');
    await user.type(nameInput, '   ');
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Create' }));
    expect(createLearningSpace).not.toHaveBeenCalled();
  });

  it('trims the space name before submitting (PHASE 10)', async () => {
    const user = userEvent.setup();
    const createLearningSpace = vi.fn(async () => ({
      data: { id: 1, name: 'Math', status: 'ACTIVE' },
      response: { status: 201 },
    }));
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({ data: [], response: { status: 200 } })),
      createLearningSpace,
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    const createButtons = await screen.findAllByRole('button', {
      name: 'Create Learning Space',
    });
    await user.click(createButtons[0]);

    await user.type(await screen.findByLabelText('Name'), '  Math 101  ');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => {
      expect(createLearningSpace).toHaveBeenCalledWith({
        name: 'Math 101',
        description: undefined,
      });
    });
  });

  it('shows the create failure in the dialog and keeps it open (PHASE 10)', async () => {
    const user = userEvent.setup();
    const createLearningSpace = vi.fn(async () => ({
      data: undefined,
      error: { message: 'boom' },
      response: { status: 500 },
    }));
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({ data: [], response: { status: 200 } })),
      createLearningSpace,
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    const createButtons = await screen.findAllByRole('button', {
      name: 'Create Learning Space',
    });
    await user.click(createButtons[0]);

    await user.type(await screen.findByLabelText('Name'), 'Math 101');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('服务器暂时无法完成请求，请稍后重试。');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('disables the submit button while create is pending (PHASE 10)', async () => {
    const user = userEvent.setup();
    let resolveCreate: (value: unknown) => void = () => undefined;
    const createLearningSpace = vi.fn(
      () =>
        new Promise((resolve) => {
          resolveCreate = resolve;
        })
    );
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({ data: [], response: { status: 200 } })),
      createLearningSpace,
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    const createButtons = await screen.findAllByRole('button', {
      name: 'Create Learning Space',
    });
    await user.click(createButtons[0]);

    await user.type(await screen.findByLabelText('Name'), 'Math 101');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    const submit = await screen.findByRole('button', { name: 'Creating…' });
    expect(submit).toBeDisabled();
    // Escape / close are blocked while pending
    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    resolveCreate({ data: { id: 1, name: 'Math 101', status: 'ACTIVE' }, response: { status: 201 } });
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });

  it('does not navigate when the created space lacks a valid id (PHASE 10)', async () => {
    const user = userEvent.setup();
    const createLearningSpace = vi.fn(async () => ({
      data: { name: 'NoId', status: 'ACTIVE' },
      response: { status: 201 },
    }));
    const listLearningSpaces = vi
      .fn()
      .mockResolvedValueOnce({ data: [], response: { status: 200 } })
      .mockResolvedValue({ data: [], response: { status: 200 } });
    const apiClient = mockApiClient({
      listLearningSpaces,
      createLearningSpace,
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    const createButtons = await screen.findAllByRole('button', {
      name: 'Create Learning Space',
    });
    await user.click(createButtons[0]);

    await user.type(await screen.findByLabelText('Name'), 'NoId');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => {
      expect(listLearningSpaces).toHaveBeenCalledTimes(2);
    });
    // dialog closed, no /spaces/undefined link was ever produced
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(
      document.querySelector('a[href="/spaces/undefined"]')
    ).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('/spaces/undefined');
  });

  it('shows a forbidden error state on 403 (PHASE 10)', async () => {
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: undefined,
        error: { message: 'forbidden' },
        response: { status: 403 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    expect(
      await screen.findByText('当前会话无权执行此操作。')
    ).toBeInTheDocument();
  });

  it('shows a network error state and retries on demand (PHASE 10)', async () => {
    const user = userEvent.setup();
    const listLearningSpaces = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('fetch failed'))
      .mockResolvedValue({
        data: [{ id: 1, name: 'Math 101', status: 'ACTIVE' }],
        response: { status: 200 },
      });
    const apiClient = mockApiClient({ listLearningSpaces });

    renderWithProviders(<SpacesPage />, { apiClient });

    expect(
      await screen.findByText('无法连接到后端服务。请确认服务已启动后重试。')
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByText('Math 101')).toBeInTheDocument();
  });

  it('shows a server error state with the retry action (PHASE 10)', async () => {
    const listLearningSpaces = vi.fn(async () => ({
      data: undefined,
      error: { message: 'boom' },
      response: { status: 500 },
    }));
    const apiClient = mockApiClient({ listLearningSpaces });

    renderWithProviders(<SpacesPage />, { apiClient });

    expect(
      await screen.findByText('服务器暂时无法完成请求，请稍后重试。')
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument();
  });

  it('renders a space with missing optional fields safely (PHASE 10)', async () => {
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: [{ id: 3, name: undefined, status: undefined, description: undefined }],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    expect(await screen.findByText('Space #3')).toBeInTheDocument();
    expect(screen.getByText('No description')).toBeInTheDocument();
    // no #undefined / #null artifacts anywhere
    expect(document.body.textContent).not.toMatch(/#undefined|#null/);
  });

  it('renders a 200-character space name without breaking (STRETCH C)', async () => {
    const longName = '长'.repeat(200);
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: [{ id: 1, name: longName, status: 'ACTIVE' }],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    expect(await screen.findByText(longName)).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: new RegExp(longName.slice(0, 20)) })
    ).toBeInTheDocument();
  });

  it('renders a long description safely (STRETCH C)', async () => {
    const longDescription = 'x'.repeat(400);
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: [{ id: 1, name: 'Space', description: longDescription, status: 'ACTIVE' }],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    expect(await screen.findByText('Space')).toBeInTheDocument();
    expect(screen.getByText(longDescription)).toBeInTheDocument();
  });
});
