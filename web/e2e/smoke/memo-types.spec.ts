import type { Browser, Page } from '@playwright/test'
import { confirmIfAsked, demoSecret } from '../auth'
import { docx } from '../docx'
import { expect, test } from '../fixtures'
import { chooseMemoType } from '../memo-type'
import { storageStateFor, type SignedInRole } from '../roles'

/**
 * The Phase 3 E01 journey (test plan, T8): an administrator builds a memo type with a required amount and a `TA-` number
 * format on its own series; a second administrator publishes it with a step-up; a user raises a memo of it, whose
 * details read back formatted, and submitting it gives the first number of the series. Each run uses a new series.
 */
async function as(browser: Browser, role: SignedInRole): Promise<Page> {
  const context = await browser.newContext({ storageState: storageStateFor(role) })
  return context.newPage()
}

test.describe('as a tenant admin', () => {
  test.use({ role: 'tenantAdmin' })

  test('builds a memo type with details and a number format; once approved, a memo of it is numbered on submit', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(180_000)
    const stamp = String(Date.now())
    const typeName = `E2E typed ${stamp}`
    const series = `e2e-${stamp}`

    await app.visit('/admin/memo-types')
    await page.getByRole('button', { name: 'New memo type' }).click()
    const create = page.getByRole('dialog')
    await create.getByLabel('Name', { exact: true }).fill(typeName)
    await create.getByLabel('Code', { exact: true }).fill(`T${stamp.slice(-8)}`)
    await create.getByRole('button', { name: 'Create draft' }).click()
    await expect(page.getByRole('heading', { level: 2, name: /Version 1 · Draft/ })).toBeVisible()
    const typeUrl = page.url()

    // A required amount, used in workflow rules; the preview shows it as authors will.
    await page.getByRole('tab', { name: 'Form' }).click()
    await page.getByRole('button', { name: 'Add field' }).click()
    const field = page.getByRole('dialog', { name: 'Add field' })
    await field.getByRole('textbox', { name: /Label/ }).fill('Facility amount')
    await field.getByLabel('Type', { exact: true }).selectOption({ label: 'Amount' })
    await field.getByRole('checkbox', { name: 'Required' }).check()
    await field.getByRole('button', { name: 'Keep field' }).click()
    await expect(page.getByRole('textbox', { name: /Facility amount/ })).toBeVisible()

    // TA-<year>-00001 on a series of its own.
    await page.getByRole('tab', { name: 'Numbering' }).click()
    await page.getByRole('textbox', { name: 'Text', exact: true }).first().fill('TA-')
    await page.getByLabel('Series', { exact: true }).fill(series)
    await expect(page.getByText(/^TA-\d{4}-00001$/).first()).toBeVisible()
    await app.checkA11y()

    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByText('Saved.')).toBeVisible()
    await page.getByRole('button', { name: 'Submit for review' }).click()
    await expect(page.getByRole('heading', { level: 2, name: /Version 1 · In review/ })).toBeVisible()

    // The second administrator sees the changes and publishes it (maker-checker, ADR-0043).
    const reviewer = await as(browser, 'reviewer')
    await reviewer.goto(typeUrl)
    await expect(reviewer.getByText(/Facility amount/).first()).toBeVisible()
    await reviewer.getByRole('button', { name: 'Approve and publish' }).click()
    await confirmIfAsked(reviewer, demoSecret('MEMO_DEMO_TOTP_SECRET'))
    await expect(reviewer.getByRole('heading', { level: 2, name: /Version 1 · Published/ })).toBeVisible()

    // A user raises one: the type first, then its details.
    const author = await as(browser, 'user')
    await author.goto('/memos/new')
    await chooseMemoType(author, typeName)
    await author.getByLabel('Subject').fill(`E2E typed memo ${stamp}`)
    await author.getByRole('textbox', { name: /Facility amount/ }).fill('1250000')
    await author.getByRole('button', { name: 'Create draft' }).click()
    await expect(author.getByRole('heading', { level: 1, name: `E2E typed memo ${stamp}` })).toBeVisible()
    const memoUrl = author.url()
    await expect(author.getByText(/^Unnumbered — numbered when submitted/)).toBeVisible()
    const details = author.getByRole('region', { name: 'Details' })
    await expect(details.getByText(/BDT\s1,250,000\.00/)).toBeVisible()

    await author.getByLabel('Upload the document').setInputFiles({
      name: 'memo.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      buffer: docx('A memo of a type built in the browser'),
    })
    await expect(author.getByText(/^Version 1 ·/)).toBeVisible()
    await author.getByLabel('Approver', { exact: true }).fill('Director')
    await author.getByRole('option', { name: /Demo Director/ }).click()
    await author.getByRole('button', { name: 'Add approver' }).click()
    await author.getByRole('button', { name: 'Save approvers' }).click()
    await expect(author.getByText('Approvers saved.')).toBeVisible()

    await author.getByRole('toolbar', { name: 'Actions' }).getByRole('button', { name: 'Submit' }).click()
    await author.getByRole('dialog').getByRole('button', { name: 'Submit' }).click()
    await expect(author.getByText('Done.')).toBeVisible()
    await author.goto(memoUrl)
    // The first number of the new series.
    await expect(author.getByText(/TA-\d{4}-00001/).first()).toBeVisible()
  })
})
