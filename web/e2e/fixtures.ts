import AxeBuilder from '@axe-core/playwright'
import { test as base, expect, type Page } from '@playwright/test'
import { storageStateFor, type Role } from './roles'

/**
 * WebKit (Playwright 1.64's build) reports CSP violations for its own internals: an inline style in the shadow tree of
 * every native `<select>`, and a style element when a modal `<dialog>` opens. The events target the document or the
 * `<select>` itself (which carries no style attribute of ours). Our code is the same in every browser, and Chromium and
 * Firefox still catch any inline style of ours; a violation on one of our elements is still reported in WebKit too.
 */
const WEBKIT_INTERNAL = [
  /^Content-Security-Policy violation: style-src-(attr|elem) inline \(browser internal\)$/,
  /^Refused to apply a stylesheet because its hash, its nonce, or 'unsafe-inline' does not appear in the style-src directive/,
]

/** WCAG 2.2 AA tag set used for every page visit (TESTING_STRATEGY §4). */
const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

export interface App {
  /** Navigates, waits for the app to settle and fails on serious/critical axe violations. */
  visit: (path: string) => Promise<void>
  /** Runs axe on the current state (e.g. after opening a menu). */
  checkA11y: () => Promise<void>
}

interface Fixtures {
  role: Role
  app: App
  /** CSP violations reported by the browser during the test; asserted empty automatically. */
  cspViolations: string[]
}

async function assertNoA11yViolations(page: Page): Promise<void> {
  // Let enter/exit animations settle: axe measures contrast on what is rendered, and a half-faded menu reports
  // false contrast failures (observed in CI).
  // Infinite animations (loading skeletons) never finish and are ignored.
  await page.waitForFunction(() =>
    document.getAnimations().every((a) => a.playState !== 'running' || a.effect?.getTiming().iterations === Infinity),
  )
  const results = await new AxeBuilder({ page }).withTags(AXE_TAGS).analyze()
  const serious = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')
  expect(
    serious.map((v) => `${v.id}: ${v.help} → ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`),
    'axe serious/critical violations',
  ).toEqual([])
}

export const test = base.extend<Fixtures>({
  role: ['anonymous', { option: true }],

  // Role → storage state written by the global setup's real sign-ins (Phase 0 Q5).
  storageState: async ({ role }, use) => {
    await use(storageStateFor(role))
  },

  cspViolations: [
    async ({ page, browserName }, use) => {
      const violations: string[] = []
      page.on('console', (msg) => {
        const text = msg.text()
        if (!/Content[- ]Security[- ]Policy/i.test(text)) return
        if (browserName === 'webkit' && WEBKIT_INTERNAL.some((pattern) => pattern.test(text))) return
        violations.push(text)
      })
      await page.addInitScript(() => {
        document.addEventListener('securitypolicyviolation', (e) => {
          // WebKit reports styles of a native <select>'s own shadow tree on the document or on the <select> itself.
          const internal =
            e.target === document || (e.target instanceof HTMLSelectElement && !e.target.hasAttribute('style'))
          const element = e.target instanceof Element ? ` (${e.target.outerHTML.slice(0, 80)})` : ''
          const where = internal ? ' (browser internal)' : element
          console.error(`Content-Security-Policy violation: ${e.violatedDirective} ${e.blockedURI}${where}`)
        })
      })
      await use(violations)
      expect(violations, 'CSP violations').toEqual([])
    },
    { auto: true },
  ],

  app: async ({ page }, use) => {
    await use({
      visit: async (path) => {
        await page.goto(path)
        await page.waitForLoadState('networkidle')
        await assertNoA11yViolations(page)
      },
      checkA11y: () => assertNoA11yViolations(page),
    })
  },
})

export { expect }
