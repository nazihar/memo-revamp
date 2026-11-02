import { confirmIfAsked, demoSecret } from '../auth'
import { expect, test } from '../fixtures'

/** Access administration (E04 test plan T10): a role given and revoked behind step-up; the role and level screens. */
test.describe('as a tenant admin', () => {
  test.use({ role: 'tenantAdmin' })

  test('gives the demo user the auditor role, then revokes it', async ({ app, page }) => {
    const secret = demoSecret('MEMO_DEMO_TOTP_SECRET')
    await app.visit('/admin/users')
    await page.getByRole('link', { name: 'Demo User' }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'Demo User' })).toBeVisible()
    await expect(page.getByRole('table', { name: 'Positions held, newest first' })).toContainText('Credit Officer')

    await page.getByRole('button', { name: 'Give a role' }).click()
    const dialog = page.getByRole('dialog', { name: 'Give a role' })
    await dialog.getByLabel(/^Role/).selectOption({ label: 'Auditor' })
    await dialog.getByRole('button', { name: 'Give a role' }).click()
    await confirmIfAsked(page, secret)
    const grants = page.getByRole('table', { name: 'Roles given to this person' })
    await expect(grants.getByRole('row', { name: /Auditor/ })).toBeVisible()
    await app.checkA11y()

    await grants
      .getByRole('row', { name: /Auditor/ })
      .getByRole('button', { name: 'Revoke' })
      .click()
    await page.getByRole('alertdialog', { name: 'Revoke this role?' }).getByRole('button', { name: 'Revoke' }).click()
    await confirmIfAsked(page, secret)
    await expect(grants.getByRole('row', { name: /Auditor/ })).toHaveCount(0)
  })

  test('the role and classification screens are accessible', async ({ app, page }) => {
    await app.visit('/admin/roles')
    await expect(page.getByRole('table', { name: 'Roles of the organisation' })).toContainText(
      'Organisation administrator',
    )
    await app.visit('/admin/classifications')
    await expect(page.getByRole('table', { name: 'Classification levels, least sensitive first' })).toContainText(
      'Strictly Confidential',
    )
  })
})
