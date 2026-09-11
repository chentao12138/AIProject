/**
 * AIStudy Desktop — ESLint 9 flat config (FE-001.5 PHASE 2).
 *
 * Scope: whole desktop package. Rules catch unused vars/imports,
 * accidental `any`, React Hooks violations, no-fallthrough/no-unreachable,
 * and invalid React fast-refresh export patterns.
 *
 * Deliberately NOT a giant opinionated style stack: no Prettier, no
 * stylistic churn. node/main configs get Node globals; the renderer
 * gets browser globals only (a renderer reference to a Node global is
 * a security smell and stays visible to the static scan in PHASE 26).
 */
import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';

export default tseslint.config(
  {
    ignores: ['out/**', 'dist/**', 'node_modules/**', '*.log', '*.tsbuildinfo'],
  },

  js.configs.recommended,
  ...tseslint.configs.recommended,

  // Underscore-prefixed params are intentionally unnamed (e.g. the
  // permission handler's webContents argument).
  {
    rules: {
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },

  // Node-side code: main process, preload, build configs.
  {
    files: [
      'src/main/**/*.ts',
      'src/preload/**/*.ts',
      'electron.vite.config.ts',
      'vitest.config.ts',
    ],
    languageOptions: {
      globals: globals.node,
    },
  },

  // Renderer code: browser globals only.
  {
    files: ['src/renderer/src/**/*.{ts,tsx}'],
    languageOptions: {
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      '@typescript-eslint/no-explicit-any': 'error',
    },
  },

  // React fast refresh: only components may be exported from .tsx
  // files. lib/ and test files are exempt (they export hooks, fakes
  // and helpers by design).
  {
    files: ['src/renderer/src/**/*.tsx'],
    plugins: {
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactRefresh.configs.vite.rules,
    },
  },
  {
    files: [
      'src/renderer/src/lib/**',
      'src/renderer/src/test/**',
      'src/renderer/src/**/*.test.{ts,tsx}',
    ],
    rules: {
      'react-refresh/only-export-components': 'off',
    },
  },

  // Test files: vitest globals are injected by vitest.config.ts
  // (globals: true) — declare them for no-undef.
  {
    files: ['**/*.test.{ts,tsx}'],
    languageOptions: {
      globals: {
        ...globals.browser,
        vi: 'readonly',
        describe: 'readonly',
        it: 'readonly',
        expect: 'readonly',
        beforeEach: 'readonly',
        afterEach: 'readonly',
      },
    },
  },
);
