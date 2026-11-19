import { defineConfig, devices } from '@playwright/test'

/** The E06 T1 spike against a document server (see `minutes-controls.spec.ts`); never part of the CI projects. */
export default defineConfig({
  testDir: '.',
  outputDir: '../../test-results/spike',
  workers: 1,
  reporter: 'list',
  use: { ...devices['Desktop Chrome'], viewport: { width: 1400, height: 1000 }, trace: 'retain-on-failure' },
})
