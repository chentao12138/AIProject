/**
 * CSP policies (ELECTRON-CORS-001-A).
 *
 * Pure builder so the exact policy strings are unit-testable. The
 * production policy is set directly on app:// responses by the
 * protocol handler; the dev policy rides on http(s) responses via
 * onHeadersReceived. Dev vs prod are EXPLICITLY distinct — there is
 * no loose meta CSP anywhere.
 */

export function cspFor(isDev: boolean, devServerUrl?: string): string {
  let devOrigin = 'http://localhost:5173';
  if (isDev && devServerUrl) {
    try {
      devOrigin = new URL(devServerUrl).origin;
    } catch {
      // keep default
    }
  }
  const devWs = devOrigin.replace(/^http/, 'ws');
  const connectSrc = isDev
    ? `'self' ${devOrigin} ${devWs} http://localhost:8080`
    : `'self' http://localhost:8080`;
  // Dev: @vitejs/plugin-react injects an inline preamble script, so
  // script-src needs 'unsafe-inline' (documented dev-only relaxation).
  const scriptSrc = isDev ? "'self' 'unsafe-inline'" : "'self'";
  const styleSrc = isDev ? "'self' 'unsafe-inline'" : "'self'";
  return [
    "default-src 'self'",
    `script-src ${scriptSrc}`,
    `style-src ${styleSrc}`,
    // Scoped exception: React inline style attributes (category tree
    // indent, style={{ paddingLeft: depth * 14 }}). No production
    // <style> block is allowed; nothing else gets inline capability.
    "style-src-attr 'unsafe-inline'",
    "img-src 'self' data:",
    `connect-src ${connectSrc}`,
    "object-src 'none'",
    "base-uri 'none'",
    "frame-ancestors 'none'",
    "form-action 'self'",
  ].join('; ');
}
