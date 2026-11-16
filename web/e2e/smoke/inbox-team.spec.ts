import type { Browser, Page } from '@playwright/test'
import { confirmIfAsked, demoSecret } from '../auth'
import { docx } from '../docx'
import { expect, test } from '../fixtures'
import { chooseMemoType } from '../memo-type'
import { storageStateFor, type SignedInRole } from '../roles'

/**
 * The team view and bulk actions (Phase 3 E05 test plan T8, flow I): the head of credit starts a memo for Demo User, who
 * sits in Retail Credit under them; the head finds Demo User's task in the Team tab, by due time, and reassigns it to
 * the head of finance with a reason and a step-up; the head of finance then has it. Then a memo goes to the credit desk
 * (a queue of the head of credit and the director): the head claims and releases it in bulk from My tasks, with a
 * result for the selection. Decisions are never offered in bulk. Each run writes new memos.
 */
async function as(browser: Browser, role: SignedInRole): Promise<Page> {
  const context = await browser.newContext({ storageState: storageStateFor(role) })
  return context.newPage()
}

function toolbar(page: Page) {
  return page.getByRole('toolbar', { name: 'Actions' })
}

/** Creates a draft of [type] with its document, then routes and submits it; returns the memo's address. */
async function sendMemo(page: Page, subject: string, type: string, approver?: { search: string; name: RegExp }) {
  await page.goto('/memos/new')
  await chooseMemoType(page, type)
  await page.getByLabel('Subject').fill(subject)
  await page.getByRole('button', { name: 'Create draft' }).click()
  await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible()
  const url = page.url()
  await page.getByLabel('Upload the document').setInputFiles({
    name: 'memo.docx',
    mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    buffer: docx(subject),
  })
  await expect(page.getByText(/^Version 1 ·/)).toBeVisible()
  if (approver) {
    await page.getByLabel('Approver', { exact: true }).fill(approver.search)
    await page.getByRole('option', { name: approver.name }).click()
    await page.getByRole('button', { name: 'Add approver' }).click()
    await page.getByRole('button', { name: 'Save approvers' }).click()
    await expect(page.getByText('Approvers saved.')).toBeVisible()
  }
  await toolbar(page).getByRole('button', { name: 'Submit' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Submit' }).click()
  await expect(page.getByRole('dialog', { name: 'Confirm it is you' }).or(page.getByText('Done.'))).toBeVisible()
  await confirmIfAsked(page, demoSecret('MEMO_DEMO_TOTP_SECRET'))
  await expect(page.getByText('Done.')).toBeVisible()
  return url
}

test.describe('as the head of credit', () => {
  test.use({ role: 'headCredit' })

  test("finds a team member's task in the Team tab by due time and reassigns it", async ({ app, page, browser }) => {
    test.setTimeout(180_000)
    const subject = `E2E team ${String(Date.now())}`
    await sendMemo(page, subject, 'General memo', { search: 'Demo Us', name: /Demo User/ })

    await app.visit('/inbox?tab=team')
    await expect(page.getByRole('tab', { name: 'Team', selected: true })).toBeVisible()
    const row = page.getByRole('listitem').filter({ has: page.getByRole('link', { name: subject }) })
    await expect(row).toBeVisible()
    // The holder (a General memo's route step has no name or due time).
    await expect(row.getByText('Demo User', { exact: true })).toBeVisible()
    await app.checkA11y()

    await row.getByRole('checkbox', { name: /^Select / }).check()
    await page.getByRole('button', { name: 'Reassign…' }).click()
    const dialog = page.getByRole('dialog', { name: 'Reassign the selected tasks' })
    await dialog.getByLabel('New person').fill('Head of Fin')
    await dialog.getByRole('option', { name: /Demo Head of Finance/ }).click()
    await dialog.getByLabel('Reason').fill('Demo User is on leave this week')
    await dialog.getByRole('button', { name: 'Reassign' }).click()
    // Step-up is asked unless the head confirmed it was them in the last 30 minutes.
    await expect(
      page.getByRole('dialog', { name: 'Confirm it is you' }).or(page.getByText('1 reassigned')),
    ).toBeVisible()
    await confirmIfAsked(page, demoSecret('MEMO_DEMO_TOTP_SECRET'))
    await expect(page.getByText('1 reassigned')).toBeVisible()
    await expect(page.getByRole('link', { name: subject })).toHaveCount(0)

    const finance = await as(browser, 'headFinance')
    await finance.goto('/inbox?sort=due')
    await expect(finance.getByRole('link', { name: subject })).toBeVisible()
    await finance.context().close()
  })
})

test.describe('as the initiator', () => {
  test.use({ role: 'user' })

  test('the head of credit claims and releases a credit-desk task in bulk', async ({ page, browser }) => {
    test.setTimeout(180_000)
    const subject = `E2E bulk ${String(Date.now())}`
    await sendMemo(page, subject, 'Credit under authority')

    const head = await as(browser, 'headCredit')
    await head.goto('/inbox')
    const row = head.getByRole('listitem').filter({ has: head.getByRole('link', { name: subject }) })
    await expect(row).toBeVisible()
    await row.getByRole('checkbox', { name: /^Select / }).check()
    const bar = head.getByRole('toolbar', { name: 'Actions on the selected items' })
    // Watch, Claim and Release, never a decision.
    await expect(bar.getByRole('button', { name: /Approve|Reject|Recommend/ })).toHaveCount(0)
    await bar.getByRole('button', { name: 'Claim' }).click()
    await expect(head.getByText('1 claimed')).toBeVisible()

    // The head now holds the desk task alone; it stays in their inbox and goes back to the desk in bulk.
    await head.reload()
    await row.getByRole('checkbox', { name: /^Select / }).check()
    await bar.getByRole('button', { name: 'Release' }).click()
    await expect(head.getByText('1 released')).toBeVisible()
    await head.context().close()
  })
})
