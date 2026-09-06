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
import { QueryClient, useQuery } from '@tanstack/react-query';
import type { QueryKey } from '@tanstack/react-query';
import { DevelopmentSession } from './DevelopmentSession';
import { tokenSession } from '../../lib/api-client';
import { renderWithProviders } from '../../test/test-utils';

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
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
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
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
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
});
