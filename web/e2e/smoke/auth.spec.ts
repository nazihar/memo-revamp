import type { Page } from '@playwright/test'
import { demoSecret, enterCode, latestMailText } from '../auth'
import { expect, test } from '../fixtures'

/**
 * Identity journeys (E03 test plan T9/T10) on the real stack: sign-in with TOTP, an invitation accepted through the
 * emailed link (Mailpit), MFA enrolment, sessions, and an admin action behind step-up.
 */

/** Admin actions may ask to confirm with a code; the demo admin answers with the demo TOTP secret. */
async function confirmIfAsked(page: Page, secret: string) {
  const dialog = page.getByRole('dialog', { name: 'Confirm it is you' })
  if (await dialog.isVisible().catch(() => false)) await enterCode(page, secret, 'Code', 'Confirm')
}

test('an anonymous visitor is sent to sign-in, which is accessible', async ({ app, page }) => {
  await app.visit('/')

  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByRole('heading', { level: 1, name: 'Sign in' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Forgot your password?' })).toBeVisible()
})

test.describe('as a tenant admin', () => {
  test.use({ role: 'tenantAdmin' })

  test('invite a user, who accepts by email, enrols MFA and signs in; then deactivate them', async ({
    app,
    page,
    browser,
    request,
  }) => {
    test.setTimeout(120_000) // a whole journey across two browsers and an email
    const adminSecret = demoSecret('MEMO_DEMO_TOTP_SECRET')
    const run = String(Date.now())
    const email = `e2e-${run}@demo.example`
    const name = `Leila Nasser ${run}`

    await app.visit('/admin/users')
    await expect(page.getByRole('heading', { level: 1, name: 'Users' })).toBeVisible()
    await page.getByRole('button', { name: 'Invite user' }).click()
    const invite = page.getByRole('dialog', { name: 'Invite a user' })
    await invite.getByLabel('Work email').fill(email)
    await invite.getByLabel('Full name').fill(name)
    await app.checkA11y()
    await invite.getByRole('button', { name: 'Send invitation' }).click()
    await confirmIfAsked(page, adminSecret)
    await expect(page.getByText(`Invitation sent to ${email}.`)).toBeVisible()

    // The invitee, in their own browser.
    const text = await latestMailText(request, email)
    const token = /#([A-Za-z0-9_-]{20,})/.exec(text)?.[1]
    expect(token, 'one-time token in the invitation link').toBeTruthy()
    // Explicitly empty: Playwright would otherwise give this browser the admin's storage state (test.use above).
    const invitee = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const inviteePage = await invitee.newPage()
    await inviteePage.goto(`/invitation#${token ?? ''}`)
    await expect(inviteePage.getByRole('heading', { level: 1, name: /^Join / })).toBeVisible()
    await expect(inviteePage).toHaveURL(/\/invitation$/) // the token left the address bar
    await inviteePage.getByLabel('Password').fill('violet harbour lantern sixteen e2e')
    await inviteePage.getByRole('button', { name: 'Set password and continue' }).click()

    await expect(inviteePage.getByRole('heading', { level: 1, name: 'Set up two-step sign-in' })).toBeVisible()
    const key = (await inviteePage.locator('code').innerText()).replace(/\s/g, '')
    await enterCode(inviteePage, key, 'Code from the app', 'Turn on two-step sign-in')
    await expect(inviteePage.getByRole('heading', { level: 1, name: 'Save your recovery codes' })).toBeVisible()
    await expect(inviteePage.getByRole('list', { name: 'Recovery codes' }).getByRole('listitem')).toHaveCount(10)
    await inviteePage.getByLabel('I have saved my recovery codes').check()
    await inviteePage.getByRole('button', { name: 'Continue' }).click()
    await expect(inviteePage.getByRole('heading', { level: 1, name: `Welcome, ${name}` })).toBeVisible()

    await inviteePage.goto('/me/security')
    await expect(inviteePage.getByText('This device')).toBeVisible()

    // Back to the admin: deactivation signs the invitee out.
    await page.reload()
    await page.getByLabel('Search by name or email').fill(email)
    await page.getByRole('button', { name: 'Search', exact: true }).click()
    await page.getByRole('button', { name: `Actions for ${name}` }).click()
    await page.getByRole('menuitem', { name: 'Deactivate' }).click()
    await page
      .getByRole('alertdialog', { name: `Deactivate ${name}?` })
      .getByRole('button', { name: 'Deactivate' })
      .click()
    await confirmIfAsked(page, adminSecret)
    await expect(page.getByText('Done.')).toBeVisible()

    await inviteePage.reload()
    await expect(inviteePage).toHaveURL(/\/login/)
    await invitee.close()
  })
})

test.describe('as a user', () => {
  test.use({ role: 'user' })

  test('sees the home page and no administration', async ({ app, page }) => {
    await app.visit('/')

    await expect(page.getByRole('heading', { level: 1, name: 'Welcome, Demo User' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Users' })).toHaveCount(0)
    await page.getByRole('button', { name: 'Account: Demo User' }).click()
    await expect(page.getByRole('menuitem', { name: 'Sign out' })).toBeVisible()
    await app.checkA11y()
  })
})
