import type { Browser, Page } from '@playwright/test'
import { expect, test } from '../fixtures'
import { chooseMemoType } from '../memo-type'
import { storageStateFor, type SignedInRole } from '../roles'

/**
 * Search (Phase 3 E05 test plan T8, ADR-0054): a note's words find the memo for its initiator, through the box in the
 * top bar, and a person who may not read the memo finds nothing; the search is saved and reopened from the Saved
 * searches page. An attachment's words find it once its text is extracted, which needs the text extractor (Tika): it
 * runs with the documents stack (nightly and on dev), so that test skips itself on the PR stack, as the editing journey
 * does. Each run writes a new memo with words of its own.
 */
async function as(browser: Browser, role: SignedInRole): Promise<Page> {
  const context = await browser.newContext({ storageState: storageStateFor(role) })
  return context.newPage()
}

/** Searches from the top-bar box: the words go in the page's address fragment, never in a request's URL. */
async function searchFor(page: Page, words: string) {
  const box = page.getByRole('combobox', { name: 'Search memos' })
  await box.fill(words)
  await box.press('Enter')
  await expect(page).toHaveURL((url) => url.pathname === '/search' && url.hash.startsWith('#q='))
}

test.describe('as the initiator', () => {
  test.use({ role: 'user' })

  test("a note's words find the memo; someone without access finds nothing; the search is saved and reopened", async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(120_000)
    const stamp = String(Date.now())
    const subject = `E2E search ${stamp}`
    // A word no other memo has: letters and this run's digits form one word.
    const word = `quokka${stamp}`
    await app.visit('/memos/new')
    await chooseMemoType(page, 'General memo')
    await page.getByLabel('Subject').fill(subject)
    await page.getByRole('button', { name: 'Create draft' }).click()
    await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible()

    await page.getByRole('button', { name: 'Write a note' }).click()
    const dialog = page.getByRole('dialog', { name: 'Write a note' })
    await dialog.getByRole('textbox', { name: 'Note' }).click()
    await page.keyboard.type(`The ${word} schedule is attached.`)
    await dialog.getByRole('button', { name: 'Post note' }).click()
    await expect(dialog).toBeHidden()

    // The note is indexed with the change: found at once, with where the words were found.
    const requests: string[] = []
    page.on('request', (request) => requests.push(request.url()))
    await searchFor(page, word)
    const result = page.getByRole('listitem').filter({ has: page.getByRole('link', { name: subject }) })
    await expect(result).toBeVisible()
    await expect(result.getByText(/^Found in: note by /)).toBeVisible()
    await expect(result.locator('mark', { hasText: word })).toBeVisible()
    expect(
      requests.filter((url) => url.includes(word)),
      'search words in a request URL',
    ).toEqual([])
    await app.checkA11y()

    await page.getByRole('button', { name: 'Save this search' }).click()
    const save = page.getByRole('dialog', { name: 'Save this search' })
    await save.getByLabel('Name').fill(`Quokka ${stamp}`)
    await save.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByText('Search saved.')).toBeVisible()

    await app.visit('/search/saved')
    await page.getByRole('link', { name: `Quokka ${stamp}` }).click()
    await expect(page.getByRole('link', { name: subject })).toBeVisible()

    // The head of finance takes no part in the memo and may not read it: nothing is found.
    const other = await as(browser, 'headFinance')
    await other.goto('/inbox')
    await searchFor(other, word)
    await expect(other.getByText(/^No memos match\./)).toBeVisible()
    await expect(other.getByRole('link', { name: subject })).toHaveCount(0)
    await other.context().close()
  })

  test("an attachment's words find the memo once its text is extracted", async ({ app, page }) => {
    // The text extractor runs with the documents stack, as the document server does (ci/e2e-stack.sh E2E_DOCUMENTS).
    const editing = await page.request.get('/api/v1/files/editing')
    test.skip(!editing.ok() || !((await editing.json()) as { enabled: boolean }).enabled, 'no documents stack here')
    test.setTimeout(180_000)
    const stamp = String(Date.now())
    const subject = `E2E search attachment ${stamp}`
    const word = `wombat${stamp}`
    await app.visit('/memos/new')
    await chooseMemoType(page, 'General memo')
    await page.getByLabel('Subject').fill(subject)
    await page.getByRole('button', { name: 'Create draft' }).click()
    await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible()
    const attachments = page.getByRole('region', { name: 'Attachments' })
    await attachments.getByLabel('Add an attachment').setInputFiles({
      name: 'rent-schedule.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from(`Rent schedule for floors 3 and 4.\nThe ${word} clause applies from January.\n`),
    })
    await expect(attachments.getByRole('listitem').filter({ hasText: 'rent-schedule.txt' })).toBeVisible()

    // Extraction and indexing run in the background: search again (from another page, so it is asked anew) until found.
    await expect(async () => {
      await app.visit('/inbox')
      await searchFor(page, word)
      await expect(page.getByRole('link', { name: subject })).toBeVisible({ timeout: 3000 })
    }).toPass({ timeout: 120_000, intervals: [3000] })
    await expect(page.getByText('Found in: attachment “rent-schedule.txt”')).toBeVisible()
  })
})
