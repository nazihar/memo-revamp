import type { Page } from '@playwright/test'
import type {
  ClassificationLevel,
  ImportBatchView,
  ImportMappingsView,
  ImportProblemPage,
  ImportReportView,
  IntakeEntryView,
  IntakePage,
  IntakeRegistrationRequest,
  MemoView,
  SearchPageView,
  WatchStatus,
} from '../../src/api/generated/types.gen'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { expect, test } from '../fixtures'

/**
 * Visual regression (nightly) of the legacy import (Phase 3 E06 T9, flows L to O): the batch's steps (metadata,
 * originals while sending, mappings, the dry run's problems, the import in progress, the report), the imported memo's
 * page and search with the Origin filter and the "Imported" badge; light/dark × LTR/RTL for the main ones, and a phone.
 * The API is mocked with fixed data typed by the generated client; baselines are rendered on Linux in the Playwright
 * container (web/e2e/README.md).
 */
const variants = [
  { name: 'light-ltr', theme: 'light', locale: 'en' },
  { name: 'dark-ltr', theme: 'dark', locale: 'en' },
  { name: 'light-rtl', theme: 'light', locale: 'ar' },
  { name: 'dark-rtl', theme: 'dark', locale: 'ar' },
] as const
const [lightLtr, , lightRtl, darkRtl] = variants

const levels = [
  { id: 'l0', name: 'Internal', rank: 0 },
  { id: 'l1', name: 'Confidential', rank: 1 },
  { id: 'l2', name: 'Secret', rank: 2 },
] as ClassificationLevel[]

const batch: ImportBatchView = {
  id: 'b1',
  name: 'Head office 2015–2019',
  sourceCode: 'LEGACY-DMS',
  timeZone: 'Asia/Dhaka',
  state: 'VALIDATED',
  version: 9,
  allowedActions: ['EDIT', 'ATTACH_SOURCE', 'REGISTER_ORIGINALS', 'MAP', 'DRY_RUN', 'START', 'ABANDON'],
  createdAt: '2026-11-16T08:00:00Z',
  createdBy: 'u1',
  updatedAt: '2026-11-16T11:40:00Z',
  dryRunAt: '2026-11-16T11:40:00Z',
  maxRank: 1,
  sourceKind: 'XLSX',
  sources: [
    {
      table: 'WORKBOOK',
      fileId: 'f1',
      versionId: 'v1',
      name: 'legacy-export.xlsx',
      sizeBytes: 4_404_019,
      sha256: 'ab',
      uploadedAt: '2026-11-16T10:02:00Z',
    },
  ],
  originals: { expected: 0, expectedBytes: 0, purged: 0, received: 31_197, receivedBytes: 1_717_986_918, refused: 7 },
  rows: { READY: 9812, REJECTED: 147 },
  problems: { ERROR: 147, WARNING: 1903, FILE: 240 },
}

const mappings: ImportMappingsView = {
  batchVersion: 9,
  mappings: [
    { kind: 'STATUS', legacyValue: 'Sanctioned', rowCount: 6102, target: 'APPROVED', proposed: false, confirmed: true },
    {
      kind: 'STATUS',
      legacyValue: 'Approved & filed',
      rowCount: 2880,
      target: 'CLOSED',
      proposed: false,
      confirmed: true,
    },
    { kind: 'STATUS', legacyValue: 'Declined', rowCount: 611, target: 'REJECTED', proposed: true, confirmed: false },
    {
      kind: 'STATUS',
      legacyValue: 'Pending with GM',
      rowCount: 42,
      target: 'NOT_IMPORTED',
      proposed: false,
      confirmed: false,
    },
  ],
  targets: levels.map((l) => ({ kind: 'CLASSIFICATION' as const, id: l.id, label: l.name, rank: l.rank })),
}

const problems: ImportProblemPage = {
  byLevel: { ERROR: 147, WARNING: 1903, FILE: 240 },
  byCode: {
    NUMBER_IN_USE: 12,
    IN_FLIGHT_NOT_IMPORTED: 42,
    FILE_NOT_RECEIVED: 9,
    NUMBER_IN_LIVE_SERIES: 3,
    FILE_UNREFERENCED: 233,
  },
  problems: [
    {
      id: 'p1',
      stage: 'DRY_RUN',
      level: 'ERROR',
      code: 'NUMBER_IN_USE',
      sheet: 'MEMOS',
      rowNumber: 214,
      column: 'legacy_number',
      params: {},
    },
    {
      id: 'p2',
      stage: 'DRY_RUN',
      level: 'ERROR',
      code: 'IN_FLIGHT_NOT_IMPORTED',
      sheet: 'MEMOS',
      rowNumber: 880,
      column: 'status',
      params: {},
    },
    {
      id: 'p3',
      stage: 'DRY_RUN',
      level: 'ERROR',
      code: 'FILE_NOT_RECEIVED',
      sheet: 'FILES',
      rowNumber: 1322,
      column: 'path',
      params: { path: '2017/CR-0108/main.pdf' },
    },
    {
      id: 'p4',
      stage: 'DRY_RUN',
      level: 'ERROR',
      code: 'NUMBER_IN_LIVE_SERIES',
      sheet: 'MEMOS',
      rowNumber: 3051,
      column: 'legacy_number',
      params: { format: 'CR/{FY}/{SEQ}' },
    },
  ],
  next: 'more',
}

const report: ImportReportView = {
  batchId: 'b1',
  state: 'COMPLETED',
  completedAt: '2026-11-16T16:00:00Z',
  balanced: true,
  complete: true,
  valid: true,
  failing: [],
  totals: {
    rows: { read: 9959, imported: 9794, rejected: 159, skipped: 0, failed: 6, notStarted: 0, rejectedByCode: {} },
    files: { listed: 31_087, matched: 31_087, notReceived: 0, ambiguous: 0 },
    originals: {
      registered: { files: 31_204, bytes: 1 },
      stored: { files: 31_197, bytes: 1 },
      imported: { files: 30_880, bytes: 1 },
      heldBack: { files: 84, bytes: 1 },
      unreferenced: { files: 233, bytes: 1 },
      refused: { files: 7, bytes: 1 },
      neverUploaded: { files: 0, bytes: 0 },
    },
    renditions: {
      PDF: { originals: 30_880, done: 30_402, notPossible: 478, notNeeded: 0, failed: 0, pending: 0 },
      TEXT: { originals: 30_880, done: 29_960, notPossible: 920, notNeeded: 0, failed: 0, pending: 0 },
    },
    fixity: { verified: 30_880, importedOriginals: 30_880, mismatches: 0, promotionMismatches: 0, verifyProblems: 0 },
    problems: {},
    storedNotBound: 0,
  },
  parts: [
    { kind: 'SUMMARY', fileId: 'r1', versionId: 'r1', name: 'report-summary.pdf', sha256: 'a', size: 1 },
    { kind: 'DETAIL', fileId: 'r2', versionId: 'r2', name: 'report-detail.xlsx', sha256: 'b', size: 1 },
    { kind: 'MANIFEST', fileId: 'r3', versionId: 'r3', name: 'manifest-sha256.txt', sha256: 'c', size: 1 },
  ],
}

const imported: MemoView = {
  origin: 'IMPORTED',
  followsUp: [],
  id: 'm1',
  reference: 'CR/2016/0108',
  subject: 'Working capital line for Meghna Agro',
  type: { id: 't1', name: 'Credit proposal' },
  typeVersion: 2,
  classification: { id: 'l1', rank: 1, name: 'Confidential' },
  originUnit: { id: 'u1', name: 'Credit Division' },
  status: 'APPROVED',
  route: [],
  round: 0,
  holders: [],
  sendBackTargets: [],
  allowedActions: ['CORRECT_LEGACY', 'RECLASSIFY'],
  createdAt: '2026-11-16T12:00:00Z',
  updatedAt: '2026-11-21T09:00:00Z',
  version: 2,
  interests: [],
  details: [],
  staleDetails: [],
  links: [],
  linkedFrom: [],
  canLink: false,
  coauthors: [],
  rendition: 'NONE',
  legacy: {
    batchId: 'b1',
    rowNumber: 214,
    sourceCode: 'LEGACY-DMS',
    legacyNumber: 'CR/2016/0108',
    importedAt: '2026-11-16T12:00:00Z',
    timeZone: 'Asia/Dhaka',
    raisedOn: '2016-03-13',
    decidedOn: '2016-03-21',
    status: 'Sanctioned',
    type: 'Credit',
    unit: 'CR-HO',
    classification: 'Conf',
    subject: 'Working capital line for Meghna Agro',
    initiator: { name: 'R. K. Bansal' },
    approvals: [
      {
        ordinal: 1,
        person: { name: 'A. Rahman', user: { id: 'u7', name: 'Demo Head of Credit' } },
        position: 'Head of Credit',
        decision: 'Recommended',
        decidedOn: '2016-03-14',
      },
      {
        ordinal: 2,
        person: { name: 'S. Karim' },
        position: 'GM Credit',
        decision: 'Sanctioned',
        decidedOn: '2016-03-21',
        remark: 'Subject to collateral',
      },
    ],
    originals: [
      {
        id: 'o1',
        role: 'MAIN',
        position: 0,
        title: 'proposal.doc',
        mediaType: 'application/msword',
        sizeBytes: 421_888,
        restricted: false,
        fileId: 'f1',
        versionId: 'v1',
      },
      {
        id: 'o2',
        role: 'ATTACHMENT',
        position: 1,
        title: 'valuation.pdf',
        mediaType: 'application/pdf',
        sizeBytes: 2_202_009,
        restricted: false,
        fileId: 'f2',
        versionId: 'v2',
      },
      {
        id: 'o3',
        role: 'ATTACHMENT',
        position: 2,
        title: 'site-scan.tif',
        mediaType: 'image/tiff',
        sizeBytes: 5_100_000,
        restricted: false,
        fileId: 'f3',
        versionId: 'v3',
      },
      { id: 'o4', role: 'ATTACHMENT', position: 3, restricted: true },
    ],
    corrections: [
      {
        field: 'RAISED_ON',
        before: '2016-03-13',
        after: '2016-03-03',
        reason: 'Typo in the legacy export',
        correctedAt: '2026-11-21T09:00:00Z',
        correctedBy: { id: 'u1', name: 'Demo Tenant Admin' },
      },
    ],
  },
}

const searchPage: SearchPageView = {
  results: [
    {
      memoId: 'm1',
      number: 'CR/2016/0108',
      subject: 'Working capital line for Meghna Agro',
      typeId: 't1',
      typeName: 'Credit proposal',
      status: 'APPROVED',
      unitId: 'u1',
      unitName: 'Credit Division',
      updatedAt: '2026-11-21T09:00:00Z',
      snippet: [
        { text: '…sanctioned by ', match: false },
        { text: 'S. Karim', match: true },
        { text: ', GM Credit…', match: false },
      ],
      foundIn: { kind: 'DETAILS' },
      origin: 'IMPORTED',
    },
  ],
  count: 1,
  capped: false,
  facets: { status: [{ value: 'APPROVED', label: 'APPROVED', count: 1 }], type: [], unit: [] },
  cursor: null,
}

/** An unpacked legacy archive on disk, as the admin would choose it (a folder upload needs a real directory). */
function archive(): string {
  const dir = join(mkdtempSync(join(tmpdir(), 'legacy-')), 'HO-archive')
  for (const path of [
    '2016/CR-0042/proposal.doc',
    '2016/CR-0042/annex.pdf',
    '2017/HO-FIN-117/main.pdf',
    '2017/HO-FIN-117/scan.tif',
    'Thumbs.db',
  ]) {
    mkdirSync(join(dir, path, '..'), { recursive: true })
    writeFileSync(join(dir, path), Buffer.alloc(2048, 1))
  }
  return dir
}

async function setUp(page: Page, theme: string, locale: string, view: ImportBatchView = batch) {
  await page.addInitScript(
    ({ t, l }) => {
      localStorage.setItem('memo.theme', t)
      localStorage.setItem('memo.locale', l)
    },
    { t: theme, l: locale },
  )
  await page.clock.setFixedTime(new Date('2026-11-22T10:00:05Z'))
  await page.route('**/api/v1/auth/session', (route) =>
    route.fulfill({
      json: {
        state: 'AUTHENTICATED',
        organisation: 'Demo Organization',
        user: { id: 'u1', email: 'admin@demo.example', displayName: 'Demo Tenant Admin', tenantAdmin: true },
      },
    }),
  )
  await page.route('**/api/v1/me/access', (route) =>
    route.fulfill({
      json: {
        permissions: ['directory.read', 'memo.create', 'memo.import'].map((key) => ({
          key,
          tenantWide: true,
          scopeUnitIds: [],
        })),
        clearance: { rank: 2, name: 'Secret' },
      },
    }),
  )
  await page.route('**/api/v1/authz/classification-levels', (route) => route.fulfill({ json: levels }))
  await page.route('**/api/v1/admin/imports/b1', (route) => route.fulfill({ json: view }))
  await page.route('**/api/v1/admin/imports/b1/mappings', (route) => route.fulfill({ json: mappings }))
  await page.route('**/api/v1/admin/imports/b1/problems?*', (route) => route.fulfill({ json: problems }))
  await page.route('**/api/v1/admin/imports/b1/report', (route) => route.fulfill({ json: report }))
  await page.route('**/api/v1/admin/imports/b1/files?*', (route) => {
    if (route.request().method() === 'POST') return route.fallback()
    return route.fulfill({
      json: {
        files: [
          { intakeId: 'i1', path: '2016/CR-0042/annex.doc', size: 1, state: 'REFUSED', refusal: 'MALWARE' },
          { intakeId: 'i2', path: '2017/HO-FIN-117/scan.msg', size: 1, state: 'REFUSED', refusal: 'TYPE_NOT_ALLOWED' },
        ],
      } satisfies IntakePage,
    })
  })
  await page.route('**/api/v1/memos/m1', (route) => route.fulfill({ json: imported }))
  await page.route('**/api/v1/memos/m1/watch', (route) =>
    route.fulfill({ json: { watching: false } satisfies WatchStatus }),
  )
  await page.route('**/api/v1/memos/m1/copies', (route) =>
    route.fulfill({ json: { copies: [], canEdit: false, canAdd: false, memoVersion: 2 } }),
  )
  await page.route('**/api/v1/memos/m1/shares', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/v1/search', (route) => route.fulfill({ json: searchPage }))
  await page.route('**/api/v1/saved-searches', (route) => route.fulfill({ json: [] }))
}

for (const variant of variants) {
  test(`import mappings — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/admin/imports/b1?step=mappings')
    await expect(page.getByTestId('mapping-STATUS-Sanctioned')).toBeVisible()
    await expect(page).toHaveScreenshot(`import-mappings-${variant.name}.png`, { fullPage: true })
  })

  test(`imported memo — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/memos/m1')
    await expect(page.getByTestId('imported-badge')).toBeVisible()
    await expect(page.getByTestId('legacy-original').first()).toBeVisible()
    await expect(page).toHaveScreenshot(`imported-memo-${variant.name}.png`, { fullPage: true })
  })
}

for (const variant of [lightLtr, lightRtl]) {
  test(`import metadata — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/admin/imports/b1?step=metadata')
    await expect(page.getByTestId('source-WORKBOOK')).toBeVisible()
    await expect(page).toHaveScreenshot(`import-metadata-${variant.name}.png`, { fullPage: true })
  })

  test(`import originals while sending — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await page.route('**/api/v1/admin/imports/b1/files', async (route) => {
      const body = route.request().postDataJSON() as IntakeRegistrationRequest
      await route.fulfill({
        json: body.files.map((f, i) => ({
          intakeId: `n${String(i)}`,
          path: f.path,
          size: f.size,
          state: i === 0 ? 'RECEIVED' : 'EXPECTED',
        })) satisfies IntakeEntryView[],
      })
    })
    // Uploads never answer, so the step stays "sending".
    await page.route('**/api/v1/files?*', () => undefined)
    await app.visit('/admin/imports/b1?step=originals')
    await page.getByTestId('folder-input').setInputFiles(archive())
    await expect(page.getByTestId('intake-progress')).toBeVisible()
    await expect(page).toHaveScreenshot(`import-originals-${variant.name}.png`, { fullPage: true })
  })

  test(`import dry run problems — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/admin/imports/b1?step=dryRun')
    await expect(page.getByTestId('problem-table').getByRole('row')).toHaveCount(5)
    await expect(page).toHaveScreenshot(`import-problems-${variant.name}.png`, { fullPage: true })
  })

  test(`import in progress — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale, {
      ...batch,
      state: 'IMPORTING',
      allowedActions: ['STOP'],
      progress: {
        rows: 9812,
        done: 4120,
        imported: 4102,
        skipped: 0,
        rejected: 12,
        failed: 6,
        notStarted: 0,
        left: 5692,
        ratePerMinute: 142,
        throttled: true,
        throttledSince: '2026-11-22T09:58:00Z',
      },
    })
    await app.visit('/admin/imports/b1?step=import')
    await expect(page.getByTestId('import-progress')).toBeVisible()
    await expect(page).toHaveScreenshot(`import-progress-${variant.name}.png`, { fullPage: true })
  })

  test(`search for imported memos — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/search?origin=imported#q=karim')
    await expect(page.getByRole('link', { name: /Working capital line/ })).toBeVisible()
    await expect(page).toHaveScreenshot(`search-imported-${variant.name}.png`, { fullPage: true })
  })
}

for (const variant of [lightLtr, darkRtl]) {
  test(`import report — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale, { ...batch, state: 'COMPLETED', allowedActions: ['CLOSE'] })
    await app.visit('/admin/imports/b1?step=report')
    await expect(page.getByTestId('report')).toBeVisible()
    await expect(page).toHaveScreenshot(`import-report-${variant.name}.png`, { fullPage: true })
  })
}

test.describe('phone', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test(`import originals on a phone — ${lightLtr.name}`, async ({ app, page }) => {
    await setUp(page, lightLtr.theme, lightLtr.locale)
    await app.visit('/admin/imports/b1?step=originals')
    await expect(page.getByText('Use a computer to send a folder.')).toBeVisible()
    await expect(page).toHaveScreenshot(`phone-import-originals-${lightLtr.name}.png`, { fullPage: true })
  })

  test(`imported memo on a phone — ${lightRtl.name}`, async ({ app, page }) => {
    await setUp(page, lightRtl.theme, lightRtl.locale)
    await app.visit('/memos/m1')
    await expect(page.getByTestId('imported-badge')).toBeVisible()
    await expect(page).toHaveScreenshot(`phone-imported-memo-${lightRtl.name}.png`, { fullPage: true })
  })
})
