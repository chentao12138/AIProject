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
    build: {
      outDir: 'out/renderer',
    },
  },
});
