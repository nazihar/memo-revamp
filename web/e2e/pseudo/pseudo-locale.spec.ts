import { expect, test } from '../fixtures'

/**
 * Pseudo-localisation (TESTING_STRATEGY §4, nightly): in en-XA every translated string is accented and wrapped in [ ].
 * Any visible text without the brackets is a hard-coded string (or unexpected data) and fails the run.
 */
test.use({ locale: 'en-US' })

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('memo.locale', 'en-XA')
  })
  // An anonymous visitor: the organisation name is data, not a translation, so none is shown here.
  await page.route('**/api/v1/auth/session', (route) =>
    route.fulfill({ json: { state: 'ANONYMOUS', organisation: '' } }),
  )
  // Fixed API data: component *names* come from translations, the status text too; nothing else is user-visible.
  await page.route('**/api/v1/health', (route) =>
    route.fulfill({
      json: {
        status: 'UP',
        checkedAt: '2026-10-14T10:00:00Z',
        components: [
          { name: 'database', status: 'UP' },
          { name: 'mail', status: 'DISABLED' },
        ],
      },
    }),
  )
})

test('every visible string on the shell and the status page is externalised', async ({ app, page }) => {
  await app.visit('/status')
  await expect(page.getByRole('heading', { level: 1 })).toContainText('[')

  const unlocalised = await page.evaluate(() => {
    const allowed = /^[\s\d.,:/\-–—()\][]*$/ // punctuation, digits (dates, numbers)
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)
    const offenders: string[] = []
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const text = node.textContent?.trim() ?? ''
      const el = node.parentElement
      if (!text || !el || el.closest('[lang]:not(html)') || el.closest('time') || el.offsetParent === null) continue
      // Translated messages render as "[" + accented text + "]", possibly split across nodes around placeholders.
      if (!allowed.test(text) && !/[À-ɏḀ-ỿ]/.test(text)) offenders.push(text)
    }
    return offenders
  })
  expect(unlocalised, 'hard-coded (non-pseudo-localised) visible text').toEqual([])
})
