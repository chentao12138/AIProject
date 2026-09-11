/**
 * AppShell tests (FE-001.5 PHASE 7 + 8 + PRE-COMMIT REVIEW FIX-01).
 *
 * PHASE 7: the backend label is a NEUTRAL configuration target — no
 * green "online" dot, no health claim (no health probe exists).
 * PHASE 8: semantic landmarks, active-link semantics, current space
 * name, and no child-space links when there is no valid current space.
 * FIX-01: `end` on Spaces (one active primary nav link at a time),
 * child links only after a successful space query, and differentiated
 * unavailable labels (404 vs other errors).
 */

import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { AppShell } from './AppShell';
import { mockApiClient, renderWithProviders } from '../test/test-utils';
import type { ApiClient } from '@aistudy/api-client';

function renderShell(initialEntries: string[], apiClient: ApiClient) {
  return renderWithProviders(
    <Routes>
      <Route path="/" element={<AppShell />}>
        <Route path="spaces" element={<div>spaces-outlet</div>} />
        <Route
          path="spaces/:spaceId/sources"
          element={<div>sources-outlet</div>}
        />
        <Route
          path="spaces/:spaceId/knowledge"
          element={<div>knowledge-outlet</div>}
        />
        <Route path="spaces/:spaceId" element={<div>scope-outlet</div>} />
      </Route>
    </Routes>,
    { apiClient, initialEntries }
  );
}

/** Successful space query returning a fixed space. */
function spaceOkApiClient(): ApiClient {
  return mockApiClient({
    getLearningSpace: vi.fn(async () => ({
      data: { id: 7, name: 'My Research Space' },
      response: { status: 200 },
    })),
  });
}

/** HTTP-error space query for a given status. */
function spaceHttpErrorApiClient(status: number): ApiClient {
  return mockApiClient({
    getLearningSpace: vi.fn(async () => ({
      data: undefined,
      error: { message: 'boom' },
      response: { status },
    })),
  });
}

describe('AppShell — backend indicator semantics (PHASE 7)', () => {
  it('renders a neutral API target label, not a fake online indicator', () => {
    renderShell(['/spaces'], mockApiClient());

    const label = screen.getByText(/API target: http:\/\/localhost:8080/);
    expect(label).toBeInTheDocument();

    // No green-dot / online wording anywhere in the shell.
    expect(screen.queryByText(/backend http/)).not.toBeInTheDocument();
    expect(document.querySelector('.dot')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/●/);
  });
});

describe('AppShell — shell structure and navigation (PHASE 8)', () => {
  it('exposes semantic landmarks: header, nav, main', () => {
    renderShell(['/spaces'], mockApiClient());

    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(
      screen.getByRole('navigation', { name: 'Main navigation' })
    ).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
  });

  it('marks the active navigation link with aria-current', () => {
    renderShell(['/spaces'], mockApiClient());

    const spacesLink = screen.getByRole('link', { name: 'Spaces' });
    expect(spacesLink).toHaveAttribute('aria-current', 'page');
  });

  it('shows the resolved current space name in the sidebar', async () => {
    renderShell(['/spaces/7/sources'], spaceOkApiClient());

    expect(await screen.findByText('My Research Space')).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'Sources' })
    ).toBeInTheDocument();
  });

  it('shows no Sources/Knowledge links without a valid current space', () => {
    renderShell(['/spaces'], mockApiClient());

    expect(screen.queryByRole('link', { name: 'Sources' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Knowledge' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Spaces' })).toBeInTheDocument();
  });
});

describe('AppShell — single active primary nav link (FIX-01)', () => {
  it('/spaces keeps Spaces active with aria-current=page', () => {
    renderShell(['/spaces'], mockApiClient());
    expect(screen.getByRole('link', { name: 'Spaces' })).toHaveAttribute(
      'aria-current',
      'page'
    );
  });

  it('/spaces/7/sources: Sources active, Spaces NOT active', async () => {
    renderShell(['/spaces/7/sources'], spaceOkApiClient());

    expect(await screen.findByRole('link', { name: 'Sources' })).toHaveAttribute(
      'aria-current',
      'page'
    );
    expect(screen.getByRole('link', { name: 'Spaces' })).not.toHaveAttribute(
      'aria-current'
    );
    expect(screen.getByRole('link', { name: 'Knowledge' })).not.toHaveAttribute(
      'aria-current'
    );
  });

  it('/spaces/7/knowledge: Knowledge active, Spaces NOT active', async () => {
    renderShell(['/spaces/7/knowledge'], spaceOkApiClient());

    expect(await screen.findByRole('link', { name: 'Knowledge' })).toHaveAttribute(
      'aria-current',
      'page'
    );
    expect(screen.getByRole('link', { name: 'Spaces' })).not.toHaveAttribute(
      'aria-current'
    );
    expect(screen.getByRole('link', { name: 'Sources' })).not.toHaveAttribute(
      'aria-current'
    );
  });

  it('at most one primary nav link carries aria-current at any time', async () => {
    renderShell(['/spaces/7/sources'], spaceOkApiClient());

    await screen.findByRole('link', { name: 'Sources' });
    const activeLinks = screen
      .getAllByRole('link', { name: /Spaces|Sources|Knowledge/ })
      .filter((link) => link.getAttribute('aria-current') !== null);
    expect(activeLinks).toHaveLength(1);
    expect(activeLinks[0]).toHaveAttribute('aria-current', 'page');
  });
});

describe('AppShell — space unavailable semantics (FIX-01)', () => {
  it('404 shows the authorization-safe inaccessible label and NO child links', async () => {
    renderShell(['/spaces/404'], spaceHttpErrorApiClient(404));

    expect(
      await screen.findByText('Space not found or inaccessible')
    ).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sources' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Knowledge' })).not.toBeInTheDocument();
  });

  it('network failure shows the neutral "Space unavailable" and NO child links', async () => {
    const apiClient = mockApiClient({
      getLearningSpace: vi.fn(async () => {
        throw new TypeError('fetch failed');
      }),
    });
    renderShell(['/spaces/7/sources'], apiClient);

    expect(await screen.findByText('Space unavailable')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sources' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Knowledge' })).not.toBeInTheDocument();
  });

  it('401 shows the neutral "Space unavailable" and NO child links', async () => {
    renderShell(['/spaces/7/sources'], spaceHttpErrorApiClient(401));

    expect(await screen.findByText('Space unavailable')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sources' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Knowledge' })).not.toBeInTheDocument();
  });

  it('5xx shows the neutral "Space unavailable" and NO child links', async () => {
    renderShell(['/spaces/7/sources'], spaceHttpErrorApiClient(500));

    expect(await screen.findByText('Space unavailable')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sources' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Knowledge' })).not.toBeInTheDocument();
  });

  it('pending space query shows NO seemingly-valid child links', async () => {
    const apiClient = mockApiClient({
      getLearningSpace: vi.fn(() => new Promise<never>(() => {})),
    });
    renderShell(['/spaces/7/sources'], apiClient);

    expect(screen.getByText('Space')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sources' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Knowledge' })).not.toBeInTheDocument();
  });

  it('successful space query shows the space name AND both child links', async () => {
    renderShell(['/spaces/7/sources'], spaceOkApiClient());

    expect(await screen.findByText('My Research Space')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sources' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Knowledge' })).toBeInTheDocument();
  });
});
