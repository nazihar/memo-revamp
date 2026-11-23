import { readFileSync } from 'node:fs'
import type { Page } from '@playwright/test'
import type {
  MeetingResponse,
  MemberAgendaResponse,
  MinutesCommentsResponse,
  MinutesResponse,
} from '../../src/api/generated/types.gen'
import { expect, test } from '../fixtures'

/**
 * Visual regression (nightly) of the Phase 4 E06 minutes page as a reader sees it (flow AF, T12): the demo's circulated
 * minutes of the Board Risk Committee's "Risk review, third quarter" for Demo Director, who declared an interest in an
 * item and has commented on another; light/dark × LTR/RTL and on a phone. The answers are the demo seed's, captured from
 * the local stack with their ids replaced (`minutes-reader.json`) and typed by the generated API types; baselines are
 * rendered on Linux in the Playwright container (web/e2e/README.md).
 */
const variants = [
  { name: 'light-ltr', theme: 'light', locale: 'en' },
  { name: 'dark-ltr', theme: 'dark', locale: 'en' },
  { name: 'light-rtl', theme: 'light', locale: 'ar' },
  { name: 'dark-rtl', theme: 'dark', locale: 'ar' },
] as const

interface Captured {
  meeting: MeetingResponse
  minutes: MinutesResponse
  agenda: MemberAgendaResponse
  comments: MinutesCommentsResponse
  forums: unknown
  unread: unknown
}

const data = JSON.parse(readFileSync(new URL('./minutes-reader.json', import.meta.url), 'utf8')) as Captured
const meetingId = data.meeting.id

async function setUp(page: Page, theme: string, locale: string) {
  await page.addInitScript(
    ({ t, l }) => {
      localStorage.setItem('memo.theme', t)
      localStorage.setItem('memo.locale', l)
    },
    { t: theme, l: locale },
  )
  await page.clock.setFixedTime(new Date('2026-11-23T06:00:05Z'))
  await page.route('**/api/v1/auth/session', (route) =>
    route.fulfill({
      json: {
        state: 'AUTHENTICATED',
        organisation: 'Demo Organization',
        user: { id: 'u-director', email: 'director@demo.example', displayName: 'Demo Director', tenantAdmin: false },
      },
    }),
  )
  await page.route('**/api/v1/me/access', (route) =>
    route.fulfill({
      json: {
        permissions: ['directory.read', 'memo.create'].map((key) => ({ key, tenantWide: true, scopeUnitIds: [] })),
        clearance: { rank: 0, name: 'Public' },
      },
    }),
  )
  await page.route('**/api/v1/me/notifications/unread-count', (route) => route.fulfill({ json: data.unread }))
  await page.route('**/api/v1/me/forums', (route) => route.fulfill({ json: data.forums }))
  await page.route(`**/api/v1/meetings/${meetingId}`, (route) => route.fulfill({ json: data.meeting }))
  await page.route(`**/api/v1/meetings/${meetingId}/minutes`, (route) => route.fulfill({ json: data.minutes }))
  await page.route(`**/api/v1/meetings/${meetingId}/agenda`, (route) => route.fulfill({ json: data.agenda }))
  await page.route(`**/api/v1/meetings/${meetingId}/minutes/comments`, (route) =>
    route.fulfill({ json: data.comments }),
  )
}

for (const variant of variants) {
  test(`circulated minutes for a recused reader — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit(`/meetings/${meetingId}/minutes`)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveScreenshot(`minutes-reader-${variant.name}.png`, { fullPage: true })
  })
}

test.describe('phone', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('circulated minutes for a recused reader on a phone', async ({ app, page }) => {
    await setUp(page, 'light', 'en')
    await app.visit(`/meetings/${meetingId}/minutes`)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveScreenshot('phone-minutes-reader.png', { fullPage: true })
  })
})
