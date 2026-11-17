import type { Page } from '@playwright/test'
import type { HistoryAction, HistoryRound, InboxPage, MemoView } from '../../src/api/generated/types.gen'
import { expect, test } from '../fixtures'

/**
 * Visual regression (nightly) of the memo screens (E01 test plan, T9): the memo view of an approver in round 2, light/dark
 * × LTR/RTL; the inbox; and the approver's phone layout (stacked memo view with the action bar at the bottom, the approve
 * dialog as a full-screen sheet). The API is mocked with fixed data; baselines are rendered on Linux in the Playwright
 * container (web/e2e/README.md). The mocks are typed with the generated API types, so a change to the API breaks the
 * typecheck on every PR rather than the nightly screenshots.
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
  details: [],
  staleDetails: [],
  links: [],
  linkedFrom: [],
  canLink: true,
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
  await page.route('**/api/v1/inbox/tasks?*', (route) => route.fulfill({ json: inbox }))
}

for (const variant of variants) {
  test(`memo view — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/memos/m1')
    await expect(page.getByRole('toolbar').getByRole('button')).toHaveCount(3)
    await expect(page.locator('section[aria-label] ol li')).toHaveCount(3)
    await expect(page).toHaveScreenshot(`view-${variant.name}.png`, { fullPage: true })
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
      await expect(page.locator('section[aria-label] ol li')).toHaveCount(3)
      await expect(page).toHaveScreenshot(`phone-view-${variant.name}.png`)
      await page.getByRole('toolbar').getByRole('button').first().click()
      await expect(page.getByRole('dialog')).toBeVisible()
      await expect(page).toHaveScreenshot(`phone-approve-${variant.name}.png`)
    })
  }
})
