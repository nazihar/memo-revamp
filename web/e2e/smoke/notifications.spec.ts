import { expect, test } from '../fixtures'

/**
 * Notifications (E06 test plan T7). The flows that create notifications are covered by backend tests (a role grant here
 * would race the access spec on the same demo user); these journeys check the screens, their wiring and accessibility.
 */
test.describe('as a user', () => {
  test.use({ role: 'user' })

  test('the bell leads to my notifications, and security notices cannot be turned off', async ({ app, page }) => {
    await app.visit('/')
    await page.getByRole('link', { name: /^Notifications/ }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'Notifications' })).toBeVisible()
    await app.checkA11y()

    await app.visit('/me/notifications')
    await expect(page.getByRole('heading', { level: 1, name: 'Notification settings' })).toBeVisible()
    await expect(page.getByRole('checkbox', { name: 'Security: Email' })).toBeDisabled()
    const adminEmail = page.getByRole('checkbox', { name: 'Access and administration: Email' })
    const before = await adminEmail.isChecked()
    await adminEmail.click()
    await expect(page.getByText('Notification settings saved.')).toBeVisible()
    await page.reload()
    await expect(page.getByRole('checkbox', { name: 'Access and administration: Email' })).toBeChecked({
      checked: !before,
    })
    await page.getByRole('checkbox', { name: 'Access and administration: Email' }).click()
    await app.checkA11y()
  })
})

test.describe('as a tenant admin', () => {
  test.use({ role: 'tenantAdmin' })

  test('the email page shows the relay form and the delivery log', async ({ app, page }) => {
    await app.visit('/admin/email')
    await expect(page.getByRole('heading', { level: 1, name: 'Email delivery' })).toBeVisible()
    await expect(page.getByLabel('Mail server')).toBeVisible()
    await expect(page.getByRole('table', { name: 'Delivery of recent emails, newest first' })).toBeVisible()
    await app.checkA11y()
  })
})
