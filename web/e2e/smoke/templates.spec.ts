import { confirmIfAsked, demoSecret } from '../auth'
import { docx } from '../docx'
import { expect, test } from '../fixtures'
import { chooseMemoType } from '../memo-type'
import { pageAs } from '../roles'

/**
 * Templates (Phase 3 E03 test plan, T9; ADR-0048): an administrator gives a new memo type a Word template with tags, sees
 * the tags it uses, previews it and submits; a second administrator publishes it; a user's new memo of the type starts
 * with the filled template as its document. Each run builds its own type.
 */
test.describe('as a tenant admin', () => {
  test.use({ role: 'tenantAdmin' })

  test('a template is uploaded, previewed and published, and a new memo starts from it', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(180_000)
    const stamp = String(Date.now())
    const typeName = `E2E template ${stamp}`

    await app.visit('/admin/memo-types')
    await page.getByRole('button', { name: 'New memo type' }).click()
    const create = page.getByRole('dialog')
    await create.getByLabel('Name', { exact: true }).fill(typeName)
    await create.getByLabel('Code', { exact: true }).fill(`L${stamp.slice(-8)}`)
    await create.getByRole('button', { name: 'Create draft' }).click()
    await expect(page.getByRole('heading', { level: 2, name: /Version 1 · Draft/ })).toBeVisible()
    const typeUrl = page.url()

    const block = page.getByRole('region', { name: 'Template' })
    await block.getByLabel('Upload a template').setInputFiles({
      name: 'letterhead.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      buffer: docx('Subject: {{memo.subject}}. From {{initiator.name}}.'),
    })
    await expect(block.getByText('letterhead.docx', { exact: false })).toBeVisible()
    await expect(block.getByText('{{memo.subject}} {{initiator.name}}')).toBeVisible()
    await expect(block.getByText('Preview it before submitting the version')).toBeVisible()
    await app.checkA11y()

    await block.getByRole('button', { name: 'Preview as PDF' }).click()
    const preview = page.getByRole('dialog', { name: 'Template preview with sample values' })
    await expect(preview).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(block.getByText('Previewed', { exact: true })).toBeVisible()

    await page.getByRole('button', { name: 'Submit for review' }).click()
    await expect(page.getByRole('heading', { level: 2, name: /Version 1 · In review/ })).toBeVisible()

    const reviewer = await pageAs(browser, 'reviewer')
    await reviewer.goto(typeUrl)
    // A reviewer opens the version on its changes; the template is on the Settings tab.
    await reviewer.getByRole('tab', { name: 'Settings' }).click()
    await expect(reviewer.getByRole('region', { name: 'Template' }).getByText(/letterhead\.docx/)).toBeVisible()
    await reviewer.getByRole('button', { name: 'Approve and publish' }).click()
    await confirmIfAsked(
      reviewer,
      demoSecret('MEMO_DEMO_TOTP_SECRET'),
      reviewer.getByRole('heading', { level: 2, name: /Version 1 · Published/ }),
    )
    await expect(reviewer.getByRole('heading', { level: 2, name: /Version 1 · Published/ })).toBeVisible()

    const author = await pageAs(browser, 'user')
    await author.goto('/memos/new')
    await chooseMemoType(author, typeName)
    await expect(author.getByText(`The document starts from the ${typeName} template`, { exact: false })).toBeVisible()
    await author.getByLabel('Subject').fill(`E2E from a template ${stamp}`)
    await author.getByRole('button', { name: 'Create draft' }).click()
    await expect(author.getByRole('heading', { level: 1, name: `E2E from a template ${stamp}` })).toBeVisible()
    await expect(author.getByText(/^Version 1 ·/)).toBeVisible()
  })
})
