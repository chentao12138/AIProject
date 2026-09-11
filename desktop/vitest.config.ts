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
      'src/shared/**/*.test.ts',
    ],
    coverage: {
      // FE-001.5 PHASE 24: real coverage baseline for renderer app logic
      // + pure main-process helpers. Test files, test infra, build
      // configs and shared-client code are excluded by definition.
      provider: 'v8',
      reporter: ['text', 'text-summary'],
      include: [
        'src/renderer/src/**/*.{ts,tsx}',
        'src/main/**/*.ts',
        'src/shared/**/*.ts',
      ],
      exclude: [
        '**/*.test.{ts,tsx}',
        'src/renderer/src/test/**',
        'src/renderer/src/**/*.test.tsx',
        'src/shared/**/*.test.ts',
      ],
    },
  },
});
