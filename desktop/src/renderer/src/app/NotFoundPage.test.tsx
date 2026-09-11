/**
 * NotFoundPage (FE-001.5 PHASE 23).
 *
 * The catch-all route must render a real page-level fallback, not a
 * crash or a blank main area.
 */

import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { NotFoundPage } from './NotFoundPage';
import { renderWithProviders } from '../test/test-utils';

describe('NotFoundPage', () => {
  it('renders a visible not-found fallback (PHASE 23)', () => {
    renderWithProviders(<NotFoundPage />);

    expect(
      screen.getByRole('heading', { name: 'Not found' })
    ).toBeInTheDocument();
    expect(
      screen.getByText('The page you requested does not exist.')
    ).toBeInTheDocument();
  });
});
