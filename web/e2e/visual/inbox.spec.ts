import type { Page } from '@playwright/test'
import type { ApprovalsDashboard, InboxPage, TeamPage, WatchingPage } from '../../src/api/generated/types.gen'
import { expect, test } from '../fixtures'

/**
 * Visual regression (nightly) of the inbox and dashboard screens of Phase 3 E05 T7 (flows I, J and K): the Team tab of a
 * unit head (overdue first, a restricted memo, the filters), the Watching tab, the bulk bar, and the Approvals dashboard
 * for a unit, light/dark × LTR/RTL; and both on a phone (the team list stacked with the bulk bar at the bottom, the
 * dashboard's figures stacked). The API is mocked with fixed data typed by the generated client; the clock is fixed, so
 * the period and the due times are stable.
 */
const variants = [
  { name: 'light-ltr', theme: 'light', locale: 'en' },
  { name: 'dark-ltr', theme: 'dark', locale: 'en' },
  { name: 'light-rtl', theme: 'light', locale: 'ar' },
  { name: 'dark-rtl', theme: 'dark', locale: 'ar' },
] as const

const team: TeamPage = {
  items: [
    {
      taskId: 't2',
      memo: { id: 'm31', reference: 'M-2026-00031', subject: 'Branch generator replacement', restricted: false },
      holder: { id: 'p2', name: 'Demo Credit Officer' },
      unit: { id: 'u1', name: 'Credit Division' },
      stepName: 'Approve · step 2',
      assignedAt: '2026-11-02T09:00:00Z',
      dueAt: '2026-11-08T16:00:00Z',
      overdue: true,
      ageWorkingDays: 6,
    },
    {
      taskId: 't1',
      memo: { id: 'm42', reference: 'M-2026-00042', subject: 'Office lease extension', restricted: false },
      holder: { id: 'p1', name: 'Demo Head of Credit' },
      unit: { id: 'u1', name: 'Credit Division' },
      stepName: 'Approve · step 1',
      assignedAt: '2026-11-07T09:00:00Z',
      dueAt: '2026-11-10T16:00:00Z',
      overdue: false,
      ageWorkingDays: 1,
    },
    {
      taskId: 't3',
      memo: { id: 'm9', reference: null, subject: null, restricted: true },
      holder: { id: 'p3', name: 'Demo Credit Analyst' },
      unit: { id: 'u2', name: 'Credit Administration' },
      stepName: 'Opinion',
      assignedAt: '2026-11-09T09:00:00Z',
      dueAt: '2026-11-12T16:00:00Z',
      overdue: false,
      ageWorkingDays: 0,
    },
  ],
  nextCursor: null,
  units: [
    { id: 'u1', name: 'Credit Division' },
    { id: 'u2', name: 'Credit Administration' },
  ],
}

const watching: WatchingPage = {
  items: [
    {
      memoId: 'm42',
      reference: 'M-2026-00042',
      subject: 'Office lease extension',
      reason: 'INITIATED',
      since: '2026-11-09T08:00:00Z',
    },
    {
      memoId: 'm31',
      reference: 'M-2026-00031',
      subject: 'Branch generator replacement',
      reason: 'ACTED',
      since: '2026-11-05T08:00:00Z',
    },
    {
      memoId: 'm7',
      reference: null,
      subject: 'Branch rent review 2027',
      reason: 'MANUAL',
      since: '2026-11-01T08:00:00Z',
    },
  ],
  nextCursor: null,
}

const tasks: InboxPage = { items: [], nextCursor: null }

/** The bulk bar, in English or Arabic. */
const BULK = /Actions on the selected items|إجراءات على العناصر المحددة/

const weeks = Array.from({ length: 12 }, (_, i) => ({
  week: new Date(Date.UTC(2026, 7, 24 + i * 7)).toISOString().slice(0, 10),
  created: [6, 9, 7, 11, 8, 10, 12, 9, 7, 13, 10, 8][i] ?? 0,
  completed: [5, 7, 8, 9, 9, 8, 10, 11, 8, 10, 12, 9][i] ?? 0,
}))

const dashboard: ApprovalsDashboard = {
  scope: 'unit:u1',
  unit: { id: 'u1', name: 'Credit Division' },
  from: '2026-08-24',
  to: '2026-11-10',
  pendingByAge: [
    { bucket: '0-2', count: 41 },
    { bucket: '3-5', count: 18 },
    { bucket: '6-10', count: 6 },
    { bucket: '10+', count: 2 },
  ],
  overdue: 7,
  pendingByPerson: [
    { person: { id: 'p1', name: 'Demo Head of Credit' }, pending: 9, overdue: 2 },
    { person: { id: 'p2', name: 'Demo Credit Officer' }, pending: 6, overdue: 3 },
    { person: { id: 'p3', name: 'Demo Credit Analyst' }, pending: 4, overdue: 0 },
  ],
  cycleTimes: [
    { type: { id: 't2', name: 'Credit proposal' }, decided: 12, medianDays: 3.2, p90Days: 9.5 },
    { type: { id: 't1', name: 'General memo' }, decided: 30, medianDays: 1.4, p90Days: 4 },
  ],
  throughput: weeks,
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
        user: { id: 'p1', email: 'head.credit@demo.example', displayName: 'Demo Head of Credit', tenantAdmin: false },
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
  await page.route('**/api/v1/inbox/team?*', (route) => route.fulfill({ json: team }))
  await page.route('**/api/v1/inbox/watching?*', (route) => route.fulfill({ json: watching }))
  await page.route('**/api/v1/inbox/tasks?*', (route) => route.fulfill({ json: tasks }))
  await page.route('**/api/v1/dashboards/approvals?*', (route) => route.fulfill({ json: dashboard }))
}

for (const variant of variants) {
  test(`team view — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/inbox?tab=team')
    await expect(page.getByRole('tabpanel').getByRole('listitem')).toHaveCount(3)
    await expect(page).toHaveScreenshot(`team-${variant.name}.png`, { fullPage: true })
  })

  test(`approvals dashboard — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/dashboards/approvals?scope=unit%3Au1')
    await expect(page.locator('svg.recharts-surface')).toHaveCount(4)
    await expect(page).toHaveScreenshot(`dashboard-${variant.name}.png`, { fullPage: true })
  })
}

for (const variant of [variants[0], variants[2]]) {
  test(`team view with a selection and the reassign dialog — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/inbox?tab=team')
    await page.getByRole('tabpanel').getByRole('checkbox').nth(1).check()
    await page.getByRole('tabpanel').getByRole('checkbox').nth(3).check()
    await expect(page.getByRole('toolbar', { name: BULK })).toBeVisible()
    await expect(page).toHaveScreenshot(`team-selected-${variant.name}.png`, { fullPage: true })
    await page.getByRole('toolbar', { name: BULK }).getByRole('button').first().click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await app.checkA11y()
    await expect(page).toHaveScreenshot(`team-reassign-${variant.name}.png`)
  })

  test(`watching — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/inbox?tab=watching')
    await expect(page.getByRole('tabpanel').getByRole('listitem')).toHaveCount(3)
    await expect(page).toHaveScreenshot(`watching-${variant.name}.png`, { fullPage: true })
  })

  test(`dashboard tables shown — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/dashboards/approvals?scope=unit%3Au1')
    await expect(page.locator('svg.recharts-surface')).toHaveCount(4)
    for (const toggle of await page
      .locator('figure')
      .getByRole('button', { name: /Show as table|عرض كجدول/ })
      .all())
      await toggle.click()
    await expect(page.getByRole('table')).toHaveCount(4)
    await app.checkA11y()
    await expect(page).toHaveScreenshot(`dashboard-tables-${variant.name}.png`, { fullPage: true })
  })
}

test.describe('phone', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  for (const variant of [variants[0], variants[2]]) {
    test(`team view on a phone, the bulk bar at the bottom — ${variant.name}`, async ({ app, page }) => {
      await setUp(page, variant.theme, variant.locale)
      await app.visit('/inbox?tab=team')
      await page.getByRole('tabpanel').getByRole('checkbox').nth(1).check()
      await expect(page.getByRole('toolbar', { name: BULK })).toBeVisible()
      await expect(page).toHaveScreenshot(`phone-team-${variant.name}.png`)
    })

    test(`dashboard on a phone — ${variant.name}`, async ({ app, page }) => {
      await setUp(page, variant.theme, variant.locale)
      await app.visit('/dashboards/approvals?scope=unit%3Au1')
      await expect(page.locator('svg.recharts-surface')).toHaveCount(4)
      await expect(page).toHaveScreenshot(`phone-dashboard-${variant.name}.png`, { fullPage: true })
    })
  }
})
