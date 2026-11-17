import type { Page } from '@playwright/test'
import type { ImportBatchPage } from '../../src/api/generated/types.gen'
import { readFileSync } from 'node:fs'
import { confirmIfAsked, demoSecret } from '../auth'
import { docx } from '../docx'
import { expect, test } from '../fixtures'
import { folder, legacyDoc, pdf, xlsx } from '../legacy'
import { pageAs } from '../roles'

/**
 * The legacy import (Phase 3 E06 test plan T10, ADR-0055, flows L to O), as the demo admin, who holds `memo.import` and
 * is cleared to Confidential: a new batch, a workbook and a folder of originals (a .docx and a .pdf; a Word 97 .doc too
 * where the Tika server runs, since only it can type an OLE2 file: the documents stack, as `search.spec` gates
 * attachments), the mappings confirmed with one fixed by hand, the dry run, the import with a step-up, the report balanced
 * and downloaded, and the batch closed. A reader of the imported memo (Demo User, linked as its approver) then finds it
 * by its departed initiator's name with the Origin filter, and sees its banner, its originals and no final record. Each
 * run imports memos of its own (a new source code).
 */
test.describe('as the tenant administrator', () => {
  test.use({ role: 'tenantAdmin' })

  test('a legacy batch is imported, reported and closed; its memo is found by a departed name', async ({
    app,
    page,
    browser,
  }) => {
    test.setTimeout(300_000)
    const editing = await page.request.get('/api/v1/files/editing')
    const documents = editing.ok() && ((await editing.json()) as { enabled: boolean }).enabled
    const stamp = String(Date.now())
    const source = `E2E-${stamp}`
    const surname = `Haque${stamp}`
    const subject = `Legacy E2E ${stamp}: generators for the Khulna branch`
    const memos = [
      [
        `IMP/${stamp}/1`,
        'EXP',
        subject,
        'Approved',
        'Internal',
        'HO-FINANCE',
        '2019-03-14',
        '2019-03-20',
        '',
        `Selina ${surname}`,
        '',
      ],
    ]
    const files = [
      [`IMP/${stamp}/1`, '2019/1/memo.docx', 'MAIN', 'Memo', ''],
      [`IMP/${stamp}/1`, '2019/1/quotation.pdf', 'ATTACHMENT', 'Quotation', ''],
    ]
    const originals: Record<string, Buffer> = {
      '2019/1/memo.docx': docx(`Two standby generators for the Khulna branch (${stamp}).`),
      '2019/1/quotation.pdf': pdf(`Quotation for two generators, ${stamp}`),
      'Thumbs.db': Buffer.alloc(64),
    }
    if (documents) {
      const lease = `Legacy E2E ${stamp}: lease renewal`
      // Demo User, linked by email.
      memos.push([
        `IMP/${stamp}/2`,
        'EXP',
        lease,
        'Approved',
        'Internal',
        'HO-FINANCE',
        '2019-04-02',
        '2019-04-09',
        '',
        'Demo User',
        'user@demo.example',
      ])
      files.push([`IMP/${stamp}/2`, '2019/2/memo.doc', 'MAIN', 'Memo', ''])
      originals['2019/2/memo.doc'] = legacyDoc(`Lease renewal ${stamp}`)
    }
    const workbook = xlsx({
      Memos: [
        [
          'legacy_number',
          'memo_type',
          'subject',
          'status',
          'classification',
          'origin_unit',
          'raised_on',
          'decided_on',
          'closed_on',
          'initiator_name',
          'initiator_email',
        ],
        ...memos,
      ],
      Files: [['legacy_number', 'path', 'role', 'title', 'classification'], ...files],
      Approvals: [
        ['legacy_number', 'order', 'name', 'email', 'position', 'decision', 'decided_on', 'remark'],
        // Demo User, linked by email, becomes a participant and so a reader of the imported memo.
        [`IMP/${stamp}/1`, '1', 'Demo User', 'user@demo.example', 'Officer', 'Recommended', '2019-03-18', ''],
        [
          `IMP/${stamp}/1`,
          '2',
          'Demo Head of Finance',
          'head.finance@demo.example',
          'CFO',
          'Approved',
          '2019-03-20',
          'Within budget.',
        ],
      ],
    })
    const sent = Object.keys(originals).length - 1

    // A new batch (after abandoning any an earlier failed run left open: a tenant may have five).
    await abandonLeftovers(page)
    await app.visit('/admin/imports')
    await page.getByTestId('imports-new').click()
    const dialog = page.getByRole('dialog', { name: 'New import batch' })
    await dialog.getByLabel('Name').fill(`E2E import ${stamp}`)
    await dialog.getByLabel('Source code').fill(source)
    await dialog.getByRole('button', { name: 'Create' }).click()
    await expect(page.getByTestId('batch-state')).toHaveText('State: Open')

    // The workbook, then the folder (Thumbs.db is skipped by the browser).
    await page.getByLabel('Workbook', { exact: true }).setInputFiles({
      name: 'legacy-export.xlsx',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: workbook,
    })
    // Once the workbook is in, the page moves on to the originals by itself.
    await expect(page.getByTestId('step-metadata')).toContainText('Metadata (done)')
    await expect(page.getByTestId('step-originals')).toHaveAttribute('aria-current', 'step')
    await page.getByTestId('folder-input').setInputFiles(folder('legacy-export', originals))
    // The page moves on to the mappings once an original is in; the others are still sent, outside the page.
    const batchId = new URL(page.url()).pathname.split('/').pop() ?? ''
    await expect
      .poll(
        async () =>
          (
            (await (await page.request.get(`/api/v1/admin/imports/${batchId}`)).json()) as {
              originals: { received: number }
            }
          ).originals.received,
        { timeout: 60_000 },
      )
      .toBe(sent)
    await app.visit(`/admin/imports/${batchId}?step=originals`)
    await expect(page.getByTestId('originals-counts')).toContainText(`Received ${String(sent)} · waiting 0 · refused 0`)

    // A first dry run proposes the mappings; the unit has no proposal and is mapped by hand; then a second dry run.
    await dryRun(page)
    await page.getByTestId('step-mappings').click()
    // Each save moves the batch version and reloads the tabs, so the next one starts only once it has returned.
    const saved = (kind: string) =>
      page.waitForResponse((r) => r.request().method() === 'PUT' && r.url().endsWith(`/mappings/${kind}`))
    for (const kind of ['STATUS', 'TYPE', 'CLASSIFICATION', 'PERSON']) {
      await page.getByTestId(`mappings-tab-${kind}`).click()
      const response = saved(kind)
      await page.getByTestId(`confirm-all-${kind}`).click()
      expect((await response).status()).toBe(200)
      await expect(page.getByTestId(`confirm-all-${kind}`)).toBeDisabled()
    }
    await page.getByTestId('mappings-tab-UNIT').click()
    const unit = page.getByTestId('mapping-UNIT-HO-FINANCE')
    await unit.getByLabel('Ours for “HO-FINANCE”').selectOption({ label: 'Finance Division (FIN)' })
    await unit.getByLabel('Confirm “HO-FINANCE”').check()
    const unitSaved = saved('UNIT')
    await page.getByRole('button', { name: 'Save mappings' }).click()
    expect((await unitSaved).status()).toBe(200)
    await expect(page.getByTestId('mapping-error')).toHaveCount(0)
    await expect(unit.getByLabel('Confirm “HO-FINANCE”')).toBeChecked()
    await app.checkA11y()
    await dryRun(page)
    await expect(page.getByTestId('dry-run-totals')).toContainText(`${String(memos.length)} memos ready · 0 rejected`)

    // The import, with a step-up; then the verification and the report.
    await page.getByTestId('step-import').click()
    await page.getByTestId('start-import').click()
    await page.getByTestId('start-confirm').click()
    await confirmIfAsked(page, demoSecret('MEMO_DEMO_TOTP_SECRET'), page.getByTestId('import-progress'))
    await expect(page.getByTestId('batch-state')).toHaveText('State: Completed', { timeout: 180_000 })

    await page.getByTestId('step-report').click()
    const report = page.getByTestId('report')
    await expect(report).toContainText('Balanced')
    await expect(report).toContainText(`imported ${String(memos.length)} + rejected 0`)
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByTestId('report-part-SUMMARY').click(),
    ])
    const summary = await download.path()
    expect(readFileSync(summary).subarray(0, 5).toString('latin1')).toBe('%PDF-')
    const manifest = await page.request.get((await page.getByTestId('report-part-MANIFEST').getAttribute('href')) ?? '')
    expect(await manifest.text()).toContain('  2019/1/quotation.pdf')
    await app.checkA11y()

    await page.getByTestId('close-batch').click()
    const closing = page.getByRole('dialog', { name: 'Close this batch?' })
    await closing.getByLabel('Reason').fill('Journey checked')
    await closing.getByRole('button', { name: 'Close batch' }).click()
    await expect(page.getByTestId('batch-state')).toHaveText('State: Closed')

    // A reader (Demo User, linked as an approver) finds the memo by the name of its initiator, who left before the
    // move and never had an account here, with the Origin filter. The administrator does not read other people's memos.
    const reader = await pageAs(browser, 'user')
    await reader.goto('/inbox')
    const box = reader.getByRole('combobox', { name: 'Search memos' })
    await box.fill(surname)
    await box.press('Enter')
    await expect(reader).toHaveURL((url) => url.pathname === '/search')
    await reader.getByRole('radio', { name: 'Imported' }).check()
    await reader.getByRole('button', { name: 'Apply filters' }).click()
    const result = reader.getByRole('listitem').filter({ has: reader.getByRole('link', { name: subject }) })
    await expect(result).toBeVisible()
    await expect(result.getByText('Imported', { exact: true })).toBeVisible()

    await result.getByRole('link', { name: subject }).click()
    const memo = reader.getByTestId('imported-memo')
    await expect(memo).toBeVisible()
    await expect(memo.getByTestId('imported-badge')).toBeVisible()
    await expect(memo).toContainText('not verified by this system')
    await expect(memo).toContainText(`Selina ${surname}`)
    await expect(memo.getByTestId('legacy-original')).toHaveCount(2)
    await expect(memo).toContainText('No final record: for an imported memo the original documents are the record.')
    await reader.context().close()
  })
})

/** Runs a dry run from its step and waits until it is done (the page polls while it runs). */
async function dryRun(page: Page) {
  await page.getByTestId('step-dryRun').click()
  await page.getByTestId('run-dry-run').click()
  await expect(page.getByTestId('batch-state')).toHaveText('State: Dry run done', { timeout: 60_000 })
}

/** Abandons this journey's batches that an earlier failed run left open or validated, through the API. */
async function abandonLeftovers(page: Page) {
  await page.request.get('/api/v1/auth/csrf')
  const token = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value ?? ''
  const list = (await (await page.request.get('/api/v1/admin/imports?limit=100')).json()) as ImportBatchPage
  for (const batch of list.batches) {
    if (batch.name.startsWith('E2E import ') && (batch.state === 'OPEN' || batch.state === 'VALIDATED')) {
      await page.request.post(`/api/v1/admin/imports/${batch.id}/abandon`, {
        headers: { 'X-XSRF-TOKEN': decodeURIComponent(token) },
        data: { reason: 'Left by a failed journey run' },
      })
    }
  }
}
