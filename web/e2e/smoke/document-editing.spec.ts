import { docx } from '../docx'
import { expect, test } from '../fixtures'

/**
 * In-browser editing (E02 test plan, T5): the initiator opens the memo's document in ONLYOFFICE under `/office/`,
 * types, goes back, and the save lands as version 2. Needs the document server (the `documents` profile or the full
 * local stack); the PR stack has none, so the test skips itself there and runs nightly and on dev.
 */
test.describe('as the initiator', () => {
  test.use({ role: 'user' })

  test('a document edited in the browser comes back as a new version', async ({ app, page }) => {
    const editing = await page.request.get('/api/v1/files/editing')
    test.skip(!editing.ok() || !((await editing.json()) as { enabled: boolean }).enabled, 'no document server here')
    test.setTimeout(180_000)

    const subject = `E2E edit ${String(Date.now())}`
    await app.visit('/memos/new')
    await page.getByLabel('Subject').fill(subject)
    await page.getByRole('button', { name: 'Create draft' }).click()
    await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible()
    await page.getByLabel('Upload the document').setInputFiles({
      name: 'memo.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      buffer: docx('Version 1'),
    })
    await expect(page.getByText(/^Version 1 ·/)).toBeVisible()

    await page.getByRole('link', { name: 'Edit in the browser' }).click()
    await expect(page.getByRole('heading', { level: 1, name: /^Editing/ })).toBeVisible()
    // The editor runs in a same-origin frame; its canvas takes keystrokes once the document has loaded.
    const editor = page.frameLocator('iframe[name="frameEditor"]')
    await expect(editor.locator('#id_viewer, #editor_sdk').first()).toBeVisible({ timeout: 90_000 })
    await editor.locator('#editor_sdk').click()
    await page.keyboard.type(' edited in the browser')
    // Typed text reaches the document server before closing.
    await page.waitForTimeout(3_000)

    await page.getByRole('button', { name: 'Back to the memo' }).click()
    await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText(/^Version 2 ·/)).toBeVisible()
  })
})
