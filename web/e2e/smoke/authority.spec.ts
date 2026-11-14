import type { Browser, Page } from '@playwright/test'
import { confirmIfAsked, demoSecret } from '../auth'
import { docx } from '../docx'
import { expect, test } from '../fixtures'
import { chooseMemoType } from '../memo-type'
import { storageStateFor, type SignedInRole } from '../roles'

/**
 * The E04 journey (test plan T10): a memo of the demo "Credit under authority" type goes to the credit desk, a queue of
 * the head of credit and the director. The head of credit claims it (the director then sees who holds it), releases
 * it and recommends; the delegation of authority then asks its groups in order: the managing director (the director's
 * seat), then the chief financial officer. Re-resolution after a holder change, recusal and substitution are covered
 * by the integration tests (`StaffChangeIT`, `SeparationOfDutiesIT`, `SubstitutionIT`).
 */
async function as(browser: Browser, role: SignedInRole): Promise<Page> {
  const context = await browser.newContext({ storageState: storageStateFor(role) })
  return context.newPage()
}

function status(page: Page, text: string) {
  return page.locator('[data-tone]', { hasText: text })
}

function toolbar(page: Page) {
  return page.getByRole('toolbar', { name: 'Actions' })
}

/** Opens [button]'s dialog on the memo, confirms it (with a step-up if asked). */
async function act(page: Page, memoUrl: string, button: string) {
  await page.goto(memoUrl)
  await toolbar(page).getByRole('button', { name: button }).click()
  await page.getByRole('dialog').getByRole('button', { name: button }).click()
  await confirmIfAsked(page, demoSecret('MEMO_DEMO_TOTP_SECRET'))
  await expect(page.getByText('Done.')).toBeVisible()
}

test.describe('as the initiator', () => {
  test.use({ role: 'user' })

  test('a shared desk claimed and released, then the authority group by group', async ({ app, page, browser }) => {
    const subject = `E2E authority ${String(Date.now())}`
    await app.visit('/memos/new')
    await page.getByLabel('Subject').fill(subject)
    await chooseMemoType(page, 'Credit under authority')
    await page.getByRole('button', { name: 'Create draft' }).click()
    await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible()
    const memoUrl = page.url()
    await page.getByLabel('Upload the document').setInputFiles({
      name: 'facility.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      buffer: docx('Working capital facility'),
    })
    await expect(page.getByText(/^Version 1 ·/)).toBeVisible()
    await act(page, memoUrl, 'Submit')
    await expect(status(page, 'In review')).toBeVisible()

    const head = await as(browser, 'headCredit')
    await head.goto(memoUrl)
    await expect(head.getByText(/Others can act on this too/)).toBeVisible()
    await toolbar(head).getByRole('button', { name: 'Claim' }).click()
    await expect(head.getByText(/You claimed this task/)).toBeVisible()

    const director = await as(browser, 'director')
    await director.goto(memoUrl)
    await expect(director.getByText('Claimed by Demo Head of Credit')).toBeVisible()

    await toolbar(head).getByRole('button', { name: 'Release' }).click()
    await expect(head.getByText(/Others can act on this too/)).toBeVisible()
    await act(head, memoUrl, 'Recommend')

    // The authority: the managing director's seat first, then the chief financial officer's.
    await act(director, memoUrl, 'Approve')
    const finance = await as(browser, 'headFinance')
    await act(finance, memoUrl, 'Approve')

    await page.goto(memoUrl)
    await expect(status(page, 'Approved')).toBeVisible()
    await expect(page.getByText('Took the task')).toBeVisible()
    await app.checkA11y()
  })

  /**
   * Phase 3 E01 T8: a credit proposal's amount picks the DoA band. Under BDT 5 million the chief financial officer
   * decides alone; from 5 million the managing director decides first.
   */
  test('a credit proposal’s amount picks its band of the delegation of authority', async ({ app, page, browser }) => {
    test.setTimeout(180_000)
    const stamp = String(Date.now())

    async function raise(amount: string): Promise<string> {
      await app.visit('/memos/new')
      await chooseMemoType(page, 'Credit proposal')
      await page.getByLabel('Subject').fill(`E2E band ${amount} ${stamp}`)
      await page.getByRole('textbox', { name: /Customer/ }).fill('Demo Traders Ltd')
      await page.getByRole('textbox', { name: /Facility amount/ }).fill(amount)
      await page.getByRole('radio', { name: 'Working capital' }).check()
      await page.getByRole('button', { name: 'Create draft' }).click()
      await expect(page.getByRole('heading', { level: 1, name: `E2E band ${amount} ${stamp}` })).toBeVisible()
      const memoUrl = page.url()
      await page.getByLabel('Upload the document').setInputFiles({
        name: 'proposal.docx',
        mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        buffer: docx(`Credit proposal for ${amount}`),
      })
      await expect(page.getByText(/^Version 1 ·/)).toBeVisible()
      await act(page, memoUrl, 'Submit')
      // Credit proposals are numbered on submit in their own series.
      await expect(page.getByText(/CR\/\d{4}-\d{2}\/\d{4}/).first()).toBeVisible()
      return memoUrl
    }

    const head = await as(browser, 'headCredit')
    const director = await as(browser, 'director')
    const finance = await as(browser, 'headFinance')

    // Under the band: after the desk, only the chief financial officer.
    const small = await raise('2000000')
    await act(head, small, 'Recommend')
    await act(finance, small, 'Approve')
    await page.goto(small)
    await expect(status(page, 'Approved')).toBeVisible()

    // At the band: the managing director first; the chief financial officer has nothing to do until then.
    const large = await raise('8000000')
    await act(head, large, 'Recommend')
    await finance.goto(large)
    await expect(toolbar(finance).getByRole('button', { name: 'Approve' })).toHaveCount(0)
    await act(director, large, 'Approve')
    await act(finance, large, 'Approve')
    await page.goto(large)
    await expect(status(page, 'Approved')).toBeVisible()
  })
})

test.describe('as a tenant admin', () => {
  test.use({ role: 'tenantAdmin' })

  test('the delegation of authority shows the published version and its rule', async ({ app, page }) => {
    await app.visit('/admin/workflow/doa')
    await expect(page.getByRole('heading', { level: 1, name: 'Delegation of authority' })).toBeVisible()
    // Phase 3 E01 T8: the credit-proposal amount bands come first, then the rule for every memo.
    await expect(page.getByText('Rule 1 · Credit proposals of BDT 5 million or more')).toBeVisible()
    await expect(page.getByText('Rule 3 · Credit memos')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Save draft' })).toHaveCount(0)
    await app.checkA11y()
  })
})
