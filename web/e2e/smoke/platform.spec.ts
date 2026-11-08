import type { Browser, Page } from '@playwright/test'
import { demoSecret, enterCode, latestMailText, totp } from '../auth'
import { assertNoA11yViolations, expect, test } from '../fixtures'

/**
 * The Phase 1a exit journey (overview §5, E07 test plan T8): an operator creates an organisation on the console's host;
 * its first administrator accepts the emailed invitation, sets up TOTP and signs in; lists and ends a session; a
 * step-up-protected action asks for a code. Then the operator suspends the organisation and resumes it.
 *
 * Hosts are `<label>.localhost` beside the base URL: Chromium and Firefox resolve them to the loopback address, WebKit
 * on Linux does not (its nightly run skips this journey).
 */
const OPERATOR_EMAIL = 'operator@demo.example'

function originFor(baseURL: string, label: string): string {
  const url = new URL(baseURL)
  return `${url.protocol}//${label}.${url.hostname}${url.port ? `:${url.port}` : ''}`
}

async function freshPage(browser: Browser, origin: string): Promise<Page> {
  // Explicitly empty: a new organisation's host has no cookies anyway, but no role's state may leak in.
  const context = await browser.newContext({ baseURL: origin, storageState: { cookies: [], origins: [] } })
  return context.newPage()
}

test('an operator creates an organisation; its first admin joins, manages sessions and confirms a sensitive action', async ({
  browser,
  browserName,
  request,
  baseURL,
}) => {
  test.skip(browserName === 'webkit', 'WebKit on Linux does not resolve *.localhost')
  test.setTimeout(240_000) // two consoles, an email, and a step-up window that must run out
  const secret = demoSecret('MEMO_DEMO_TOTP_SECRET')
  const run = String(Date.now())
  const slug = `e2e-${run}`
  const adminEmail = `first-${run}@acme.example`
  const adminName = `Amina Rahman ${run}`
  const base = baseURL ?? 'http://localhost:4173'

  // The operator, on the console's host.
  const operator = await freshPage(browser, originFor(base, 'admin'))
  await operator.goto('/platform/login')
  await expect(operator.getByRole('heading', { level: 1, name: 'Operator sign-in' })).toBeVisible()
  await assertNoA11yViolations(operator)
  await operator.getByLabel('Email').fill(OPERATOR_EMAIL)
  await operator.getByLabel('Password').fill(demoSecret('MEMO_DEMO_PASSWORD'))
  await operator.getByRole('button', { name: 'Sign in' }).click()
  await enterCode(operator, secret, 'Code', 'Continue')
  await expect(operator.getByRole('heading', { level: 1, name: 'Organisations' })).toBeVisible()
  await assertNoA11yViolations(operator)

  await operator.getByRole('link', { name: 'New organisation' }).click()
  await operator.getByLabel('Name', { exact: true }).fill(`Acme Bank ${run}`)
  await operator.getByLabel('Address', { exact: true }).fill(slug)
  await operator.getByLabel('Time zone', { exact: true }).fill('Asia/Dhaka')
  await operator.getByLabel('First administrator’s name').fill(adminName)
  await operator.getByLabel('First administrator’s email').fill(adminEmail)
  await assertNoA11yViolations(operator)
  await operator.getByRole('button', { name: 'Create and invite' }).click()
  await expect(operator.getByRole('heading', { level: 1, name: `Acme Bank ${run}` })).toBeVisible()
  await expect(operator.getByText('No administrator yet')).toBeVisible()

  // The first administrator, on the new organisation's host.
  const tenantOrigin = originFor(base, slug)
  const text = await latestMailText(request, adminEmail)
  const token = /#([A-Za-z0-9_-]{20,})/.exec(text)?.[1]
  expect(token, 'one-time token in the invitation link').toBeTruthy()
  expect(text).toContain(
    tenantOrigin
      .replace(/^http:/, 'https:')
      .replace(/:\d+$/, '')
      .split('//')[1]
      ?.split('.')[0] ?? slug,
  )
  const admin = await freshPage(browser, tenantOrigin)
  await admin.goto(`/invitation#${token ?? ''}`)
  await expect(admin.getByRole('heading', { level: 1, name: `Join Acme Bank ${run}` })).toBeVisible()
  const password = 'amber orchard compass seventeen e2e'
  await admin.getByLabel('Password').fill(password)
  await admin.getByRole('button', { name: 'Set password and continue' }).click()
  await expect(admin.getByRole('heading', { level: 1, name: 'Set up two-step sign-in' })).toBeVisible()
  const key = (await admin.locator('code').innerText()).replace(/\s/g, '')
  await enterCode(admin, key, 'Code from the app', 'Turn on two-step sign-in')
  await admin.getByLabel('I have saved my recovery codes').check()
  await admin.getByRole('button', { name: 'Continue' }).click()
  await expect(admin.getByRole('heading', { level: 1, name: `Welcome, ${adminName}` })).toBeVisible()

  // A second device signs in; the first one sees it and ends it.
  const other = await freshPage(browser, tenantOrigin)
  await other.goto('/login')
  await other.getByLabel('Email').fill(adminEmail)
  await other.getByLabel('Password').fill(password)
  await other.getByRole('button', { name: 'Sign in' }).click()
  await enterCode(other, key, 'Code', 'Continue')
  await expect(other.getByRole('heading', { level: 1, name: `Welcome, ${adminName}` })).toBeVisible()

  await admin.goto('/me/security')
  const devices = admin.getByRole('table', { name: /Signed-in devices/ })
  await expect(devices.getByRole('row')).toHaveCount(3) // header + two sessions
  await assertNoA11yViolations(admin)
  await devices
    .getByRole('row')
    .filter({ hasNot: admin.getByText('This device') })
    .getByRole('button', { name: 'Sign out' })
    .click()
  await expect(admin.getByText('The device was signed out.')).toBeVisible()
  await other.reload()
  await expect(other).toHaveURL(/\/login/)

  // Shorten the confirmation window to a minute, let it run out, then a sensitive action asks for a code.
  await admin.goto('/admin/security')
  const stepUp = admin.getByLabel('A confirmation lasts (minutes)')
  await stepUp.fill('1')
  await admin.getByRole('button', { name: 'Save' }).click()
  await expect(admin.getByText('Saved.')).toBeVisible()
  await admin.waitForTimeout(65_000)
  await admin.goto('/me/security')
  await admin.getByRole('button', { name: 'New recovery codes' }).click()
  const confirm = admin.getByRole('dialog', { name: 'Confirm it is you' })
  await expect(confirm).toBeVisible()
  // In the dialog: the page has other fields whose labels contain "Code".
  const codes = admin.getByRole('heading', { name: 'Your new recovery codes' })
  for (const offset of [0, 1]) {
    await confirm.getByLabel('Code', { exact: true }).fill(totp(key, offset))
    await confirm.getByRole('button', { name: 'Confirm' }).click()
    const shown = await codes
      .waitFor({ timeout: 5_000 })
      .then(() => true)
      .catch(() => false)
    if (shown) break
  }
  await expect(codes).toBeVisible()

  // The operator suspends the organisation: its host shows the notice; resuming brings sign-in back.
  await operator.reload()
  await operator.getByRole('button', { name: 'Suspend' }).click()
  await operator
    .getByRole('alertdialog', { name: 'Suspend this organisation?' })
    .getByRole('button', { name: 'Suspend' })
    .click()
  await expect(operator.getByText('Organisation suspended.')).toBeVisible()
  await admin.goto('/')
  await expect(admin.getByRole('heading', { name: 'This organisation is suspended' })).toBeVisible()
  await assertNoA11yViolations(admin)

  await operator.getByRole('button', { name: 'Resume' }).click()
  await expect(operator.getByText('Organisation resumed.')).toBeVisible()
  await admin.goto('/')
  await expect(admin).toHaveURL(/\/login/) // suspension ended the session
})
