import { createHmac } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, type APIRequestContext, type Locator, type Page } from '@playwright/test'

/**
 * Real sign-ins for e2e (TESTING_STRATEGY §4, Phase 0 Q5): the demo users of the demo organisation, with the password
 * and TOTP secret generated into the git-ignored `deploy/compose/.env` (scripts/dev.mjs). Nothing here bypasses
 * authentication; the codes are computed exactly like an authenticator app does.
 */
export const DEMO_USERS = {
  tenantAdmin: 'admin@demo.example',
  reviewer: 'reviewer@demo.example',
  orgAdmin: 'orgadmin@demo.example',
  branchAdmin: 'branchadmin@demo.example',
  auditor: 'auditor@demo.example',
  user: 'user@demo.example',
  headCredit: 'head.credit@demo.example',
  director: 'director@demo.example',
  headFinance: 'head.finance@demo.example',
  // The company secretary of every demo committee (Phase 4 E01 T6), who triages their papers (E02 T4).
  secretary: 'secretary@demo.example',
} as const

const envFile = fileURLToPath(new URL('../../deploy/compose/.env', import.meta.url))

function fromEnvFile(name: string): string | undefined {
  if (!existsSync(envFile)) return undefined
  const line = readFileSync(envFile, 'utf8')
    .split(/\r?\n/)
    .find((l) => l.startsWith(`${name}=`))
  return line?.slice(name.length + 1).trim()
}

export function demoSecret(name: 'MEMO_DEMO_PASSWORD' | 'MEMO_DEMO_TOTP_SECRET'): string {
  const value = process.env[name] ?? fromEnvFile(name)
  if (!value) throw new Error(`${name} is not set (run pnpm stack:up, which generates deploy/compose/.env)`)
  return value
}

export const MAILPIT_URL = process.env.MAILPIT_URL ?? 'http://localhost:8025'

function base32Decode(value: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = 0
  let acc = 0
  const out: number[] = []
  for (const char of value.replace(/[\s=]/g, '').toUpperCase()) {
    const index = alphabet.indexOf(char)
    if (index < 0) throw new Error('not Base32')
    acc = (acc << 5) | index
    bits += 5
    if (bits >= 8) {
      out.push((acc >>> (bits - 8)) & 0xff)
      bits -= 8
    }
  }
  return Buffer.from(out)
}

/** RFC 6238 (HMAC-SHA1, 6 digits, 30 s); [stepOffset] picks a neighbouring step inside the server's ±1 window. */
export function totp(secretBase32: string, stepOffset = 0, now = Date.now()): string {
  const step = Math.floor(now / 1000 / 30) + stepOffset
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(step))
  const hash = createHmac('sha1', base32Decode(secretBase32)).update(counter).digest()
  const offset = (hash[hash.length - 1] ?? 0) & 0x0f
  const binary = hash.readUInt32BE(offset) & 0x7fffffff
  return String(binary % 1_000_000).padStart(6, '0')
}

async function csrf(request: APIRequestContext, baseURL: string): Promise<string> {
  await request.get('/api/v1/auth/csrf')
  const state = await request.storageState()
  const token = state.cookies.find((c) => c.name === 'XSRF-TOKEN' && baseURL.includes(c.domain.replace(/^\./, '')))
  return decodeURIComponent(token?.value ?? state.cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? '')
}

/**
 * Tries TOTP codes of [secretBase32] with [attempt] until one is taken. The server takes each 30-second step once per
 * user and accepts the steps next to the current one, so when another sign-in or step-up of the same user has just used
 * the current step, the next step's code works; when both are used, the step after that does once the clock moves on.
 */
async function withTotp(secretBase32: string, attempt: (code: string) => Promise<boolean>): Promise<boolean> {
  for (const offset of [0, 1]) {
    if (await attempt(totp(secretBase32, offset))) return true
  }
  const nextStep = (Math.floor(Date.now() / 30_000) + 1) * 30_000
  await new Promise((resolve) => setTimeout(resolve, nextStep - Date.now() + 500))
  return attempt(totp(secretBase32, 1))
}

/** Signs [email] in through the API: the password, then a TOTP code. */
export async function apiSignIn(request: APIRequestContext, baseURL: string, email: string): Promise<void> {
  const login = await request.post('/api/v1/auth/login', {
    headers: { 'X-XSRF-TOKEN': await csrf(request, baseURL) },
    data: { email, password: demoSecret('MEMO_DEMO_PASSWORD') },
  })
  if (!login.ok()) throw new Error(`sign-in of ${email} failed: ${String(login.status())} ${await login.text()}`)
  const taken = await withTotp(demoSecret('MEMO_DEMO_TOTP_SECRET'), async (code) => {
    const mfa = await request.post('/api/v1/auth/mfa', {
      headers: { 'X-XSRF-TOKEN': await csrf(request, baseURL) },
      data: { code },
    })
    return mfa.ok()
  })
  if (!taken) throw new Error(`second step of ${email} failed`)
}

/** The calls that check a TOTP code: sign-in, MFA enrolment and step-up, for users and operators. */
const CODE_CHECK = /\/auth\/(mfa|mfa\/enrolment\/confirm|step-up)$/

/**
 * Enters a TOTP code where [scope] (a page, or a dialog in it) asks for one, and judges it by the server's answer to the
 * code check, falling back to other steps' codes like [apiSignIn].
 */
export async function enterCode(
  scope: Page | Locator,
  secretBase32: string,
  field: string,
  submit: string,
): Promise<void> {
  const page = 'goto' in scope ? scope : scope.page()
  const taken = await withTotp(secretBase32, async (code) => {
    await scope.getByLabel(field).fill(code)
    const answer = page.waitForResponse(
      (r) => r.request().method() === 'POST' && CODE_CHECK.test(new URL(r.url()).pathname),
    )
    await scope.getByRole('button', { name: submit }).click()
    return (await answer).ok()
  })
  if (!taken) throw new Error('no TOTP code was accepted')
}

/** The newest message to [to] in Mailpit, polled while delivery (a db-scheduler task) catches up. */
export async function latestMailText(request: APIRequestContext, to: string, timeoutMs = 45_000): Promise<string> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const search = await request.get(`${MAILPIT_URL}/api/v1/search`, { params: { query: `to:"${to}"` } })
    if (search.ok()) {
      const body = (await search.json()) as { messages?: { ID: string }[] }
      const id = body.messages?.[0]?.ID
      if (id) {
        const message = await request.get(`${MAILPIT_URL}/api/v1/message/${id}`)
        return ((await message.json()) as { Text: string }).Text
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000))
  }
  throw new Error(`no mail to ${to} within ${String(timeoutMs)} ms`)
}

/**
 * Sensitive actions ask to confirm with a code once the session's last code is older than the organisation's window
 * (ADR-0024), which a long run reaches and a short one may not. Call it right after the action with [done], what the page
 * shows once the action is through (a locator that becomes visible, or a check): it waits for that or for the "Confirm it
 * is you" dialog, so it never looks before the answer has come, and enters the demo code in the dialog's own field.
 */
export async function confirmIfAsked(
  page: Page,
  secret: string,
  done: Locator | (() => Promise<boolean>),
): Promise<void> {
  const dialog = page.getByRole('dialog', { name: 'Confirm it is you' })
  const finished = typeof done === 'function' ? done : () => done.first().isVisible()
  await expect
    .poll(async () => (await dialog.isVisible()) || (await finished()), {
      message: 'the action is done or asks to confirm',
    })
    .toBe(true)
  if (await dialog.isVisible()) await enterCode(dialog, secret, 'Code', 'Confirm')
}
