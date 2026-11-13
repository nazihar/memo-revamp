import type { Browser, Page } from '@playwright/test'
import { confirmIfAsked, demoSecret } from '../auth'
import { docx } from '../docx'
import { expect, test } from '../fixtures'
import { storageStateFor, type SignedInRole } from '../roles'

/**
 * The E03 journey (test plan T11): a memo of the demo "Credit facility" type, whose definition names every step
 * (credit review, then a branch on the classification, then the director), goes without a route; the reviewer asks the
 * director for an opinion, recommends, the director sends it back to the review, and after a second recommendation
 * approves it. Parallel steps and migrations are covered by the integration tests (`DefinitionRuntimeIT`, `MigrationIT`).
 * E05 T8: the credit review has a deadline (a working day), shown to the reviewer; the business calendar admin adds
 * and removes a holiday and previews a deadline.
 */
async function as(browser: Browser, role: SignedInRole): Promise<Page> {
  const context = await browser.newContext({ storageState: storageStateFor(role) })
  return context.newPage()
}

function status(page: Page, text: string) {
  return page.locator('[data-tone]', { hasText: text })
}

/** Opens [button]'s dialog on the memo, fills what it asks for and confirms. */
async function act(
  page: Page,
  memoUrl: string,
  button: string,
  fill: { remark?: string; text?: { label: RegExp; value: string }; person?: { search: string; name: RegExp } } = {},
) {
  await page.goto(memoUrl)
  await page.getByRole('toolbar', { name: 'Actions' }).getByRole('button', { name: button }).click()
  const dialog = page.getByRole('dialog')
  if (fill.person) {
    await dialog.getByRole('combobox').fill(fill.person.search)
    await page.getByRole('option', { name: fill.person.name }).click()
  }
  if (fill.remark) await dialog.getByRole('textbox', { name: /Remark/ }).fill(fill.remark)
  if (fill.text) await dialog.getByRole('textbox', { name: fill.text.label }).fill(fill.text.value)
  await dialog.getByRole('button', { name: button }).click()
  await confirmIfAsked(page, demoSecret('MEMO_DEMO_TOTP_SECRET'))
  await expect(page.getByText('Done.')).toBeVisible()
}

test.describe('as the initiator', () => {
  test.use({ role: 'user' })

  test('a fixed workflow: opinion, recommendation, send back and approval', async ({ app, page, browser }) => {
    const subject = `E2E credit ${String(Date.now())}`
    await app.visit('/memos/new')
    await page.getByLabel('Subject').fill(subject)
    await page.getByLabel('Memo type').selectOption({ label: 'Credit facility' })
    await page.getByRole('button', { name: 'Create draft' }).click()
    await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible()
    const memoUrl = page.url()
    // The definition names every step: there is no route to build.
    await expect(page.getByRole('button', { name: 'Save approvers' })).toHaveCount(0)

    await page.getByLabel('Upload the document').setInputFiles({
      name: 'credit.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      buffer: docx('Credit facility for Example Ltd'),
    })
    await expect(page.getByText(/^Version 1 ·/)).toBeVisible()
    await act(page, memoUrl, 'Submit')
    await expect(status(page, 'In review')).toBeVisible()

    const head = await as(browser, 'headCredit')
    await head.goto(memoUrl)
    await expect(head.getByText('Step: Credit review')).toBeVisible()
    // The step's SLA (one working day) gives the task a due time.
    await expect(head.getByText(/^Due .*\d/)).toBeVisible()
    await act(head, memoUrl, 'Ask for opinions', {
      person: { search: 'Director', name: /Demo Director/ },
      remark: 'Is the collateral enough?',
    })

    const director = await as(browser, 'director')
    await act(director, memoUrl, 'Give your opinion', {
      text: { label: /Your opinion/, value: 'Enough, with the guarantee.' },
    })
    await act(head, memoUrl, 'Recommend')

    await act(director, memoUrl, 'Send back', { remark: 'Add the guarantor’s statement.' })
    await act(head, memoUrl, 'Recommend')
    await act(director, memoUrl, 'Approve')

    await page.goto(memoUrl)
    await expect(status(page, 'Approved')).toBeVisible()
    await expect(page.getByText('Sent back to an earlier step')).toBeVisible()
    await expect(page.getByText('Gave an opinion')).toBeVisible()
    await app.checkA11y()
  })
})

test.describe('as a tenant admin', () => {
  test.use({ role: 'tenantAdmin' })

  test('the workflows screen lists the demo definition and opens its published version', async ({ app, page }) => {
    await app.visit('/admin/workflows')
    await expect(page.getByRole('heading', { level: 1, name: 'Workflows' })).toBeVisible()
    await page.getByRole('link', { name: 'Credit facility' }).click()
    // Version 1 on a fresh stack; a stack seeded before E05 has the deadlines in version 2.
    await expect(page.getByRole('heading', { level: 2, name: /Version \d+ · Published/ })).toBeVisible()
    // E06 T1: the diagram draws under the real CSP; the Steps view says the same in words.
    const diagram = page.getByRole('group', { name: 'Workflow diagram' })
    await expect(diagram.getByLabel(/^Step 1 of 5, Review, Credit review\./)).toBeVisible()
    await expect(diagram.getByLabel('End', { exact: true })).toBeVisible()
    await expect(diagram.getByLabel('Tell the author leads to End')).toBeVisible()
    await app.checkA11y()
    await page.getByRole('tab', { name: 'Steps' }).click()
    const steps = page.getByRole('list', { name: 'Steps, in the order they are written' })
    await expect(steps.getByRole('heading', { name: '1. Credit review' })).toBeVisible()
    await expect(steps.getByText(/^Due in 1 working day/)).toBeVisible()
    await page.getByRole('tab', { name: 'JSON' }).click()
    await expect(page.getByRole('textbox', { name: /Definition/ })).toHaveAttribute('readonly')
    await app.checkA11y()
  })

  test('the business calendar adds and removes a holiday and previews a deadline', async ({ app, page }) => {
    await app.visit('/admin/workflow/calendar')
    await expect(page.getByRole('heading', { level: 1, name: 'Business calendar' })).toBeVisible()
    await expect(page.getByText(/^Time zone: /).first()).toBeVisible()

    // A holiday on the 28th of next month, removed again so that other journeys keep their due times.
    const now = new Date()
    const next = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 28))
    const day = next.toISOString().slice(0, 10)
    const note = `E2E holiday ${String(Date.now())}`
    await page.getByRole('button', { name: 'Next month' }).click()
    await page.getByLabel('First day').fill(day)
    await page.getByLabel('Note', { exact: true }).fill(note)
    await page.getByRole('button', { name: 'Add', exact: true }).click()
    const line = page.getByRole('listitem').filter({ hasText: note })
    await expect(line).toContainText('Closed')
    await app.checkA11y()
    await line.getByRole('button', { name: /^Remove / }).click()
    await expect(line).toHaveCount(0)

    await page.getByRole('button', { name: 'Calculate' }).click()
    await expect(page.getByText(/^Due .*\d/)).toBeVisible()
  })
})
