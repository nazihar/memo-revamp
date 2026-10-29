import { createHmac } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import type { APIRequestContext, Page } from '@playwright/test'

/**
 * Real sign-ins for e2e (TESTING_STRATEGY §4, Phase 0 Q5): the demo users of the demo organisation, with the password
 * and TOTP secret generated into the git-ignored `deploy/compose/.env` (scripts/dev.mjs). Nothing here bypasses
 * authentication; the codes are computed exactly like an authenticator app does.
 */
export const DEMO_USERS = {
  tenantAdmin: 'admin@demo.example',
  user: 'user@demo.example',
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
 * Signs [email] in through the API (password, then TOTP). A code is usable once per 30-second step, and another run
 * may have used the current one: then the next step's code, still inside the server's window, is tried.
 */
export async function apiSignIn(request: APIRequestContext, baseURL: string, email: string): Promise<void> {
  const login = await request.post('/api/v1/auth/login', {
    headers: { 'X-XSRF-TOKEN': await csrf(request, baseURL) },
    data: { email, password: demoSecret('MEMO_DEMO_PASSWORD') },
  })
  if (!login.ok()) throw new Error(`sign-in of ${email} failed: ${String(login.status())} ${await login.text()}`)
  for (const offset of [0, 1]) {
    const mfa = await request.post('/api/v1/auth/mfa', {
      headers: { 'X-XSRF-TOKEN': await csrf(request, baseURL) },
      data: { code: totp(demoSecret('MEMO_DEMO_TOTP_SECRET'), offset) },
    })
    if (mfa.ok()) return
  }
  throw new Error(`second step of ${email} failed`)
}

/** Enters a TOTP code where the page asks for one, falling back to the next step's code when this one was used. */
export async function enterCode(page: Page, secretBase32: string, field: string, submit: string): Promise<void> {
  for (const offset of [0, 1]) {
    await page.getByLabel(field).fill(totp(secretBase32, offset))
    await page.getByRole('button', { name: submit }).click()
    const outcome = await Promise.race([
      page
        .getByRole('alert')
        .waitFor({ timeout: 5_000 })
        .then(() => 'error' as const),
      page
        .getByLabel(field)
        .waitFor({ state: 'detached', timeout: 5_000 })
        .then(() => 'done' as const),
    ]).catch(() => 'done' as const)
    if (outcome === 'done') return
  }
  throw new Error('no TOTP code was accepted')
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
