import { expect, test } from '../fixtures'

/**
 * Visual regression (nightly) of the E01 page templates on the patterns gallery: list, form and an open dialog in
 * light/dark × LTR/RTL, plus the navigation sheet on a phone-sized screen. Baselines are rendered on Linux in the
 * Playwright container (web/e2e/README.md) and reviewed like code.
 */
const variants = [
  { name: 'light-ltr', theme: 'light', locale: 'en' },
  { name: 'dark-ltr', theme: 'dark', locale: 'en' },
  { name: 'light-rtl', theme: 'light', locale: 'ar' },
  { name: 'dark-rtl', theme: 'dark', locale: 'ar' },
] as const

for (const variant of variants) {
  test(`templates — ${variant.name}`, async ({ app, page }) => {
    await page.addInitScript(
      ({ theme, locale }) => {
        localStorage.setItem('memo.theme', theme)
        localStorage.setItem('memo.locale', locale)
      },
      { theme: variant.theme, locale: variant.locale },
    )
    await app.visit('/patterns')
    await expect(page.locator('section').first()).toHaveScreenshot(`list-${variant.name}.png`)
    await expect(page.locator('section').nth(2)).toHaveScreenshot(`form-${variant.name}.png`)
    await page.locator('section').nth(4).getByRole('button').first().click()
    await expect(page.getByRole('dialog')).toHaveScreenshot(`dialog-${variant.name}.png`)
  })
}

test.describe('phone', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('navigation sheet', async ({ app, page }) => {
    await app.visit('/patterns')
    await page.getByRole('button', { name: 'Open menu' }).click()
    await expect(page).toHaveScreenshot('sheet-phone.png')
  })
})
