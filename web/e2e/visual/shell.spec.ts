import { expect, test } from '../fixtures'

/**
 * Visual regression (nightly): the shell + health page in light/dark × LTR/RTL. API data is fixed so screenshots are
 * deterministic. Baselines are rendered on Linux in the Playwright container (web/e2e/README.md) and reviewed like code.
 */
const variants = [
  { name: 'light-ltr', theme: 'light', locale: 'en' },
  { name: 'dark-ltr', theme: 'dark', locale: 'en' },
  { name: 'light-rtl', theme: 'light', locale: 'ar' },
  { name: 'dark-rtl', theme: 'dark', locale: 'ar' },
] as const

for (const variant of variants) {
  test(`health page — ${variant.name}`, async ({ app, page }) => {
    await page.addInitScript(
      ({ theme, locale }) => {
        localStorage.setItem('memo.theme', theme)
        localStorage.setItem('memo.locale', locale)
      },
      { theme: variant.theme, locale: variant.locale },
    )
    await page.clock.setFixedTime(new Date('2026-10-14T10:00:05Z'))
    await page.route('**/api/v1/health', (route) =>
      route.fulfill({
        json: {
          status: 'DEGRADED',
          checkedAt: '2026-10-14T10:00:00Z',
          components: [
            { name: 'database', status: 'UP' },
            { name: 'objectStorage', status: 'UP' },
            { name: 'documentServer', status: 'DISABLED' },
            { name: 'malwareScanner', status: 'DOWN' },
            { name: 'mail', status: 'UP' },
          ],
        },
      }),
    )
    await app.visit('/')
    await expect(page.locator('dl dt')).toHaveCount(5)
    await expect(page).toHaveScreenshot(`${variant.name}.png`, { fullPage: true })
  })
}
