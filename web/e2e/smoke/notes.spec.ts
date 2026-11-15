import { expect, test } from '../fixtures'
import { chooseMemoType } from '../memo-type'
import { storageStateFor } from '../roles'

/**
 * The note sheet (Phase 3 E03 T7, ADR-0049): a note with bold text and a mention of someone who cannot see the memo
 * yet; "share and mention" shares it with a reason and posts the note; the person mentioned reads it. The fixture fails
 * the test on any Content-Security-Policy violation, so the editor runs under the real CSP.
 */
test.describe('as the initiator', () => {
  test.use({ role: 'user' })

  test('writes a formatted note, shares the memo with the person mentioned, and they read it', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(90_000)
    const subject = `E2E notes ${String(Date.now())}`
    await app.visit('/memos/new')
    await chooseMemoType(page, 'General memo')
    await page.getByLabel('Subject').fill(subject)
    await page.getByRole('button', { name: 'Create draft' }).click()
    await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible()
    const memoUrl = page.url()

    await page.getByRole('button', { name: 'Write a note' }).click()
    const dialog = page.getByRole('dialog', { name: 'Write a note' })
    const editor = dialog.getByRole('textbox', { name: 'Note' })
    await editor.click()
    await page.keyboard.press('Control+B')
    await page.keyboard.type('Rent schedule')
    await page.keyboard.press('Control+B')
    await page.keyboard.type(' attached, please check, ')
    await page.keyboard.type('@Head of Cr')
    await dialog.getByRole('option', { name: /Demo Head of Credit/ }).click()
    await app.checkA11y()

    await dialog.getByRole('button', { name: 'Post note' }).click()
    await expect(dialog.getByText(/cannot see this memo\. Share it with them\?/)).toBeVisible()
    await dialog.getByLabel('Reason').fill('Review of the rent schedule')
    await dialog.getByRole('button', { name: 'Share and mention' }).click()
    await expect(dialog).toBeHidden()

    const sheet = page.getByRole('region', { name: 'Note sheet' })
    await expect(sheet.getByText('Rent schedule', { exact: true })).toBeVisible()
    await expect(sheet.locator('strong', { hasText: 'Rent schedule' })).toBeVisible()
    await expect(sheet.getByText('@Demo Head of Credit')).toBeVisible()

    // The person mentioned now reads the memo and the note.
    const context = await browser.newContext({ storageState: storageStateFor('headCredit') })
    const theirs = await context.newPage()
    await theirs.goto(memoUrl)
    await expect(theirs.getByRole('heading', { level: 1, name: subject })).toBeVisible()
    await expect(
      theirs.getByRole('region', { name: 'Note sheet' }).getByText('Rent schedule', { exact: true }),
    ).toBeVisible()
    await context.close()
  })
})
