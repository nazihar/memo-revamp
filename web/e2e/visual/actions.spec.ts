import { readFileSync } from 'node:fs'
import type { Page } from '@playwright/test'
import { expect, test } from '../fixtures'

/**
 * Visual regression (nightly) of the Phase 4 E07 action page as its owner sees it (flow AL, T12): the demo's BRC A1 of
 * the Board Risk Committee, raised under "Any other business" at the held "Risk review, third quarter", which Demo
 * Director marked as done and which waits for verification; light/dark × LTR/RTL and on a phone. The API's answers are
 * the demo seed's, captured from the local stack for Demo Director (`action-owner.json`, keyed by path and query); any
 * other call answers 404. Baselines are rendered on Linux in the Playwright container (web/e2e/README.md).
 */
const variants = [
  { name: 'light-ltr', theme: 'light', locale: 'en' },
  { name: 'dark-ltr', theme: 'dark', locale: 'en' },
  { name: 'light-rtl', theme: 'light', locale: 'ar' },
  { name: 'dark-rtl', theme: 'dark', locale: 'ar' },
] as const

interface Captured {
  /** When the answers were captured: the page's clock is fixed there, so "overdue" and dates read the same. */
  at: string
  actionId: string
  answers: Record<string, unknown>
}

const data = JSON.parse(readFileSync(new URL('./action-owner.json', import.meta.url), 'utf8')) as Captured

async function setUp(page: Page, theme: string, locale: string) {
  await page.addInitScript(
    ({ t, l }) => {
      localStorage.setItem('memo.theme', t)
      localStorage.setItem('memo.locale', l)
    },
    { t: theme, l: locale },
  )
  await page.clock.setFixedTime(new Date(data.at))
  await page.route('**/api/v1/**', (route) => {
    const url = new URL(route.request().url())
    const key = url.pathname.replace(/^\/api\/v1/, '') + url.search
    const json = data.answers[key]
    return json === undefined
      ? route.fulfill({ status: 404, json: { status: 404, title: 'Not Found' } })
      : route.fulfill({ json })
  })
}

for (const variant of variants) {
  test(`an action done and waiting for verification, as its owner — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit(`/actions/${data.actionId}`)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveScreenshot(`action-owner-${variant.name}.png`, { fullPage: true })
  })
}

test.describe('phone', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('an action done and waiting for verification, as its owner, on a phone', async ({ app, page }) => {
    await setUp(page, 'light', 'en')
    await app.visit(`/actions/${data.actionId}`)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveScreenshot('phone-action-owner.png', { fullPage: true })
  })
})
