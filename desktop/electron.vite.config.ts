import { resolve } from 'node:path';
import { defineConfig } from 'electron-vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  main: {
    build: {
      outDir: 'out/main',
    },
  },
  preload: {
    build: {
      outDir: 'out/preload',
    },
  },
  renderer: {
    resolve: {
      // @aistudy/api-client is a file: symlink package (ESM TS source);
      // preserving symlinks lets its internal `import 'openapi-fetch'`
      // resolve from desktop/node_modules (FE-001 PHASE D).
      preserveSymlinks: true,
      alias: {
        '@': resolve('src/renderer/src'),
      },
    },
    plugins: [react()],
    optimizeDeps: {
      // The shared client ships TypeScript source through a file: symlink, so
      // pre-bundling it leaves a stale copy in node_modules/.vite whenever the
      // package changes: every method added afterwards is `undefined` at
      // runtime, which surfaces as a misleading "backend unreachable" instead
      // of a build error. Serving it from source keeps one truth.
      exclude: ['@aistudy/api-client'],
    },
    build: {
      outDir: 'out/renderer',
    },
  },
});
