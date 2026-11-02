import { expect, test } from '../fixtures'

/**
 * Visual regression (nightly) of the org directory: tree, unit path and positions, light/dark × LTR/RTL. The API is
 * mocked with fixed data; baselines are rendered on Linux in the Playwright container (web/e2e/README.md).
 */
const variants = [
  { name: 'light-ltr', theme: 'light', locale: 'en' },
  { name: 'dark-ltr', theme: 'dark', locale: 'en' },
  { name: 'light-rtl', theme: 'light', locale: 'ar' },
  { name: 'dark-rtl', theme: 'dark', locale: 'ar' },
] as const

const unit = (id: string, code: string, name: string, parentId?: string) => ({
  id,
  legalEntityId: 'e1',
  ...(parentId ? { parentId } : {}),
  code,
  name,
  validFrom: '2026-01-01',
  version: 0,
})
const units = [
  unit('bod', 'BOD', 'Board of Directors'),
  unit('ceo', 'CEO', 'Office of the Managing Director', 'bod'),
  unit('crd', 'CRD', 'Credit Division', 'ceo'),
  unit('ops', 'OPS', 'Operations Division', 'ceo'),
  unit('fin', 'FIN', 'Finance Division', 'ceo'),
]
const position = (id: string, title: string, head: boolean) => ({
  id,
  unitId: 'crd',
  title,
  head,
  validFrom: '2026-01-01',
  version: 0,
})

for (const variant of variants) {
  test(`org directory — ${variant.name}`, async ({ app, page }) => {
    await page.addInitScript(
      ({ theme, locale }) => {
        localStorage.setItem('memo.theme', theme)
        localStorage.setItem('memo.locale', locale)
      },
      { theme: variant.theme, locale: variant.locale },
    )
    await page.clock.setFixedTime(new Date('2026-10-14T10:00:05Z'))
    await page.route('**/api/v1/auth/session', (route) =>
      route.fulfill({
        json: {
          state: 'AUTHENTICATED',
          organisation: 'Demo Organization',
          user: { id: 'u0', email: 'orgadmin@demo.example', displayName: 'Demo Org Admin', tenantAdmin: false },
        },
      }),
    )
    await page.route('**/api/v1/me/access', (route) =>
      route.fulfill({
        json: {
          permissions: [
            { key: 'directory.read', tenantWide: true, scopeUnitIds: [] },
            { key: 'org.manage', tenantWide: true, scopeUnitIds: [] },
          ],
          clearance: { rank: 0, name: 'Public' },
        },
      }),
    )
    await page.route('**/api/v1/org/legal-entities', (route) =>
      route.fulfill({ json: [{ id: 'e1', name: 'Demo Bank PLC', validFrom: '2026-01-01', version: 0 }] }),
    )
    await page.route('**/api/v1/org/units?*', (route) => route.fulfill({ json: units }))
    await page.route('**/api/v1/org/units', (route) => route.fulfill({ json: units }))
    await page.route('**/api/v1/org/units/crd', (route) =>
      route.fulfill({ json: { unit: units[2], path: [units[0], units[1]], canManage: true } }),
    )
    await page.route('**/api/v1/org/units/crd/positions', (route) =>
      route.fulfill({
        json: [
          {
            position: position('p1', 'Head of Credit', true),
            holder: { id: 'u1', displayName: 'Farhana Rahman', email: 'farhana.rahman@demo.example' },
            holderSince: '2026-01-01',
          },
          { position: position('p2', 'Credit Analyst', false) },
          {
            position: position('p3', 'Relationship Manager', false),
            holder: { id: 'u2', displayName: 'Tanvir Ahmed', email: 'tanvir.ahmed@demo.example' },
            holderSince: '2026-03-15',
          },
        ],
      }),
    )
    await app.visit('/org?unit=crd')
    await expect(page.getByRole('table', { name: /.+/ }).getByRole('row')).toHaveCount(4)
    await expect(page).toHaveScreenshot(`${variant.name}.png`, { fullPage: true })
  })
}
