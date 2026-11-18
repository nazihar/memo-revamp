import type { Page } from '@playwright/test'
import { confirmIfAsked, demoSecret } from '../auth'
import { docx } from '../docx'
import { expect, test } from '../fixtures'
import { chooseMemoType } from '../memo-type'
import { pageAs } from '../roles'

/**
 * The E02 journey (test plan T5, flow M): a credit proposal of BDT 12 million, above the demo's committee threshold of
 * BDT 10 million, goes to the credit desk; after the head of credit's recommendation the route waits for the Management
 * Credit Committee (ADR-0058) and the memo says so. The company secretary accepts the paper from the committee's
 * Submissions tab with the keyboard, and the initiator sees it accepted on the memo's committee panel. Recording the
 * committee's decision has no screen until E06; `ForumSubmissionsIT` and `DemoWorkflowsIT` cover the resumed route.
 */
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
  await confirmIfAsked(page, demoSecret('MEMO_DEMO_TOTP_SECRET'), page.getByText('Done.'))
  await expect(page.getByText('Done.')).toBeVisible()
}

/** A calendar day [days] from today as `YYYY-MM-DD`. */
function dayFromNow(days: number): string {
  const d = new Date(Date.now() + days * 86_400_000)
  return `${String(d.getFullYear())}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

test.describe('as the initiator', () => {
  test.use({ role: 'user' })

  test('a large credit proposal waits for the credit committee, whose secretary accepts it', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(180_000)
    const subject = `E2E committee ${String(Date.now())}`

    // A credit proposal above the committee threshold.
    await app.visit('/memos/new')
    await chooseMemoType(page, 'Credit proposal')
    await page.getByLabel('Subject').fill(subject)
    await page.getByRole('textbox', { name: /Customer/ }).fill('Delta Textiles Ltd')
    await page.getByRole('textbox', { name: /Facility amount/ }).fill('12000000')
    await page.getByRole('radio', { name: 'Term loan' }).check()
    await page.getByRole('button', { name: 'Create draft' }).click()
    await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible()
    const memoUrl = page.url()
    await page.getByLabel('Upload the document').setInputFiles({
      name: 'proposal.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      buffer: docx('Term loan of BDT 12 million'),
    })
    await expect(page.getByText(/^Version 1 ·/)).toBeVisible()
    await act(page, memoUrl, 'Submit')

    // The credit desk recommends; the route then waits for the committee instead of going to the authority.
    const head = await pageAs(browser, 'headCredit')
    await act(head, memoUrl, 'Recommend')
    await page.goto(memoUrl)
    await expect(status(page, 'In review')).toBeVisible()
    await expect(page.getByText('With the Management Credit Committee', { exact: true }).first()).toBeVisible()
    const panel = page.getByRole('region', { name: 'Committees' })
    await expect(panel.getByText('Waiting for triage')).toBeVisible()
    await expect(panel.getByText('Sent to the Management Credit Committee', { exact: false })).toBeVisible()
    await app.checkA11y()

    // The secretary opens the committee's papers and accepts this one for a meeting, by keyboard.
    const secretary = await pageAs(browser, 'secretary')
    await secretary.goto('/committees')
    await secretary.getByRole('link', { name: 'Management Credit Committee' }).click()
    await secretary.getByRole('tab', { name: /^Submissions/ }).press('Enter')
    // The Triage menu button is named after the paper: "Triage CR/… <subject>" (a substring match).
    const triage = secretary.getByRole('button', { name: subject })
    await triage.focus()
    await triage.press('Enter')
    await secretary.getByRole('menuitem', { name: 'Accept…' }).press('Enter')
    const dialog = secretary.getByRole('dialog', { name: 'Accept the paper' })
    await expect(dialog.getByText(/The memo keeps waiting for the committee’s decision/)).toBeVisible()
    await dialog.getByLabel(/Target meeting date/).fill(dayFromNow(28))
    await dialog.getByRole('button', { name: 'Accept' }).press('Enter')
    await expect(secretary.getByText('Paper accepted.')).toBeVisible()
    await expect(triage).toHaveCount(0)

    // The initiator sees where the paper stands.
    await page.goto(memoUrl)
    await expect(panel.getByText('Accepted', { exact: true })).toBeVisible()
    await expect(panel.getByText(/Accepted for the meeting of/)).toBeVisible()
    await expect(panel.getByText('by Demo Company Secretary')).toBeVisible()
    await expect(panel.getByText('Next: decided at a meeting')).toBeVisible()
    await expect(status(page, 'In review')).toBeVisible()
    await app.checkA11y()
  })
})
