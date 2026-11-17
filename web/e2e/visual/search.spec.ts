import type { Page } from '@playwright/test'
import type { SavedSearchView, SearchPageView, SuggestionView } from '../../src/api/generated/types.gen'
import { expect, test } from '../fixtures'

/**
 * Visual regression (nightly) of search (Phase 3 E05 T6, flows H1–H3): the Search page with results, filters and chips,
 * light/dark × LTR/RTL; the capped count and the empty state; the phone layout with the filters in a sheet; the top-bar
 * box open with suggestions and saved searches; and the Saved searches page. Search is POST (the words never travel in a
 * URL); the page's words are in the address fragment. The API is mocked with fixed data typed by the generated client.
 */
const variants = [
  { name: 'light-ltr', theme: 'light', locale: 'en' },
  { name: 'dark-ltr', theme: 'dark', locale: 'en' },
  { name: 'light-rtl', theme: 'light', locale: 'ar' },
  { name: 'dark-rtl', theme: 'dark', locale: 'ar' },
] as const

const results: SearchPageView = {
  results: [
    {
      memoId: 'm42',
      number: 'M-2026-00042',
      subject: 'Office lease extension',
      typeId: 't1',
      typeName: 'General memo',
      status: 'IN_REVIEW',
      unitId: 'u1',
      unitName: 'Credit Division',
      updatedAt: '2026-11-09T09:30:00Z',
      snippet: [
        { text: '…the revised ', match: false },
        { text: 'rent schedule', match: true },
        { text: ' for floors 3 and 4…', match: false },
      ],
      foundIn: { kind: 'NOTE', name: 'Demo Head of Credit' },
      origin: 'NATIVE',
    },
    {
      memoId: 'm1',
      number: 'CR/2026/0001',
      subject: 'Credit line renewal for Meghna Agro',
      typeId: 't2',
      typeName: 'Credit proposal',
      status: 'APPROVED',
      unitId: 'u1',
      unitName: 'Credit Division',
      updatedAt: '2026-11-02T11:00:00Z',
      snippet: [
        { text: '…annex B, ', match: false },
        { text: 'rent schedule', match: true },
        { text: ' attached…', match: false },
      ],
      foundIn: { kind: 'ATTACHMENT', name: 'annex-b.pdf' },
      origin: 'NATIVE',
    },
    {
      memoId: 'm7',
      number: null,
      subject: 'Branch rent review 2027',
      typeId: 't1',
      typeName: 'General memo',
      status: 'DRAFT',
      unitId: null,
      unitName: null,
      updatedAt: '2026-10-28T08:15:00Z',
      snippet: [{ text: 'Branch rent review 2027', match: false }],
      foundIn: { kind: 'DETAILS' },
      origin: 'NATIVE',
    },
  ],
  count: 23,
  capped: false,
  facets: {
    status: [
      { value: 'IN_REVIEW', label: 'IN_REVIEW', count: 12 },
      { value: 'APPROVED', label: 'APPROVED', count: 9 },
      { value: 'RETURNED', label: 'RETURNED', count: 2 },
    ],
    type: [
      { value: 't1', label: 'General memo', count: 17 },
      { value: 't2', label: 'Credit proposal', count: 6 },
    ],
    unit: [{ value: 'u1', label: 'Credit Division', count: 21 }],
  },
  cursor: 'next',
}

const capped: SearchPageView = { ...results, count: 2000, capped: true }
const empty: SearchPageView = {
  results: [],
  count: 0,
  capped: false,
  facets: { status: [], type: [], unit: [] },
  cursor: null,
}

const saved: SavedSearchView[] = [
  {
    id: 's1',
    name: 'Credit memos this quarter',
    criteria: {
      q: 'credit',
      types: ['t2'],
      statuses: [],
      units: [],
      from: '2026-10-01',
      to: null,
      amountMin: null,
      amountMax: null,
      sources: ['METADATA', 'NOTES', 'DOCUMENTS'],
      sort: 'RELEVANCE',
    },
    version: 0,
    createdAt: '2026-11-01T08:00:00Z',
    updatedAt: '2026-11-01T08:00:00Z',
  },
  {
    id: 's2',
    name: 'Rent in review',
    criteria: {
      q: 'rent',
      types: [],
      statuses: ['IN_REVIEW'],
      units: [],
      from: null,
      to: null,
      amountMin: null,
      amountMax: null,
      sources: ['METADATA', 'NOTES', 'DOCUMENTS'],
      sort: 'NEWEST',
    },
    version: 2,
    createdAt: '2026-10-20T08:00:00Z',
    updatedAt: '2026-11-05T08:00:00Z',
  },
]

const suggestions: SuggestionView[] = [
  { memoId: 'm42', number: 'M-2026-00042', subject: 'Office lease extension' },
  { memoId: 'm7', number: null, subject: 'Branch rent review 2027' },
]

async function setUp(page: Page, theme: string, locale: string, page1: SearchPageView = results) {
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
  await page.route('**/api/v1/search', (route) => route.fulfill({ json: page1 }))
  await page.route('**/api/v1/search/suggest', (route) => route.fulfill({ json: suggestions }))
  await page.route('**/api/v1/saved-searches', (route) => route.fulfill({ json: saved }))
  // The page behind the box (an empty inbox).
  await page.route('**/api/v1/inbox/tasks?*', (route) => route.fulfill({ json: { items: [], nextCursor: null } }))
}

const withFilters = '/search?status=%5B%22IN_REVIEW%22%5D&unit=%5B%22u1%22%5D#q=rent%20schedule'

for (const variant of variants) {
  test(`search results with filters — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit(withFilters)
    await expect(page.getByRole('link', { name: /Office lease extension/ })).toBeVisible()
    await expect(page.locator('mark').first()).toBeVisible()
    await expect(page).toHaveScreenshot(`search-${variant.name}.png`, { fullPage: true })
  })
}

test('search, capped count — light-ltr', async ({ app, page }) => {
  await setUp(page, 'light', 'en', capped)
  await app.visit('/search#q=rent')
  await expect(page.getByText('2,000+', { exact: false }).first()).toBeVisible()
  await expect(page).toHaveScreenshot('search-capped-light-ltr.png', { fullPage: true })
})

for (const variant of [variants[0], variants[2]]) {
  test(`search, nothing found — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale, empty)
    await app.visit('/search?status=%5B%22IN_REVIEW%22%5D#q=zeppelin')
    await expect(page.getByRole('button', { name: /Clear filters|مسح/ }).first()).toBeVisible()
    await expect(page).toHaveScreenshot(`search-empty-${variant.name}.png`, { fullPage: true })
  })
}

test('the search box open, with suggestions and saved searches — light-ltr', async ({ app, page }) => {
  await setUp(page, 'light', 'en')
  await app.visit('/inbox')
  const box = page.getByRole('combobox').first()
  await box.click()
  await box.fill('rent')
  await expect(page.getByRole('option', { name: /Office lease extension/ })).toBeVisible()
  await expect(page.getByRole('option', { name: /Rent in review/ })).toBeVisible()
  await expect(page).toHaveScreenshot('search-box-light-ltr.png')
})

for (const variant of [variants[0], variants[3]]) {
  test(`saved searches — ${variant.name}`, async ({ app, page }) => {
    await setUp(page, variant.theme, variant.locale)
    await app.visit('/search/saved')
    await expect(page.getByRole('link', { name: 'Credit memos this quarter' })).toBeVisible()
    await expect(page).toHaveScreenshot(`saved-searches-${variant.name}.png`, { fullPage: true })
  })
}

test.describe('phone', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  for (const variant of [variants[0], variants[2]]) {
    test(`search on a phone, with the filters open — ${variant.name}`, async ({ app, page }) => {
      await setUp(page, variant.theme, variant.locale)
      await app.visit(withFilters)
      await expect(page.getByRole('link', { name: /Office lease extension/ })).toBeVisible()
      await expect(page).toHaveScreenshot(`phone-search-${variant.name}.png`)
      await page.getByRole('button', { name: /\(2\)/ }).click()
      await expect(page.getByRole('dialog')).toBeVisible()
      await expect(page).toHaveScreenshot(`phone-search-filters-${variant.name}.png`)
    })
  }
})
