/**
 * useSpaceId / requireId contract (ADR-038 scope rules).
 *
 * The URL is the only source of truth for the current LearningSpace, so
 * these are the guards every space-scoped page depends on: a malformed id
 * must mean "no space" (and therefore not-found), never a prefix of itself.
 */

import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { requireId, useSpaceId } from './space-scope';

function Probe() {
  const spaceId = useSpaceId();
  return <div>space:{spaceId === null ? 'null' : spaceId}</div>;
}

function renderAt(entry: string, routePath: string) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route path={routePath} element={<Probe />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('useSpaceId', () => {
  it('parses the :spaceId route param', () => {
    renderAt('/spaces/7/sources', '/spaces/:spaceId/sources');
    expect(screen.getByText('space:7')).toBeInTheDocument();
  });

  it('treats a malformed route param as no space, not its numeric prefix', () => {
    renderAt('/spaces/12abc/sources', '/spaces/:spaceId/sources');
    expect(screen.getByText('space:null')).toBeInTheDocument();
  });

  it('reads the space from the pathname when the route has no space param', () => {
    renderAt('/spaces/9/anything', '*');
    expect(screen.getByText('space:9')).toBeInTheDocument();
  });

  it('rejects a pathname segment that only starts with digits', () => {
    renderAt('/spaces/9abc/anything', '*');
    expect(screen.getByText('space:null')).toBeInTheDocument();
  });

  it('returns null outside any space URL', () => {
    renderAt('/spaces', '/spaces');
    expect(screen.getByText('space:null')).toBeInTheDocument();
  });
});

describe('requireId', () => {
  it('narrows a resolved id', () => {
    expect(requireId(7)).toBe(7);
  });

  it('throws a labelled error for a missing id', () => {
    expect(() => requireId(null, 'space id')).toThrow('invalid space id');
  });
});
