import { defineConfig, devices } from '@playwright/test'

/**
 * E2E runs against the *production* build (`vite build --mode e2e` + `vite preview`), so security headers and the strict
 * CSP are exercised exactly as shipped. `/api` is proxied to the backend (MEMO_API_ORIGIN, default the `pnpm dev` stack).
 * Projects (TESTING_STRATEGY §9): smoke = PR; firefox/webkit/pseudo/visual = nightly.
 */
const PORT = 4173
const baseURL = process.env.E2E_BASE_URL ?? `http://localhost:${String(PORT)}`
const ci = Boolean(process.env.CI)
const externalServer = Boolean(process.env.E2E_BASE_URL)

export default defineConfig({
  testDir: './e2e',
  outputDir: './test-results',
  fullyParallel: true,
  forbidOnly: ci,
  retries: 0, // PR stage: no retries (flaky tests are bugs). Nightly overrides with --retries=1.
  reporter: ci ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  expect: {
    toHaveScreenshot: { maxDiffPixelRatio: 0.01, animations: 'disabled' },
  },
  // Only the visual project takes screenshots; baselines are Linux-rendered (e2e/update-visual-baselines.sh).
  snapshotPathTemplate: '{testDir}/__screenshots__/{testFilePath}/{arg}{ext}',
  projects: [
    { name: 'smoke', testMatch: /smoke\/.*\.spec\.ts/, use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', testMatch: /smoke\/.*\.spec\.ts/, use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', testMatch: /smoke\/.*\.spec\.ts/, use: { ...devices['Desktop Safari'] } },
    { name: 'pseudo', testMatch: /pseudo\/.*\.spec\.ts/, use: { ...devices['Desktop Chrome'] } },
    // Baselines are Linux-rendered: generate them in the Playwright container (see web/e2e/README.md).
    {
      name: 'visual',
      testMatch: /visual\/.*\.spec\.ts/,
      // Fixed viewport, time zone and locale so screenshots are deterministic.
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 }, timezoneId: 'UTC', locale: 'en-US' },
    },
  ],
  webServer: externalServer
    ? undefined
    : {
        command: 'pnpm build:e2e && pnpm preview',
        url: baseURL,
        reuseExistingServer: !ci,
        timeout: 180_000,
      },
})
