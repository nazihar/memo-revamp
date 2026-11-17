import type { Locator } from '@playwright/test'
import { confirmIfAsked, demoSecret } from '../auth'
import { docx } from '../docx'
import { expect, test } from '../fixtures'
import { chooseMemoType } from '../memo-type'
import { pageAs } from '../roles'

/**
 * Access and copies (Phase 3 E03 test plan, T9; ADR-0050): the initiator copies the memo to the head of finance after
 * approval and shares it with the branch administrator until tomorrow; once approved, the copy reaches the head of
 * finance, who reads it for information, and the receipt shows they opened it. The auditor reads it as a unit reader for
 * the Credit Division (the demo seed). Each run writes a new memo.
 */
/** Picks a person in the people picker labelled [field] inside [scope] (a dialog: the page has other pickers). */
async function pick(scope: Locator, field: string, search: string, name: RegExp) {
  await scope.getByRole('combobox', { name: field, exact: true }).fill(search)
  await scope.getByRole('option', { name }).click()
}

function tomorrow(): string {
  const date = new Date(Date.now() + 24 * 60 * 60 * 1000)
  return date.toISOString().slice(0, 10)
}

test.describe('as the initiator', () => {
  test.use({ role: 'user' })

  test('copies after approval reach the head of finance, whose receipt shows; a share has an end date', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(180_000)
    const subject = `E2E copies ${String(Date.now())}`
    await app.visit('/memos/new')
    await chooseMemoType(page, 'General memo')
    await page.getByLabel('Subject').fill(subject)
    await page.getByRole('button', { name: 'Create draft' }).click()
    await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible()
    const memoUrl = page.url()

    await page.getByLabel('Upload the document').setInputFiles({
      name: 'memo.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      buffer: docx('A memo copied to finance'),
    })
    await expect(page.getByText(/^Version 1 ·/)).toBeVisible()
    await page.getByLabel('Approver', { exact: true }).fill('Head of Cr')
    await page.getByRole('option', { name: /Demo Head of Credit/ }).click()
    await page.getByRole('button', { name: 'Add approver' }).click()
    await page.getByRole('button', { name: 'Save approvers' }).click()
    await expect(page.getByText('Approvers saved.')).toBeVisible()

    // Copy to the head of finance, after approval (the default).
    const access = page.getByRole('region', { name: 'People with access' })
    await access.getByRole('button', { name: 'Copy to…' }).click()
    const copyTo = page.getByRole('dialog', { name: 'Copy to' })
    await pick(copyTo, 'Add a person', 'Head of Fin', /Demo Head of Finance/)
    await expect(copyTo.getByRole('combobox', { name: 'When to send to Demo Head of Finance' })).toHaveValue(
      'AFTER_APPROVAL',
    )
    await app.checkA11y()
    await copyTo.getByRole('button', { name: 'Save' }).click()
    await expect(copyTo).toBeHidden()
    await expect(access.getByRole('button', { name: '0 people · 0 read' })).toBeVisible()

    // Share with the branch administrator until tomorrow, with a reason.
    await access.getByRole('button', { name: 'Share' }).click()
    const share = page.getByRole('dialog', { name: 'Share this memo' })
    await pick(share, 'Person', 'Branch Ad', /Demo Branch Admin/)
    await share.getByLabel('Reason').fill('Check of the branch premises')
    await share.getByLabel(/^Until/).fill(tomorrow())
    await share.getByRole('button', { name: 'Share' }).click()
    await expect(share).toBeHidden()
    await expect(access.getByText('Demo Branch Admin')).toBeVisible()
    await expect(access.getByText(/^until /)).toBeVisible()

    await page.getByRole('toolbar', { name: 'Actions' }).getByRole('button', { name: 'Submit' }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Submit' }).click()
    await expect(page.getByText('Done.')).toBeVisible()

    const head = await pageAs(browser, 'headCredit')
    await head.goto(memoUrl)
    await head.getByRole('toolbar', { name: 'Actions' }).getByRole('button', { name: 'Approve' }).click()
    await head.getByRole('dialog').getByRole('button', { name: 'Approve' }).click()
    await confirmIfAsked(head, demoSecret('MEMO_DEMO_TOTP_SECRET'), head.getByText('Done.'))
    await expect(head.getByText('Done.')).toBeVisible()

    // The copy reached the head of finance, who reads it for information only.
    const finance = await pageAs(browser, 'headFinance')
    await finance.goto(memoUrl)
    await expect(finance.getByRole('heading', { level: 1, name: subject })).toBeVisible()
    await expect(finance.getByRole('note').filter({ hasText: 'You received a copy of this memo' })).toBeVisible()
    await expect(finance.getByRole('toolbar', { name: 'Actions' })).toBeHidden()

    // Their first open is the receipt.
    await page.goto(memoUrl)
    await access.getByRole('button', { name: '1 person · 1 read' }).click()
    const receipts = page.getByRole('dialog', { name: 'Copies and receipts' })
    await expect(receipts.getByText('Demo Head of Finance')).toBeVisible()
    await expect(receipts.getByText(/\(v1\)/).first()).toBeVisible()

    // The auditor reads memos raised in the Credit Division (the demo's unit reader); the initiator sits in Retail Credit.
    const auditor = await pageAs(browser, 'auditor')
    await expect(async () => {
      await auditor.goto(memoUrl)
      await expect(auditor.getByRole('heading', { level: 1, name: subject })).toBeVisible({ timeout: 3000 })
    }).toPass({ timeout: 60_000 })
  })
})
