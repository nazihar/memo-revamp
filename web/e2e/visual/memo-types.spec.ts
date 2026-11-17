import type { Page } from '@playwright/test'
import type {
  ClassificationLevel,
  DefinitionChoice,
  MemoField,
  MemoType,
  MemoTypeForm,
  MemoTypeOption,
  MemoTypeVersionResponse,
  NumberingPreview,
} from '../../src/api/generated/types.gen'
import { expect, test } from '../fixtures'

/**
 * Visual regression (nightly) of the Phase 3 E01 screens (test plan, T8): the memo-type page on its Form tab (field list
 * and live preview), light/dark × LTR/RTL; and the new-memo form with the type chosen and its details, LTR/RTL and on a
 * phone. The API is mocked with fixed data typed by the generated API types; baselines are rendered on Linux in the
 * Playwright container (web/e2e/README.md).
 */
const variants = [
  { name: 'light-ltr', theme: 'light', locale: 'en' },
  { name: 'dark-ltr', theme: 'dark', locale: 'en' },
  { name: 'light-rtl', theme: 'light', locale: 'ar' },
  { name: 'dark-rtl', theme: 'dark', locale: 'ar' },
] as const

const fields: MemoField[] = [
  { key: 'customer', label: 'Customer', type: 'TEXT', required: true, attribute: false, inbox: false, rules: {} },
  {
    key: 'amount',
    label: 'Facility amount',
    help: 'The total limit asked for.',
    type: 'AMOUNT',
    required: true,
    attribute: true,
    inbox: false,
    rules: { currencies: ['BDT', 'USD'] },
  },
  {
    key: 'purpose',
    label: 'Purpose',
    type: 'CHOICE',
    required: true,
    attribute: true,
    inbox: false,
    rules: {
      choices: [
        { key: 'working-capital', label: 'Working capital' },
        { key: 'term-loan', label: 'Term loan' },
        { key: 'trade', label: 'Trade finance' },
      ],
    },
  },
  { key: 'tenor', label: 'Tenor (months)', type: 'NUMBER', required: false, attribute: false, inbox: false, rules: {} },
]

const version: MemoTypeVersionResponse = {
  number: 3,
  state: 'DRAFT',
  revision: 2,
  name: 'Credit proposal',
  code: 'CP',
  definitionKey: 'credit-authority',
  routePolicy: 'FIXED',
  allowedApprovers: {},
  allowedNames: {},
  doaCheck: false,
  stepUpOnApprove: true,
  fields,
  raiseScope: {},
  numbering: {
    series: 'credit',
    parts: [
      { kind: 'TEXT', text: 'CR/' },
      { kind: 'FINANCIAL_YEAR', style: 'LONG' },
      { kind: 'TEXT', text: '/' },
      { kind: 'SEQUENCE', digits: 4 },
    ],
    reset: 'FINANCIAL_YEAR',
    countPer: 'SERIES',
    moment: 'ON_SUBMIT',
    fyStartMonth: 7,
  },
  retention: { years: 10, from: 'CLOSURE' },
  attachmentRules: { required: false, min: 0 },
  createdAt: '2026-11-10T08:00:00Z',
  createdBy: { id: 'u1', name: 'Demo Admin' },
}

const type: MemoType = {
  id: 't1',
  name: 'Credit proposal',
  code: 'CP',
  active: true,
  publishedVersion: 2,
  definitionKey: 'credit-authority',
  routePolicy: 'FIXED',
  stepUpOnApprove: true,
  doaCheck: false,
  allowedApprovers: {},
  allowedNames: {},
  version: 4,
  pending: { number: 3, state: 'DRAFT' },
}

const options: MemoTypeOption[] = [
  { id: 't0', name: 'General memo', hasFields: false, numberingMoment: 'ON_SUBMIT', stepUpOnApprove: true },
  { id: 't1', name: 'Credit proposal', hasFields: true, numberingMoment: 'ON_SUBMIT', stepUpOnApprove: true },
  { id: 't2', name: 'Procurement', hasFields: true, numberingMoment: 'ON_APPROVAL', stepUpOnApprove: true },
]

const form: MemoTypeForm = { id: 't1', name: 'Credit proposal', version: 2, numberingMoment: 'ON_SUBMIT', fields }

async function setUp(page: Page, theme: string, locale: string) {
  await page.addInitScript(
    ({ t, l }) => {
      localStorage.setItem('memo.theme', t)
      localStorage.setItem('memo.locale', l)
    },
    { t: theme, l: locale },
  )
  await page.clock.setFixedTime(new Date('2026-11-14T10:00:05Z'))
  await page.route('**/api/v1/auth/session', (route) =>
    route.fulfill({
      json: {
        state: 'AUTHENTICATED',
        organisation: 'Demo Organization',
        user: { id: 'u1', email: 'admin@demo.example', displayName: 'Demo Admin', tenantAdmin: true },
      },
    }),
  )
  await page.route('**/api/v1/me/access', (route) =>
    route.fulfill({
      json: {
        permissions: ['directory.read', 'memo.create', 'memo.types.manage', 'memo.types.approve'].map((key) => ({
          key,
          tenantWide: true,
          scopeUnitIds: [],
        })),
        clearance: { rank: 1, name: 'Internal' },
      },
    }),
  )
  await page.route('**/api/v1/admin/memo-types', (route) => route.fulfill({ json: [type] }))
  await page.route('**/api/v1/admin/memo-types/t1/versions', (route) =>
    route.fulfill({ json: [version, { ...version, number: 2, state: 'PUBLISHED', revision: 5 }] }),
  )
  await page.route('**/api/v1/admin/memo-types/t1/versions/*', (route) => route.fulfill({ json: version }))
  await page.route('**/api/v1/admin/memo-types/definitions', (route) =>
    route.fulfill({
      json: [{ key: 'credit-authority', name: 'Credit under authority', routed: false }] satisfies DefinitionChoice[],
    }),
  )
  await page.route('**/api/v1/admin/memo-types/numbering/preview', (route) =>
    route.fulfill({
      json: { samples: ['CR/2026-27/0001', 'CR/2027-28/0001'], problems: [] } satisfies NumberingPreview,
    }),
  )
  await page.route('**/api/v1/authz/classification-levels', (route) =>
    route.fulfill({
      json: [
        { id: 'c0', name: 'Public', rank: 0 },
        { id: 'c1', name: 'Internal', rank: 1 },
      ] as ClassificationLevel[],
    }),
  )
  await page.route('**/api/v1/authz/roles', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/v1/memo-types', (route) => route.fulfill({ json: options }))
  await page.route('**/api/v1/memo-types/t1/form', (route) => route.fulfill({ json: form }))
}

for (const variant of variants) {
  test(`memo type form tab — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/admin/memo-types/t1?version=3')
    await page.getByRole('tab').nth(1).click()
    // The preview shows every field as authors will see it.
    await expect(page.getByRole('radio')).toHaveCount(3)
    await expect(page).toHaveScreenshot(`type-form-${variant.name}.png`, { fullPage: true })
  })
}

for (const variant of [variants[0], variants[2]]) {
  test(`new memo with details — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/memos/new')
    await page.getByRole('radio').nth(1).check()
    await expect(page.getByRole('radio')).toHaveCount(6)
    await expect(page).toHaveScreenshot(`new-memo-${variant.name}.png`, { fullPage: true })
  })
}

test.describe('phone', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('new memo with details on a phone', async ({ app, page }) => {
    await setUp(page, 'light', 'en')
    await app.visit('/memos/new')
    await page.getByRole('radio').nth(1).check()
    await expect(page.getByRole('radio')).toHaveCount(6)
    await expect(page).toHaveScreenshot('phone-new-memo.png', { fullPage: true })
  })
})
