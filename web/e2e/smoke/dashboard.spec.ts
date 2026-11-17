import { expect, test } from '../fixtures'

/**
 * The Approvals dashboard (Phase 3 E05 T7, test plan "Charts render with no CSP violation"). The charts are Recharts
 * SVG; the CSP-violation fixture (fixtures.ts) fails the test on any report, so drawing them, opening a table and
 * changing the scope must not need an inline style, a <style> element or eval. Runs against the demo stack as the Head
 * of Credit, who heads Credit Division and so has a unit scope as well as Me.
 */
test.describe('as a unit head', () => {
  test.use({ role: 'headCredit' })

  test('the dashboard draws its charts with no CSP violation, for Me and for the unit', async ({ app, page }) => {
    await app.visit('/dashboards/approvals')
    await expect(page.getByRole('heading', { level: 1, name: 'Approvals dashboard' })).toBeVisible()
    await expect(page.getByText(/^Showing: Me, /)).toBeVisible()

    const age = page.getByRole('figure', { name: 'Pending by age' })
    const throughput = page.getByRole('figure', { name: 'Throughput per week' })
    // Pending by age and throughput always draw (zero values included); cycle time draws when memos were decided.
    await expect(age.locator('svg.recharts-surface')).toBeVisible()
    await expect(throughput.locator('svg.recharts-surface')).toBeVisible()
    await expect(throughput.locator('.recharts-line')).toHaveCount(2)

    // Keyboard: the chart takes focus, and the arrow keys move its tooltip (Recharts' accessibility layer).
    await age.locator('svg.recharts-surface').focus()
    await page.keyboard.press('ArrowRight')
    await age.getByRole('button', { name: 'Show as table' }).click()
    await expect(age.getByRole('table', { name: 'Pending by age' })).toBeVisible()
    await app.checkA11y()

    const scope = page.getByRole('combobox', { name: 'Scope' })
    await expect(scope.getByRole('option', { name: 'Credit Division' })).toBeAttached()
    await scope.selectOption({ label: 'Credit Division' })
    await expect(page.getByText(/^Showing: Credit Division, /)).toBeVisible()
    await expect(page.getByRole('figure', { name: 'Pending by person' })).toBeVisible()
    await expect(age.locator('svg.recharts-surface')).toBeVisible()
    await page.waitForLoadState('networkidle')
    await app.checkA11y()
  })
})
