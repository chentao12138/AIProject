import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Admin Web Vite config (ADR-028).
 *
 * Requests use RELATIVE /api/v1/... URLs so production can be served as
 * static files behind the same reverse proxy that fronts the Spring
 * Boot API (no CORS at all in production). During development the Vite
 * dev server proxies /api to the local backend — set
 * VITE_PROXY_TARGET to point at a non-default backend.
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxyTarget = env.VITE_PROXY_TARGET ?? 'http://localhost:8080';
  return {
    plugins: [react()],
    resolve: {
      // @aistudy/api-client is a file: symlink package (ESM TS source);
      // preserving symlinks lets its `import 'openapi-fetch'` resolve
      // from admin-web/node_modules.
      preserveSymlinks: true,
    },
    server: {
      port: 5174,
      strictPort: true,
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
        },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
    },
  };
});
