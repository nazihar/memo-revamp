import type { Browser, Page } from '@playwright/test'
import { docx } from '../docx'
import { expect, test } from '../fixtures'
import { chooseMemoType } from '../memo-type'
import { storageStateFor, type SignedInRole } from '../roles'

/**
 * The Phase 3 E02 journey (test plan T8): an initiator names a co-author, attaches a PDF, previews it and classifies it
 * above the co-author's clearance; the co-author sees the draft, may add to it and sees the attachment only as
 * restricted; and two versions of the document are compared. On the smoke stack there is no
 * text service, so the comparison says the text is not available; the nightly stack with ONLYOFFICE and Tika marks the
 * changes.
 */
async function as(browser: Browser, role: SignedInRole): Promise<Page> {
  const context = await browser.newContext({ storageState: storageStateFor(role) })
  return context.newPage()
}

/** A one-page PDF, written by hand like the DOCX helper. */
function pdf(): Buffer {
  const body = [
    '%PDF-1.4',
    '1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj',
    '2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj',
    '3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >> endobj',
  ]
  let text = ''
  const offsets: number[] = []
  for (const line of body) {
    offsets.push(text.length)
    text += `${line}\n`
  }
  const xref = text.length
  text += `xref\n0 4\n0000000000 65535 f \n${offsets
    .slice(1)
    .map((o) => `${String(o).padStart(10, '0')} 00000 n \n`)
    .join('')}trailer << /Size 4 /Root 1 0 R >>\nstartxref\n${String(xref)}\n%%EOF\n`
  return Buffer.from(text, 'latin1')
}

test.describe('as the initiator', () => {
  test.use({ role: 'user' })

  test('names a co-author, attaches and previews a PDF, and compares two versions of the document', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(120_000)
    const subject = `E2E attachments ${String(Date.now())}`
    await app.visit('/memos/new')
    await chooseMemoType(page, 'General memo')
    await page.getByLabel('Subject').fill(subject)
    await page.getByRole('button', { name: 'Create draft' }).click()
    await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible()
    const memoUrl = page.url()

    // The document, twice.
    for (const [label, text, version] of [
      ['Upload the document', 'Branch refurbishment, budget 2 million', /^Version 1 ·/],
      ['Upload a new version', 'Branch refurbishment, budget 3 million', /^Version 2 ·/],
    ] as const) {
      await page.getByLabel(label).setInputFiles({
        name: 'refurbishment.docx',
        mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        buffer: docx(text),
      })
      await expect(page.getByText(version)).toBeVisible()
    }

    // A co-author.
    const coauthors = page.getByRole('region', { name: 'Co-authors' })
    await coauthors.getByRole('combobox', { name: 'Add a co-author' }).fill('Head of Cr')
    await page.getByRole('option', { name: /Demo Head of Credit/ }).click()
    await expect(coauthors.getByRole('listitem').filter({ hasText: 'Demo Head of Credit' })).toBeVisible()

    // An attachment, previewed.
    const attachments = page.getByRole('region', { name: 'Attachments' })
    await attachments
      .getByLabel('Add an attachment')
      .setInputFiles({ name: 'quotation.pdf', mimeType: 'application/pdf', buffer: pdf() })
    await expect(attachments.getByRole('listitem').filter({ hasText: 'quotation.pdf' })).toBeVisible()
    await attachments.getByRole('button', { name: 'Preview quotation.pdf' }).click()
    const preview = page.getByRole('dialog', { name: 'quotation.pdf' })
    await expect(preview.getByText('Page 1 of 1')).toBeVisible()
    await preview.getByRole('button', { name: 'Close' }).first().click()
    // Classified above the memo (Public) at Internal, which only the initiator is cleared for in the demo.
    const classify = attachments.getByRole('combobox', { name: 'Classification of quotation.pdf' })
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === 'PATCH' && r.url().includes('/attachments/') && r.ok()),
      classify.selectOption({ label: 'Internal' }),
    ])
    await app.checkA11y()

    // Comparing the two versions of the document.
    await page.getByRole('button', { name: 'Compare versions' }).click()
    const compare = page.getByRole('dialog', { name: 'Compare versions' })
    await expect(
      compare.getByText(/^(\d+ changes?|No changes|The text of this version is not available yet)/).first(),
    ).toBeVisible()
    await compare.getByRole('button', { name: 'Close' }).first().click()

    // The co-author sees the draft and may add to it, but not submit it.
    const coauthor = await as(browser, 'headCredit')
    await coauthor.goto(memoUrl)
    await expect(coauthor.getByRole('heading', { level: 1, name: subject })).toBeVisible()
    await expect(coauthor.getByText(/You are a co-author/)).toBeVisible()
    const theirs = coauthor.getByRole('region', { name: 'Attachments' })
    await expect(theirs.getByLabel('Add an attachment')).toBeVisible()
    // Not cleared for Internal: the attachment shows only that it exists.
    await expect(theirs.getByText('Restricted attachment')).toBeVisible()
    await expect(theirs.getByText('quotation.pdf')).toHaveCount(0)
    await expect(
      coauthor.getByRole('toolbar', { name: 'Actions' }).getByRole('button', { name: 'Submit' }),
    ).toHaveCount(0)
  })
})
