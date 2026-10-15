// ESLint flat config (ADR-0009). Formatting is Prettier's job; eslint-config-prettier turns off conflicting rules.
import js from '@eslint/js'
import tanstackQuery from '@tanstack/eslint-plugin-query'
import prettier from 'eslint-config-prettier'
import formatjs from 'eslint-plugin-formatjs'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import security from 'eslint-plugin-security'
import globals from 'globals'
import { defineConfig } from 'eslint/config'
import tseslint from 'typescript-eslint'

export default defineConfig(
  {
    ignores: [
      'dist/**',
      'coverage/**',
      'playwright-report/**',
      'test-results/**',
      '.lighthouseci/**',
      'src/api/generated/**',
      'src/routeTree.gen.ts',
      'public/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.strictTypeChecked,
  ...tseslint.configs.stylisticTypeChecked,
  ...tanstackQuery.configs['flat/recommended'],
  security.configs.recommended,
  {
    languageOptions: {
      globals: { ...globals.browser },
      parserOptions: {
        projectService: { allowDefaultProject: ['eslint.config.js'] },
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: { 'react-hooks': reactHooks, 'react-refresh': reactRefresh, formatjs },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['error', { allowConstantExport: true }],
      // AGENTS.md §3.6: every UI string goes through react-intl with an explicit id; ICU must be valid.
      'formatjs/no-literal-string-in-jsx': 'error',
      'formatjs/enforce-id': [
        'error',
        {
          idInterpolationPattern: '[sha512:contenthash:base64:6]',
          idWhitelist: ['^[a-z][a-zA-Z0-9]*(\\.[a-zA-Z0-9]+)+$'],
        },
      ],
      'formatjs/enforce-default-message': ['error', 'literal'],
      'formatjs/enforce-placeholders': 'error',
      'formatjs/no-invalid-icu': 'error',
      'formatjs/no-multiple-whitespaces': 'error',
      '@typescript-eslint/restrict-template-expressions': ['error', { allowNumber: true }],
      // Flags every obj[key]; with typed union keys (our usage) it is pure noise. XSS/injection is covered by React
      // escaping, CSP and the formatjs rules; re-evaluate if untyped user input is ever used as a key.
      'security/detect-object-injection': 'off',
    },
  },
  {
    // Route files export `Route` (TanStack convention); providers export their hook; test helpers are not hot-reloaded.
    files: ['src/routes/**/*.tsx', 'src/**/*Provider.tsx', 'src/test/**'],
    rules: { 'react-refresh/only-export-components': 'off' },
  },
  {
    // Tests and tooling: strings in assertions are not UI; Node globals available.
    files: ['**/*.test.{ts,tsx}', 'src/test/**', 'e2e/**', '*.config.{ts,js}'],
    languageOptions: { globals: { ...globals.node } },
    rules: {
      'formatjs/no-literal-string-in-jsx': 'off',
      'security/detect-non-literal-fs-filename': 'off',
      '@typescript-eslint/no-non-null-assertion': 'off',
    },
  },
  {
    // Playwright fixtures call `use()` — not a React hook.
    files: ['e2e/**'],
    rules: { 'react-hooks/rules-of-hooks': 'off' },
  },
  {
    // This file is plain JS outside the TS projects.
    files: ['eslint.config.js'],
    ...tseslint.configs.disableTypeChecked,
  },
  prettier,
)
