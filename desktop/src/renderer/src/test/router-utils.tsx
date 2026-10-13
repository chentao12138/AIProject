import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router-dom';

/**
 * Data-router test harness (A8.2 and every later `useBlocker` flow).
 *
 * Two things the plain `renderWithProviders` cannot do: `useBlocker` only
 * exists on a data router, and jsdom hands that router a jsdom `AbortSignal`
 * which Node's undici `Request` rejects ("Expected signal to be an instance of
 * AbortSignal") — so a navigation the blocker *allows* never lands. The signal
 * is dropped once, here, so nothing else in the suite has to know about it.
 * No test in this repository depends on in-flight fetch cancellation.
 */
const NativeRequest = globalThis.Request;

if (!(NativeRequest as { __jsdomSignalTolerant?: boolean }).__jsdomSignalTolerant) {
  class UndiciCompatibleRequest extends NativeRequest {
    constructor(input: RequestInfo | URL, init?: RequestInit) {
      const { signal: _jsdomSignal, ...rest } = init ?? {};
      super(input, rest);
    }
  }
  (UndiciCompatibleRequest as unknown as { __jsdomSignalTolerant: boolean }).__jsdomSignalTolerant =
    true;
  globalThis.Request = UndiciCompatibleRequest as typeof Request;
}

export interface RouterRenderOptions {
  initialEntries?: string[];
}

/** Mount a memory data router and return it, so tests can assert its location. */
export function renderWithRouterProvider(
  routes: RouteObject[],
  options: RouterRenderOptions = {}
) {
  const router = createMemoryRouter(routes, {
    initialEntries: options.initialEntries ?? ['/'],
  });
  render(<RouterProvider router={router} />);
  return router;
}
