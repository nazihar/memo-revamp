import type { Page } from '@playwright/test'
import { confirmIfAsked, demoSecret } from '../auth'
import { docx } from '../docx'
import { expect, test } from '../fixtures'
import { chooseMemoType } from '../memo-type'
import { pageAs } from '../roles'

/**
 * The final record (Phase 3 E04 test plan, T7; ADR-0053): once the memo is approved, a background job makes its PDF/A
 * record; the initiator downloads it. The demo admin, who keeps records ("Records keeper" in the demo seed) and is given
 * the memo through a share, makes version 2 with a reason and a step-up; version 1 stays downloadable. Each run writes a
 * new memo. The PDF/A validation itself is proven by the backend's tests (veraPDF); here the file is a PDF.
 */
/** Reloads [page] until the Final record box offers [link] (the job runs in the background). */
async function waitForRecord(page: Page, url: string, link: string) {
  await expect(async () => {
    await page.goto(url)
    await expect(page.getByRole('region', { name: 'Final record' }).getByRole('link', { name: link })).toBeVisible({
      timeout: 3000,
    })
  }).toPass({ timeout: 120_000, intervals: [3000] })
}

test.describe('as the initiator', () => {
  test.use({ role: 'user' })

  test('an approved memo gets its final record; a keeper makes version 2 with a reason', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(300_000)
    const subject = `E2E record ${String(Date.now())}`
    await app.visit('/memos/new')
    await chooseMemoType(page, 'General memo')
    await page.getByLabel('Subject').fill(subject)
    await page.getByRole('button', { name: 'Create draft' }).click()
    await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible()
    const memoUrl = page.url()
    await page.getByLabel('Upload the document').setInputFiles({
      name: 'memo.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      buffer: docx('A memo with a final record'),
    })
    await expect(page.getByText(/^Version 1 ·/)).toBeVisible()
    await page.getByLabel('Approver', { exact: true }).fill('Head of Cr')
    await page.getByRole('option', { name: /Demo Head of Credit/ }).click()
    await page.getByRole('button', { name: 'Add approver' }).click()
    await page.getByRole('button', { name: 'Save approvers' }).click()
    await expect(page.getByText('Approvers saved.')).toBeVisible()
    await page.getByRole('toolbar', { name: 'Actions' }).getByRole('button', { name: 'Submit' }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Submit' }).click()
    await expect(page.getByText('Done.')).toBeVisible()

    const head = await pageAs(browser, 'headCredit')
    await head.goto(memoUrl)
    await head.getByRole('toolbar', { name: 'Actions' }).getByRole('button', { name: 'Approve' }).click()
    await head.getByRole('dialog').getByRole('button', { name: 'Approve' }).click()
    await confirmIfAsked(head, demoSecret('MEMO_DEMO_TOTP_SECRET'), head.getByText('Done.'))
    await expect(head.getByText('Done.')).toBeVisible()

    // The record is made in the background, then offered for download.
    await waitForRecord(page, memoUrl, 'Download')
    const box = page.getByRole('region', { name: 'Final record' })
    await expect(box.getByText(/^Version 1 ·/)).toBeVisible()
    await app.checkA11y()
    const href = await box.getByRole('link', { name: 'Download' }).getAttribute('href')
    const pdf = await page.request.get(String(href))
    expect(pdf.status()).toBe(200)
    expect((await pdf.body()).subarray(0, 5).toString()).toBe('%PDF-')

    // The demo admin keeps records; the initiator shares the memo with them so they can read it.
    await box.page().getByRole('region', { name: 'People with access' }).getByRole('button', { name: 'Share' }).click()
    const share = page.getByRole('dialog', { name: 'Share this memo' })
    await share.getByRole('combobox', { name: 'Person', exact: true }).fill('Demo Admin')
    await share.getByRole('option', { name: /^Demo Admin/ }).click()
    await share.getByLabel('Reason').fill('Records check')
    await share.getByRole('button', { name: 'Share' }).click()
    await expect(share).toBeHidden()

    const keeper = await pageAs(browser, 'tenantAdmin')
    await waitForRecord(keeper, memoUrl, 'Download')
    await keeper
      .getByRole('region', { name: 'Final record' })
      .getByRole('button', { name: 'Make a new version' })
      .click()
    const dialog = keeper.getByRole('dialog', { name: 'Make a new version of the final record' })
    await dialog.getByLabel(/Reason/).fill('Checking the record after a converter upgrade.')
    await dialog.getByRole('button', { name: 'Make version 2' }).click()
    await confirmIfAsked(keeper, demoSecret('MEMO_DEMO_TOTP_SECRET'), async () => !(await dialog.isVisible()))
    await expect(dialog).toBeHidden()

    // Version 2 is made; version 1 stays, listed with the reason and downloadable.
    await waitForRecord(keeper, memoUrl, 'Download version 1')
    const kept = keeper.getByRole('region', { name: 'Final record' })
    await expect(kept.getByText(/^Version 2 ·/)).toBeVisible()
    await expect(kept.getByText(/Checking the record after a converter upgrade\./)).toBeVisible()
  })
})
