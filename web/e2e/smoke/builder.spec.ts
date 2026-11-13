import type { Browser, Locator, Page } from '@playwright/test'
import { confirmIfAsked, demoSecret } from '../auth'
import { docx } from '../docx'
import { expect, test } from '../fixtures'
import { storageStateFor, type SignedInRole } from '../roles'

/**
 * The Phase 2 exit journey (E06 T9, overview §5): a tenant administrator builds a workflow in the builder's Steps view
 * — a review, a branch on the classification, a parallel committee, and a director with a deadline and an escalation —
 * simulates both branches, submits it; a second administrator approves it; a memo type uses it; and a memo routes
 * through it to Approved. Built with the keyboard-path view, not by dragging.
 */
async function as(browser: Browser, role: SignedInRole): Promise<Page> {
  const context = await browser.newContext({ storageState: storageStateFor(role) })
  return context.newPage()
}

async function act(page: Page, memoUrl: string, button: string) {
  await page.goto(memoUrl)
  await page.getByRole('toolbar', { name: 'Actions' }).getByRole('button', { name: button }).click()
  await page.getByRole('dialog').getByRole('button', { name: button }).click()
  await confirmIfAsked(page, demoSecret('MEMO_DEMO_TOTP_SECRET'))
  await expect(page.getByText('Done.')).toBeVisible()
}

/** Picks [option] in the people picker [combobox] after typing [search]. */
async function pick(page: Page, combobox: Locator, search: string, option: RegExp) {
  await combobox.fill(search)
  await page.getByRole('option', { name: option }).click()
}

test.describe('as a tenant admin', () => {
  test.use({ role: 'tenantAdmin' })

  test('builds, simulates, gets approval for and publishes a workflow, and a memo routes through it', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(240_000)
    const stamp = String(Date.now())
    const key = `e2e-${stamp}`
    const name = `E2E builder ${stamp}`

    // Create the definition: a draft with the starter route step.
    await app.visit('/admin/workflows')
    await page.getByRole('button', { name: 'New workflow' }).click()
    const create = page.getByRole('dialog', { name: 'New workflow' })
    await create.getByLabel('Key').fill(key)
    await create.getByLabel('Name', { exact: true }).fill(name)
    await create.getByRole('button', { name: 'Create draft' }).click()
    await expect(page.getByRole('heading', { level: 1, name })).toBeVisible()
    const definitionUrl = page.url()

    // Build it in the Steps view.
    await page.getByRole('tab', { name: 'Steps' }).click()
    const steps = page.getByRole('list', { name: 'Steps, in the order they are written' })
    const step = (heading: RegExp) =>
      steps.getByRole('listitem').filter({ has: page.getByRole('heading', { name: heading }) })
    const addFirst = async (type: string) => {
      await page.getByRole('combobox', { name: 'Add a step at the start' }).selectOption({ label: type })
      await page.getByRole('button', { name: 'Add', exact: true }).click()
    }

    await step(/Approvers/)
      .getByRole('button', { name: 'Change' })
      .click()
    await step(/Approvers/)
      .getByRole('button', { name: 'Remove this step' })
      .click()
    await page.getByRole('alertdialog').getByRole('button', { name: 'Remove' }).click()

    // The director, with a deadline of two working days; the supervisor is told a day late.
    await addFirst('Approval')
    await step(/^1\. Approval$/)
      .getByRole('button', { name: 'Change' })
      .click()
    let panel = step(/^1\. Approval$/)
    await panel.getByLabel('Name', { exact: true }).fill('Director')
    panel = step(/^1\. Director$/)
    await panel.getByLabel('Goes to', { exact: true }).selectOption({ label: 'A person' })
    await pick(page, panel.getByRole('combobox', { name: 'Person' }), 'Director', /Demo Director/)
    await panel.getByRole('checkbox', { name: 'This step has a deadline' }).check()
    await panel.getByLabel('Amount', { exact: true }).fill('2')
    await panel.getByLabel('When it is late', { exact: true }).selectOption({ label: 'Tell the holder’s supervisor' })
    await panel.getByRole('button', { name: 'Close' }).click()

    // The credit committee: the director and the head of finance, all of them.
    await addFirst('Parallel decision')
    await step(/^1\. Parallel decision$/)
      .getByRole('button', { name: 'Change' })
      .click()
    await step(/^1\. Parallel decision$/)
      .getByLabel('Name', { exact: true })
      .fill('Credit committee')
    panel = step(/^1\. Credit committee$/)
    await panel.getByRole('button', { name: 'Add someone' }).click()
    await panel.getByRole('button', { name: 'Add someone' }).click()
    for (const [n, who, option] of [
      [1, 'Director', /Demo Director/],
      [2, 'Finance', /Demo Head of Finance/],
    ] as const) {
      const group = panel.getByRole('group', { name: `Person or group ${String(n)}` })
      await group.getByLabel('Goes to', { exact: true }).selectOption({ label: 'A person' })
      await pick(page, group.getByRole('combobox', { name: 'Person' }), who, option)
    }
    await panel.getByLabel('Leads to', { exact: true }).selectOption({ label: 'End' })
    await panel.getByRole('button', { name: 'Close' }).click()

    // The branch: classification rank 2 or more goes to the committee, anything else to the director.
    await addFirst('Branch')
    await step(/^1\. Branch$/)
      .getByRole('button', { name: 'Change' })
      .click()
    await step(/^1\. Branch$/)
      .getByLabel('Name', { exact: true })
      .fill('By classification')
    panel = step(/^1\. By classification$/)
    await panel.getByLabel('Attribute', { exact: true }).selectOption({ label: 'Classification rank' })
    await panel.getByLabel('Comparison', { exact: true }).selectOption({ label: 'is at least' })
    await panel.getByLabel('Value', { exact: true }).fill('2')
    await panel
      .getByLabel('If condition 1 holds, leads to', { exact: true })
      .selectOption({ label: 'Credit committee' })
    await panel.getByLabel('Otherwise', { exact: true }).selectOption({ label: 'Director' })
    await panel.getByRole('button', { name: 'Close' }).click()

    // The review by the head of credit, first.
    await addFirst('Review')
    await step(/^1\. Review$/)
      .getByRole('button', { name: 'Change' })
      .click()
    await step(/^1\. Review$/)
      .getByLabel('Name', { exact: true })
      .fill('Credit review')
    panel = step(/^1\. Credit review$/)
    await panel.getByLabel('Goes to', { exact: true }).selectOption({ label: 'A person' })
    await pick(page, panel.getByRole('combobox', { name: 'Person' }), 'Credit', /Demo Head of Credit/)
    await panel.getByRole('button', { name: 'Close' }).click()

    // Checked as it was built; the diagram draws it.
    await expect(page.getByRole('status').filter({ hasText: 'No problems found.' })).toBeVisible()
    await expect(step(/^4\. Director$/).getByText(/^Due in 2 working days/)).toBeVisible()
    await page.getByRole('button', { name: 'Save draft' }).click()
    await expect(page.getByText('Saved.')).toBeVisible()
    await page.getByRole('tab', { name: 'Diagram' }).click()
    await expect(
      page.getByRole('group', { name: 'Workflow diagram' }).getByLabel(/^Step 1 of 4, Review, Credit review\./),
    ).toBeVisible()
    await app.checkA11y()

    // Simulate both branches: nobody in the demo is cleared above rank 0, so the committee stops.
    const simulation = page.getByRole('region', { name: 'Simulate' })
    await pick(page, simulation.getByRole('combobox', { name: /Started by/ }), 'Demo User', /Demo User/)
    await simulation.getByLabel('Classification rank').fill('0')
    await simulation.getByRole('button', { name: 'Simulate' }).click()
    await expect(simulation.getByText('It would be approved.')).toBeVisible()
    const path = simulation.getByRole('list', { name: 'Steps it would reach, in order' })
    await expect(path.getByText('Demo Director')).toBeVisible()
    await simulation.getByLabel('Classification rank').fill('2')
    await simulation.getByRole('button', { name: 'Simulate' }).click()
    await expect(simulation.getByText(/^It would stop and wait for an administrator/)).toBeVisible()
    await expect(path.getByText('Not cleared for this classification.').first()).toBeVisible()

    await page.getByRole('toolbar', { name: 'Versions' }).getByRole('button', { name: 'Submit for review' }).click()
    await expect(page.getByRole('heading', { level: 2, name: /Version 1 · In review/ })).toBeVisible()

    // A second administrator approves and publishes it (maker-checker, with a step-up).
    const reviewer = await as(browser, 'reviewer')
    await reviewer.goto(definitionUrl)
    await reviewer
      .getByRole('toolbar', { name: 'Versions' })
      .getByRole('button', { name: 'Approve and publish' })
      .click()
    await reviewer
      .getByRole('dialog', { name: 'Publish this version?' })
      .getByRole('button', { name: 'Approve and publish' })
      .click()
    await confirmIfAsked(reviewer, demoSecret('MEMO_DEMO_TOTP_SECRET'))
    await expect(reviewer.getByRole('heading', { level: 2, name: /Version 1 · Published/ })).toBeVisible()

    // A memo type runs on it.
    const typeName = `E2E built ${stamp}`
    await app.visit('/admin/memo-types')
    await page.getByRole('button', { name: 'Add memo type' }).click()
    const typeDialog = page.getByRole('dialog')
    await typeDialog.getByLabel('Name', { exact: true }).fill(typeName)
    await typeDialog.getByLabel('Workflow').selectOption({ label: name })
    await typeDialog.getByLabel('Approvers').selectOption({ label: 'The workflow names every step' })
    await typeDialog.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByRole('cell', { name: typeName })).toBeVisible()

    // A memo of that type goes review → branch (rank 0) → director → Approved.
    const author = await as(browser, 'user')
    await author.goto('/memos/new')
    await author.getByLabel('Subject').fill(`E2E built workflow ${stamp}`)
    await author.getByLabel('Memo type').selectOption({ label: typeName })
    await author.getByRole('button', { name: 'Create draft' }).click()
    await expect(author.getByRole('heading', { level: 1, name: `E2E built workflow ${stamp}` })).toBeVisible()
    const memoUrl = author.url()
    await author.getByLabel('Upload the document').setInputFiles({
      name: 'memo.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      buffer: docx('A memo routed by a built workflow'),
    })
    await expect(author.getByText(/^Version 1 ·/)).toBeVisible()
    await act(author, memoUrl, 'Submit')
    await act(await as(browser, 'headCredit'), memoUrl, 'Recommend')
    const director = await as(browser, 'director')
    await director.goto(memoUrl)
    await expect(director.getByText(/^Due /)).toBeVisible()
    await act(director, memoUrl, 'Approve')
    await author.goto(memoUrl)
    await expect(author.locator('[data-tone]', { hasText: 'Approved' })).toBeVisible()
  })
})
