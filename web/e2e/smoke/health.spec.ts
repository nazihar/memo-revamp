import { expect, test } from '../fixtures'

/** Critical journey for Phase 0: the walking skeleton end to end (browser → SPA → API → DB and services). */
test('health page shows the live platform status', async ({ app, page }) => {
  await app.visit('/status')

  await expect(page.getByRole('heading', { level: 1, name: 'Platform status' })).toBeVisible()
  await expect(page.getByText('Overall')).toBeVisible()
  const components = page.locator('dl dt')
  await expect(components).toHaveText(['Database', 'Object storage', 'Document server', 'Malware scanner', 'Mail'])
  // The database is the one component that must be up for the page to make sense.
  await expect(page.locator('dl > div').first().locator('[data-status]')).toHaveAttribute('data-status', 'UP')
})

test('shell is keyboard operable: skip link, then theme and language menus', async ({ app, page }) => {
  await app.visit('/status')

  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: 'Skip to main content' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('main')).toBeFocused()

  await page.getByRole('button', { name: /Change theme/ }).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('menuitemradio', { name: 'Dark' })).toBeVisible()
  await app.checkA11y()
  await page.getByRole('menuitemradio', { name: 'Dark' }).press('Enter')
  await expect(page.locator('html')).toHaveClass(/dark/)
  await app.checkA11y() // dark theme contrast

  await page.getByRole('button', { name: /Change language/ }).click()
  await page.getByRole('menuitemradio', { name: 'العربية' }).click()
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
  await expect(page.getByRole('heading', { level: 1, name: 'حالة المنصة' })).toBeVisible()
  await app.checkA11y() // RTL

  // Preferences survive a reload and are applied before the app renders (theme-init.js).
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
  await expect(page.locator('html')).toHaveClass(/dark/)
})
