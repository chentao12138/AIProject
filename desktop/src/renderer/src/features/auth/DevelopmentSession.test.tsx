/**
 * DevelopmentSession tests (FE-001 pre-commit review fix #1).
 *
 * The dev session is the AUTH CACHE BOUNDARY: Apply / Clear must reset
 * TanStack Query server-state so a previous principal's cached data can
 * never survive a token switch, and active queries must refetch under
 * the new token.
 *
 * tokenSession is a module-level singleton — every test clears it so
 * runs never pollute each other.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useQuery } from '@tanstack/react-query';
import type { QueryKey } from '@tanstack/react-query';
import { DevelopmentSession } from './DevelopmentSession';
import { tokenSession } from '../../lib/api-client';
import {
  createTestQueryClient,
  renderWithProviders,
} from '../../test/test-utils';

/** Renders a real useQuery so cache reset / refetch is observable. */
function Probe({
  queryKey,
  queryFn,
}: {
  queryKey: QueryKey;
  queryFn: () => Promise<unknown>;
}) {
  const { data } = useQuery({ queryKey, queryFn, retry: false });
  return <div>{String(data ?? '')}</div>;
}

describe('DevelopmentSession', () => {
  afterEach(() => {
    tokenSession.clear();
  });

  it('Apply sets the token and resets the query cache (review #1)', async () => {
    const user = userEvent.setup();
    const queryClient = createTestQueryClient();
    // First call resolves v1; the reset-triggered refetch resolves v2.
    const queryFn = vi
      .fn()
      .mockResolvedValueOnce('data-v1')
      .mockResolvedValue('data-v2');

    renderWithProviders(
      <>
        <DevelopmentSession />
        <Probe queryKey={['spaces']} queryFn={queryFn} />
      </>,
      { queryClient }
    );

    expect(await screen.findByText('data-v1')).toBeInTheDocument();
    expect(queryFn).toHaveBeenCalledTimes(1);

    await user.type(
      screen.getByLabelText('Development access token'),
      'token-B'
    );
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    expect(tokenSession.getAccessToken()).toBe('token-B');
    // cache reset -> active query refetched under the new token
    await waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('data-v2')).toBeInTheDocument();
  });

  it('Clear resets the query cache and clears the token (review #1)', async () => {
    tokenSession.setAccessToken('token-A');
    const user = userEvent.setup();
    const queryClient = createTestQueryClient();
    const queryFn = vi
      .fn()
      .mockResolvedValueOnce('data-v1')
      .mockResolvedValue('data-v2');

    renderWithProviders(
      <>
        <DevelopmentSession />
        <Probe queryKey={['spaces']} queryFn={queryFn} />
      </>,
      { queryClient }
    );

    expect(await screen.findByText('data-v1')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Clear' }));

    expect(tokenSession.getAccessToken()).toBeNull();
    // cache reset -> active query refetched anonymously
    await waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('data-v2')).toBeInTheDocument();
  });

  it('tokenSession reflects apply/clear transitions (review #1)', async () => {
    tokenSession.clear();
    expect(tokenSession.getAccessToken()).toBeNull();

    tokenSession.setAccessToken('  token-X  ');
    expect(tokenSession.getAccessToken()).toBe('token-X'); // trimmed, in-memory

    tokenSession.clear();
    expect(tokenSession.getAccessToken()).toBeNull();
  });

  it('Apply is disabled for empty input (PHASE 6)', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DevelopmentSession />);

    const applyButton = screen.getByRole('button', { name: 'Apply' });
    expect(applyButton).toBeDisabled();

    await user.type(screen.getByLabelText('Development access token'), 'abc');
    expect(screen.getByRole('button', { name: 'Apply' })).toBeEnabled();
  });

  it('Apply is disabled for whitespace-only input (PHASE 6)', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DevelopmentSession />);

    await user.type(screen.getByLabelText('Development access token'), '   ');
    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled();
  });

  it('Apply trims the token and announces an applied status (PHASE 6)', async () => {
    tokenSession.clear();
    const user = userEvent.setup();
    const queryClient = createTestQueryClient();

    renderWithProviders(<DevelopmentSession />, { queryClient });

    await user.type(
      screen.getByLabelText('Development access token'),
      '  token-trimmed  '
    );
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    expect(tokenSession.getAccessToken()).toBe('token-trimmed');
    const status = await screen.findByRole('status');
    expect(status).toHaveTextContent('Token applied (in-memory only).');
  });

  it('Clear announces a cleared status (PHASE 6)', async () => {
    tokenSession.setAccessToken('token-A');
    const user = userEvent.setup();

    renderWithProviders(<DevelopmentSession />);

    await user.click(screen.getByRole('button', { name: 'Clear' }));

    expect(tokenSession.getAccessToken()).toBeNull();
    expect(await screen.findByText('Token cleared.')).toBeInTheDocument();
  });

  it('editing the input clears the stale applied status (PHASE 6)', async () => {
    tokenSession.clear();
    const user = userEvent.setup();

    renderWithProviders(<DevelopmentSession />);

    await user.type(screen.getByLabelText('Development access token'), 'tok');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByText(/Token applied/)).toBeInTheDocument();

    await user.type(screen.getByLabelText('Development access token'), '-more');
    expect(screen.queryByText(/Token applied/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Token cleared/)).not.toBeInTheDocument();
  });

  it('the token value never appears in the document (password masking, PHASE 6)', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DevelopmentSession />);

    const input = screen.getByLabelText('Development access token');
    await user.type(input, 'super-secret-token-value');
    expect(input).toHaveAttribute('type', 'password');
    expect(document.body.textContent).not.toContain('super-secret-token-value');
  });

  it('never touches persistence APIs (PHASE 6)', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DevelopmentSession />);

    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    await user.type(screen.getByLabelText('Development access token'), 'tok');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    await user.click(screen.getByRole('button', { name: 'Clear' }));

    expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });
});
