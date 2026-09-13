/**
 * Global 404 fallback route (FE-001.5 PHASE 2).
 *
 * Split out of router.tsx so the router module exports only the router
 * constant — satisfies the react-refresh export contract.
 */

export function NotFoundPage() {
  return (
    <div className="page">
      <h1 className="page__title">Not found</h1>
      <p className="muted">The page you requested does not exist.</p>
    </div>
  );
}
