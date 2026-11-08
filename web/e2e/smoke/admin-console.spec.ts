import { expect, test } from '../fixtures'

/**
 * The tenant admin console (E07 test plan T8): every new screen opens for an organisation administrator and passes axe.
 * Saving is covered by the backend tests and the platform journey; this checks the wiring and accessibility.
 */
test.describe('as a tenant admin', () => {
  test.use({ role: 'tenantAdmin' })

  test('the overview leads to each administration area', async ({ app, page }) => {
    await app.visit('/admin')
    await expect(page.getByRole('heading', { level: 1, name: 'Administration' })).toBeVisible()

    for (const [path, heading] of [
      ['/admin/security', 'Security'],
      ['/admin/branding', 'Branding'],
      ['/admin/language', 'Language and terms'],
      ['/admin/audit', 'Audit'],
    ] as const) {
      await app.visit(path)
      await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible()
    }
    await expect(page.getByRole('table', { name: 'Audit entries, newest first' })).toBeVisible()
  })

  test('a colour with too little contrast is flagged before saving', async ({ app, page }) => {
    await app.visit('/admin/branding')
    await page.getByLabel('Colour, light theme').fill('#ffff00')
    await expect(page.getByText('Too little contrast').first()).toBeVisible()
    await app.checkA11y()
  })
})

test.describe('as a user', () => {
  test.use({ role: 'user' })

  test('the administration overview is not in the menu', async ({ app, page }) => {
    await app.visit('/')
    await expect(page.getByRole('link', { name: 'Overview' })).toHaveCount(0)
  })
})
