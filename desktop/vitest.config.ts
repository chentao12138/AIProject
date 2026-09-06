import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    preserveSymlinks: true,
    alias: {
      '@': resolve('src/renderer/src'),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['src/renderer/src/test/setup.ts'],
    include: [
      'src/renderer/src/**/*.test.{ts,tsx}',
      'src/main/**/*.test.ts',
    ],
  },
});
