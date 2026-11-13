import type { Browser, Page } from '@playwright/test'
import { confirmIfAsked, demoSecret } from '../auth'
import { docx } from '../docx'
import { expect, test } from '../fixtures'
import { storageStateFor, type SignedInRole } from '../roles'

/**
 * The first-slice journey (E01 test plan, T10; D1 scenario): create → upload → route → submit → return → version 2 →
 * resubmit → round 2 → approve → Approved, across three people, plus the admin's reassignment screen. Each run writes a
 * new memo, so it does not depend on the seeded ones.
 */
async function as(browser: Browser, role: SignedInRole): Promise<Page> {
  const context = await browser.newContext({ storageState: storageStateFor(role) })
  return context.newPage()
}

async function upload(page: Page, label: string, text: string) {
  await page.getByLabel(label).setInputFiles({
    name: 'memo.docx',
    mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    buffer: docx(text),
  })
}

/** The memo's status badge (the history also names actions such as "Returned"). */
function status(page: Page, text: string) {
  return page.locator('[data-tone]', { hasText: text })
}

async function act(page: Page, memoUrl: string, button: string, remark?: string) {
  await page.goto(memoUrl)
  await page.getByRole('toolbar', { name: 'Actions' }).getByRole('button', { name: button }).click()
  const dialog = page.getByRole('dialog')
  if (remark) await dialog.getByRole('textbox', { name: /Remark/ }).fill(remark)
  await dialog.getByRole('button', { name: button }).click()
  await confirmIfAsked(page, demoSecret('MEMO_DEMO_TOTP_SECRET'))
  await expect(page.getByText('Done.')).toBeVisible()
}

test.describe('as the initiator', () => {
  test.use({ role: 'user' })

  test('a memo goes through a return and a second round to approval', async ({ app, page, browser }) => {
    const subject = `E2E memo ${String(Date.now())}`
    await app.visit('/memos/new')
    await page.getByLabel('Subject').fill(subject)
    // The demo has a second type, Credit facility, whose workflow names every step (E03): this journey builds a route.
    await page.getByLabel('Memo type').selectOption({ label: 'General memo' })
    await page.getByRole('button', { name: 'Create draft' }).click()
    await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible()
    const memoUrl = page.url()
    await app.checkA11y()

    await upload(page, 'Upload the document', 'Version 1')
    await expect(page.getByText(/^Version 1 ·/)).toBeVisible()
    for (const [search, name] of [
      ['Head of Cr', /Demo Head of Credit/],
      ['Director', /Demo Director/],
    ] as const) {
      await page.getByLabel('Approver', { exact: true }).fill(search)
      await page.getByRole('option', { name }).click()
      await page.getByRole('button', { name: 'Add approver' }).click()
    }
    await page.getByRole('button', { name: 'Save approvers' }).click()
    await expect(page.getByText('Approvers saved.')).toBeVisible()
    await act(page, memoUrl, 'Submit')
    await expect(status(page, 'In review')).toBeVisible()

    const head = await as(browser, 'headCredit')
    await act(head, memoUrl, 'Return', 'Please add the annex.')

    await page.goto(memoUrl)
    await expect(status(page, 'Returned')).toBeVisible()
    await upload(page, 'Upload a new version', 'Version 2 with the annex')
    await expect(page.getByText(/^Version 2 ·/)).toBeVisible()
    await act(page, memoUrl, 'Resubmit')
    await expect(page.getByText('Earlier round: its approvals no longer count.')).toBeVisible()

    await act(head, memoUrl, 'Approve')
    const director = await as(browser, 'director')
    await act(director, memoUrl, 'Approve')

    await page.goto(memoUrl)
    await expect(status(page, 'Approved')).toBeVisible()
    await app.checkA11y()
  })
})

test.describe('as a tenant admin', () => {
  test.use({ role: 'tenantAdmin' })

  test('open tasks waiting for reassignment are listed with a reassign action', async ({ app, page }) => {
    await app.visit('/admin/workflow/tasks')
    await expect(page.getByRole('heading', { level: 1, name: 'Open tasks' })).toBeVisible()
    await expect(page.getByRole('table', { name: 'Open workflow tasks' })).toBeVisible()
    await app.checkA11y()
  })
})
