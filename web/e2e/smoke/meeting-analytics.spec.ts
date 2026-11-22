import type { Page } from '@playwright/test'
import { expect, test } from '../fixtures'

/**
 * Committee analytics (Phase 4 E08 T7, flows AU–AW; test plan `meeting-analytics.spec`), against the demo stack with
 * the held meetings of the E03–E05 seeds:
 * - the company secretary, an officer of every demo committee, opens the overview from the menu: each committee's
 *   sentence comes before its table, the table is visible without asking, the bars are out of the accessibility tree,
 *   and the CSP fixture fails the test on any inline-style report; the committee's detail and its attendance per
 *   member follow the figures' links; axe in English and Arabic;
 * - Demo Head of Finance, a member of the Management Credit Committee, reads their own attendance row and the
 *   committee's total, with no export.
 * The numbers depend on the seed, so the journey checks the structure, not the figures (the component tests check them).
 */
async function toArabic(page: Page) {
  await page.getByRole('button', { name: /Change language/ }).click()
  await page.getByRole('menuitemradio', { name: 'العربية' }).click()
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
}

async function toEnglish(page: Page) {
  await page.getByRole('button', { name: /تغيير اللغة|Change language/ }).click()
  await page.getByRole('menuitemradio', { name: 'English' }).click()
  await expect(page.locator('html')).toHaveAttribute('dir', 'ltr')
}

test.describe('as the company secretary', () => {
  test.use({ role: 'secretary' })

  test('the overview leads with a sentence and a visible table per committee, and its links open the detail and attendance', async ({
    app,
    page,
  }) => {
    await app.visit('/')
    await page
      .getByRole('navigation', { name: 'Main navigation' })
      .first()
      .getByRole('link', { name: 'Committee analytics' })
      .click()
    await expect(page.getByRole('heading', { level: 1, name: 'Committee analytics' })).toBeVisible()
    await expect(page.getByText(/^Showing /)).toBeVisible()

    const credit = page.getByRole('figure', { name: 'Management Credit Committee' })
    await expect(credit).toBeVisible()
    const table = credit.getByRole('table', { name: 'Management Credit Committee' })
    await expect(table).toBeVisible()
    await expect(table.getByRole('rowheader')).toHaveText([
      'Attendance',
      'Actions overdue now',
      'Actions closed on time',
      'Time to decision',
      'Items deferred',
      'Papers on time',
    ])
    await expect(credit.getByRole('button', { name: 'Show as table' })).toHaveCount(0)
    for (const bar of await credit.locator('[data-bar]').all()) {
      await expect(bar).toHaveAttribute('aria-hidden', 'true')
    }
    await app.checkA11y()

    await toArabic(page)
    await expect(page.getByRole('columnheader', { name: 'المقياس' }).first()).toBeVisible()
    await app.checkA11y()
    await toEnglish(page)

    // The committee's detail (flow AW), then its attendance per member (flow AV).
    await credit.getByRole('link', { name: 'Committee detail' }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'Management Credit Committee' })).toBeVisible()
    for (const name of [
      'Time from submission to decision',
      'Items deferred',
      'Actions overdue by owner',
      'Papers on time',
    ]) {
      await expect(page.getByRole('figure', { name })).toBeVisible()
    }
    await expect(page.getByRole('figure', { name: 'Papers on time' }).getByRole('table')).toBeVisible()
    await app.checkA11y()

    await page.getByRole('link', { name: 'Attendance per member' }).first().click()
    await expect(
      page.getByRole('heading', { level: 1, name: 'Management Credit Committee — attendance' }),
    ).toBeVisible()
    const members = page.getByRole('table', { name: 'Management Credit Committee — attendance' })
    await expect(members).toBeVisible()
    await expect(members.getByRole('columnheader', { name: 'Member' })).toHaveAttribute('aria-sort', 'ascending')
    await expect(page.getByRole('button', { name: 'Export for the annual report' })).toBeEnabled()
    await app.checkA11y()

    // Sorting by rate is a button in the header; the address keeps it.
    await members.getByRole('button', { name: 'Rate' }).click()
    await expect(page).toHaveURL(/sort=rate/)
    await expect(members.getByRole('columnheader', { name: 'Rate' })).toHaveAttribute('aria-sort', 'descending')
  })
})

test.describe('as a member', () => {
  test.use({ role: 'headFinance' })

  test('a member reads their own attendance row and the committee total, without an export', async ({ app, page }) => {
    await app.visit('/analytics/committees')
    const credit = page.getByRole('figure', { name: 'Management Credit Committee' })
    await expect(credit).toBeVisible()
    await credit.getByRole('link', { name: 'Attendance per member' }).click()
    await expect(page.getByText(/^(Your attendance: |You held no seat at a meeting in this period\.)/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Export for the annual report' })).toHaveCount(0)
    await app.checkA11y()
  })
})
