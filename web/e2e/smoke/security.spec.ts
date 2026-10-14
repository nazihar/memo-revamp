import { readFileSync } from 'node:fs'
import { expect, test } from '../fixtures'

const expected = Object.fromEntries(
  Object.entries(
    JSON.parse(readFileSync(new URL('../../security-headers.json', import.meta.url), 'utf8')) as Record<string, string>,
  ).filter(([name]) => !name.startsWith('$')),
)

test('documents are served with the strict security headers', async ({ request }) => {
  const response = await request.get('/')
  expect(response.ok()).toBe(true)
  for (const [name, value] of Object.entries(expected)) {
    expect(response.headers()[name.toLowerCase()], name).toBe(value)
  }
  expect(response.headers()['content-security-policy']).not.toContain('unsafe-inline')
  expect(response.headers()['content-security-policy']).not.toContain('unsafe-eval')
})

test('the built index.html contains no inline scripts or styles', async ({ request }) => {
  const html = await (await request.get('/')).text()
  const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)]
  expect(scripts.length).toBeGreaterThan(0)
  for (const [, attributes, body] of scripts) {
    expect(attributes, 'every script is external').toMatch(/\ssrc=/)
    expect(body?.trim(), 'no inline script body').toBe('')
  }
  expect(html).not.toMatch(/<style\b/)
  expect(html).not.toMatch(/\sstyle=/)
})

test('only preference keys are kept in Web Storage; no tokens', async ({ app, page }) => {
  await app.visit('/')
  await page.getByRole('button', { name: /Change theme/ }).click()
  await page.getByRole('menuitemradio', { name: 'Dark' }).click()
  const storage = await page.evaluate(() => ({
    local: Object.keys(localStorage),
    session: Object.keys(sessionStorage),
  }))
  expect(storage.local.filter((k) => !['memo.theme', 'memo.locale'].includes(k))).toEqual([])
  expect(storage.session).toEqual([])
})

test('the app loads nothing from third-party origins', async ({ app, page }) => {
  const origins = new Set<string>()
  page.on('request', (req) => origins.add(new URL(req.url()).origin))
  await app.visit('/')
  expect([...origins].filter((o) => o !== new URL(page.url()).origin)).toEqual([])
})
