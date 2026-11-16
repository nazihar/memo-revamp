import type { Page } from '@playwright/test'
import type {
  AttachmentView,
  HistoryAction,
  HistoryRound,
  InboxPage,
  MemoView,
  VersionComparison,
  WatchStatus,
} from '../../src/api/generated/types.gen'
import { expect, test } from '../fixtures'

/**
 * Visual regression (nightly) of the memo screens (E01 test plan, T9; Phase 3 E01 T8 adds the details and links): the
 * memo view of an approver in round 2, light/dark × LTR/RTL; the inbox; and the approver's phone layout (stacked memo
 * view with the action bar at the bottom, the approve dialog as a full-screen sheet). Phase 3 E02 T8 adds the attachments
 * (one restricted), a co-author and the version comparison. The API is mocked with fixed data;
 * baselines are rendered on Linux in the Playwright container (web/e2e/README.md). The mocks are typed with the generated
 * API types, so a change to the API breaks the typecheck on every PR rather than the nightly screenshots.
 */
const variants = [
  { name: 'light-ltr', theme: 'light', locale: 'en' },
  { name: 'dark-ltr', theme: 'dark', locale: 'en' },
  { name: 'light-rtl', theme: 'light', locale: 'ar' },
  { name: 'dark-rtl', theme: 'dark', locale: 'ar' },
] as const

const memo: MemoView = {
  id: 'm1',
  reference: 'M-2026-00042',
  subject: 'Opening a branch in Sylhet',
  type: { id: 't1', name: 'General memo' },
  classification: { id: 'c1', rank: 1, name: 'Internal' },
  status: 'IN_REVIEW',
  initiator: { id: 'u1', name: 'Demo User' },
  document: {
    fileId: 'f1',
    fileVersion: 2,
    versionId: 'v2',
    number: 2,
    name: 'branch-opening-sylhet.docx',
    sizeBytes: 48211,
    sha256: 'a'.repeat(64),
    uploadedAt: '2026-11-09T09:30:00Z',
  },
  route: [
    { ordinal: 1, approver: { id: 'u2', name: 'Demo Head of Credit' } },
    { ordinal: 2, approver: { id: 'u3', name: 'Demo Head of Finance' } },
    { ordinal: 3, approver: { id: 'u4', name: 'Demo Director' } },
  ],
  round: 2,
  holders: [{ id: 'u2', name: 'Demo Head of Credit' }],
  myTask: { id: 'k1', role: 'DECIDE', shared: false, waiting: false },
  interests: [],
  // Phase 3 E01 T8: the Details section and the links.
  typeVersion: 2,
  details: [
    { key: 'amount', label: 'Estimated cost', type: 'AMOUNT', value: { value: '12500000.00', currency: 'BDT' } },
    { key: 'branch', label: 'Branch', type: 'UNIT', value: 'b1', display: 'Sylhet Zindabazar' },
    { key: 'opening', label: 'Planned opening', type: 'DATE', value: '2027-03-01' },
    { key: 'kind', label: 'Kind of branch', type: 'CHOICE', value: 'full', display: 'Full-service branch' },
  ],
  staleDetails: [],
  links: [
    {
      id: 'l1',
      kind: 'FOLLOWS_UP',
      memoId: 'm7',
      reference: 'M-2026-00007',
      subject: 'Branch expansion plan 2026',
      status: 'APPROVED',
      removable: false,
    },
  ],
  linkedFrom: [],
  canLink: true,
  coauthors: [{ id: 'u5', name: 'Demo Branch Admin' }],
  rendition: 'NONE',
  sendBackTargets: [],
  allowedActions: ['APPROVE', 'RETURN', 'REJECT'],
  createdAt: '2026-11-08T08:00:00Z',
  updatedAt: '2026-11-09T09:31:00Z',
  version: 5,
}

const action = (
  seq: number,
  kind: HistoryAction['kind'],
  actor: string,
  at: string,
  documentNumber: number,
  remark?: string,
): HistoryAction => ({
  seq,
  kind,
  actor: { id: actor, name: actor === 'u1' ? 'Demo User' : 'Demo Head of Credit' },
  ...(remark ? { remark } : {}),
  documentNumber,
  sha256: 'a'.repeat(64),
  occurredAt: at,
  to: [],
  details: {},
})

const history: HistoryRound[] = [
  { round: 2, current: true, actions: [action(3, 'RESUBMIT', 'u1', '2026-11-09T09:31:00Z', 2, 'Annex B added.')] },
  {
    round: 1,
    current: false,
    actions: [
      action(1, 'SUBMIT', 'u1', '2026-11-08T08:05:00Z', 1, 'For your approval.'),
      action(2, 'RETURN', 'u2', '2026-11-08T14:20:00Z', 1, 'Please attach annex B with the lease terms.'),
    ],
  },
]

const inbox: InboxPage = {
  items: [
    {
      taskId: 'k1',
      memo: { id: 'm1', reference: 'M-2026-00042', subject: 'Opening a branch in Sylhet', restricted: false },
      round: 2,
      kind: 'DECIDE',
      shared: false,
      assignedAt: '2026-11-09T09:31:00Z',
    },
    {
      taskId: 'k2',
      memo: { id: 'm2', reference: 'M-2026-00038', subject: 'Annual training budget', restricted: false },
      round: 1,
      kind: 'DECIDE',
      shared: false,
      assignedAt: '2026-11-07T11:00:00Z',
    },
    {
      taskId: 'k3',
      memo: { id: 'm3', restricted: true },
      round: 1,
      kind: 'DECIDE',
      shared: false,
      blocked: 'NO_CLEARANCE',
      assignedAt: '2026-11-05T10:00:00Z',
    },
  ],
}

/** The note sheet (Phase 3 E03 T7): a comment with bold text and a mention, a corrected entry, and one stamp. */
const p = (...content: unknown[]) => ({ type: 'paragraph', content })
const notes = {
  canWrite: true,
  entries: [
    {
      id: 'n1',
      kind: 'COMMENT',
      round: 2,
      author: { id: 'u2', name: 'Demo Head of Credit' },
      body: {
        type: 'doc',
        content: [
          p(
            { type: 'text', text: 'Thanks. ' },
            { type: 'text', text: 'Rent schedule', marks: [{ type: 'bold' }] },
            { type: 'text', text: ' seen, ' },
            { type: 'mention', attrs: { id: 'u1', label: 'Demo User' } },
          ),
        ],
      },
      text: 'Thanks. Rent schedule seen, @Demo User',
      createdAt: '2026-11-09T11:02:00Z',
      correctedBy: [],
      attachments: [],
      readableMemos: [],
      canCorrect: false,
    },
    {
      id: 'n2',
      kind: 'COMMENT',
      round: 1,
      author: { id: 'u1', name: 'Demo User' },
      body: { type: 'doc', content: [p({ type: 'text', text: 'Annex B to follow.' })] },
      text: 'Annex B to follow.',
      createdAt: '2026-11-08T15:00:00Z',
      correctedBy: ['n3'],
      attachments: [],
      readableMemos: [],
      canCorrect: true,
    },
    {
      id: 'n3',
      kind: 'CORRECTION',
      round: 1,
      author: { id: 'u1', name: 'Demo User' },
      correctsId: 'n2',
      body: { type: 'doc', content: [p({ type: 'text', text: 'Annex B and the rent schedule to follow.' })] },
      text: 'Annex B and the rent schedule to follow.',
      createdAt: '2026-11-08T15:05:00Z',
      correctedBy: [],
      attachments: [],
      readableMemos: [],
      canCorrect: true,
    },
  ],
}
const stamps = [
  {
    id: 's1',
    decision: 'RETURN',
    round: 1,
    person: { id: 'u2', name: 'Demo Head of Credit' },
    decidedAt: '2026-11-08T14:20:00Z',
    factors: ['PASSWORD', 'TOTP'],
    secondFactorAgeSeconds: 125,
    stepUpRequired: false,
    documentNumber: 1,
    documentHash: '3f9a1c07',
  },
]

const attachments: AttachmentView[] = [
  {
    id: 'a1',
    restricted: false,
    position: 0,
    fileId: 'fa1',
    title: 'site-survey-zindabazar.pdf',
    mediaType: 'application/pdf',
    sizeBytes: 812_400,
    versionId: 'va1',
    versionNumber: 1,
    version: 0,
  },
  { id: 'a2', restricted: true, position: 1 },
]

const side = (number: number, uploadedAt: string, text: string): VersionComparison['from'] => ({
  number,
  versionId: 'v' + String(number),
  name: 'branch-opening-sylhet.docx',
  sha256: 'a'.repeat(64),
  uploadedAt,
  uploadedBy: { id: 'u1', name: 'Demo User' },
  state: 'DONE',
  truncated: false,
  text,
})

const comparison: VersionComparison = {
  from: side(
    1,
    '2026-11-08T08:00:00Z',
    [
      'Opening a branch in Sylhet',
      'Estimated cost BDT 11.8 million',
      'Planned opening 1 February 2027',
      'Staff: 14',
    ].join('\n'),
  ),
  to: side(
    2,
    '2026-11-09T09:30:00Z',
    [
      'Opening a branch in Sylhet',
      'Estimated cost BDT 12.5 million',
      'Planned opening 1 March 2027',
      'Staff: 14',
      'Annex B: lease terms',
    ].join('\n'),
  ),
}

async function setUp(page: Page, theme: string, locale: string) {
  await page.addInitScript(
    ({ t, l }) => {
      localStorage.setItem('memo.theme', t)
      localStorage.setItem('memo.locale', l)
    },
    { t: theme, l: locale },
  )
  await page.clock.setFixedTime(new Date('2026-11-10T10:00:05Z'))
  await page.route('**/api/v1/auth/session', (route) =>
    route.fulfill({
      json: {
        state: 'AUTHENTICATED',
        organisation: 'Demo Organization',
        user: { id: 'u2', email: 'head.credit@demo.example', displayName: 'Demo Head of Credit', tenantAdmin: false },
      },
    }),
  )
  await page.route('**/api/v1/me/access', (route) =>
    route.fulfill({
      json: {
        permissions: [
          { key: 'directory.read', tenantWide: true, scopeUnitIds: [] },
          { key: 'memo.create', tenantWide: true, scopeUnitIds: [] },
        ],
        clearance: { rank: 1, name: 'Internal' },
      },
    }),
  )
  await page.route('**/api/v1/memos/m1', (route) => route.fulfill({ json: memo }))
  await page.route('**/api/v1/memos/m1/history', (route) => route.fulfill({ json: history }))
  // Phase 3 E05: the approver follows the memo because they acted on it; they oversee no team.
  await page.route('**/api/v1/memos/m1/watch', (route) =>
    route.fulfill({ json: { watching: true, reason: 'ACTED' } satisfies WatchStatus }),
  )
  await page.route('**/api/v1/inbox/team?*', (route) =>
    route.fulfill({ status: 403, json: { type: 'about:blank', title: 'Forbidden', status: 403 } }),
  )
  await page.route('**/api/v1/memos/m1/attachments', (route) => route.fulfill({ json: attachments }))
  await page.route('**/api/v1/memos/m1/notes', (route) => route.fulfill({ json: notes }))
  await page.route('**/api/v1/memos/m1/approvals', (route) => route.fulfill({ json: stamps }))
  await page.route('**/api/v1/memos/m1/copies', (route) =>
    route.fulfill({
      json: {
        canEdit: false,
        canAdd: false,
        memoVersion: 4,
        copies: [
          {
            id: 'c1',
            recipient: { kind: 'LIST', id: 'l1', name: 'Credit committee' },
            sendWhen: 'AFTER_APPROVAL',
            sent: 0,
            read: 0,
            addedAt: '2026-11-09T08:00:00Z',
            addedBy: { id: 'u1', name: 'Demo User' },
          },
          {
            id: 'c2',
            recipient: { kind: 'USER', id: 'u5', name: 'Demo Auditor' },
            sendWhen: 'AT_SUBMIT',
            sent: 1,
            read: 1,
            sentAt: '2026-11-09T09:00:00Z',
            addedAt: '2026-11-09T08:00:00Z',
            addedBy: { id: 'u1', name: 'Demo User' },
          },
        ],
      },
    }),
  )
  await page.route('**/api/v1/memos/m1/shares', (route) =>
    route.fulfill({
      json: [
        {
          id: 's1',
          person: { id: 'u7', name: 'Demo Legal Counsel' },
          grantedBy: { id: 'u1', name: 'Demo User' },
          reason: 'Review of the guarantee',
          grantedAt: '2026-11-09T08:00:00Z',
          canRevoke: false,
        },
      ],
    }),
  )
  await page.route('**/api/v1/memos/m1/files/f1/compare?*', (route) => route.fulfill({ json: comparison }))
  await page.route('**/api/v1/authz/classification-levels', (route) =>
    route.fulfill({
      json: [
        { id: 'c0', name: 'Public', rank: 0 },
        { id: 'c1', name: 'Internal', rank: 1 },
        { id: 'c2', name: 'Confidential', rank: 2 },
      ],
    }),
  )
  await page.route('**/api/v1/inbox/tasks?*', (route) => route.fulfill({ json: inbox }))
}

for (const variant of variants) {
  test(`memo view — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/memos/m1')
    await expect(page.getByRole('toolbar').getByRole('button')).toHaveCount(3)
    await expect(page.locator('section[aria-label] ol li')).toHaveCount(6)
    await expect(page).toHaveScreenshot(`view-${variant.name}.png`, { fullPage: true })
  })
}

for (const variant of [variants[0], variants[3]]) {
  test(`final record, the auditor view — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    // An approved memo whose record is made (ADR-0053); the auditor sees its hash and validation.
    await page.route('**/api/v1/memos/m1', (route) =>
      route.fulfill({ json: { ...memo, status: 'APPROVED', allowedActions: [], myTask: null, rendition: 'READY' } }),
    )
    const v1 = {
      version: 1,
      state: 'READY',
      trigger: 'APPROVED',
      createdAt: '2026-11-09T14:20:00Z',
      finishedAt: '2026-11-09T14:22:00Z',
      pages: 62,
      sizeBytes: 812_000,
      classification: 'Internal',
      readable: true,
      fileId: 'r1',
      versionId: 'rv1',
      audit: {
        sha256: '3f9a1c07d2e84b6a9c0e5f7a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e',
        compliant: true,
        profile: '2u',
        validator: 'veraPDF 1.30.3',
        failedRules: {},
        rasterisedPages: 2,
        placeholders: 1,
        renderer: 'PDFBox 3.0.8 with Noto Sans 2.015',
      },
    }
    await page.route('**/api/v1/memos/m1/rendition', (route) =>
      route.fulfill({ json: { state: 'READY', current: v1, versions: [v1] } }),
    )
    await app.visit('/memos/m1')
    const box = page.locator('section[aria-labelledby="final-record"]')
    await expect(box.getByText('3f9a1c07', { exact: false })).toBeVisible()
    await expect(box).toHaveScreenshot(`final-record-${variant.name}.png`)
  })
}

for (const variant of [variants[0], variants[2]]) {
  test(`compare versions — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/memos/m1')
    await page.getByRole('button', { name: /Compare versions|مقارنة/ }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog.locator('ins').first()).toBeVisible()
    await expect(dialog).toHaveScreenshot(`compare-${variant.name}.png`)
  })
}

for (const variant of [variants[0], variants[2]]) {
  test(`inbox — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/inbox')
    await expect(page.getByRole('tabpanel').getByRole('listitem')).toHaveCount(3)
    await expect(page).toHaveScreenshot(`inbox-${variant.name}.png`, { fullPage: true })
  })
}

test.describe('phone', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  for (const variant of [variants[0], variants[2]]) {
    test(`approver on a phone — ${variant.name}`, async ({ app, page }) => {
      await setUp(page, variant.theme, variant.locale)
      await app.visit('/memos/m1')
      await expect(page.getByRole('toolbar').getByRole('button')).toHaveCount(3)
      await expect(page.locator('section[aria-label] ol li')).toHaveCount(6)
      await expect(page).toHaveScreenshot(`phone-view-${variant.name}.png`)
      await page.getByRole('toolbar').getByRole('button').first().click()
      await expect(page.getByRole('dialog')).toBeVisible()
      // The remark editor loads lazily (ADR-0049).
      await expect(page.getByRole('dialog').getByRole('toolbar')).toBeVisible()
      await expect(page).toHaveScreenshot(`phone-approve-${variant.name}.png`)
    })
  }
})
