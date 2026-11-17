import { mkdirSync, renameSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { request, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { apiSignIn, DEMO_USERS } from './auth'

/**
 * Per-role authenticated sessions as Playwright storage states (TESTING_STRATEGY §4, Phase 0 Q5). The demo
 * organisation's users sign in through the real API, password and TOTP; there is deliberately no test-only auth bypass
 * in the application. E04 added the org administrator, a branch administrator scoped to North Region, and the auditor;
 * later phases add initiator, approver, secretary and director.
 *
 * A step-up moves the session to a new id (ASVS V7.2.4; `SessionStore.upgrade`), so tests that run at the same time
 * cannot share one session per role: the first one asked to confirm would sign the others out. Each worker slot
 * (`parallelIndex`, which a worker replacing a failed one keeps) of each project has its own session per role in
 * `e2e/.auth/<project>/<role>.<slot>.json` (git-ignored). Projects do not share them: a browser saves cookies with its
 * own attributes (WebKit saves the session cookie as SameSite=None without Secure, which Chromium then drops). A session
 * is checked before every use and signed in again only when it has gone, and every context saves its session back after
 * a sign-in or step-up call ([keepSession]).
 */
export type SignedInRole =
  | 'tenantAdmin'
  | 'reviewer'
  | 'orgAdmin'
  | 'branchAdmin'
  | 'auditor'
  | 'user'
  | 'headCredit'
  | 'director'
  | 'headFinance'
export type Role = 'anonymous' | SignedInRole

function sessionFile(role: SignedInRole): string {
  const { project, parallelIndex } = test.info()
  return fileURLToPath(new URL(`./.auth/${project.name}/${role}.${String(parallelIndex)}.json`, import.meta.url))
}

function baseURL(): string {
  return test.info().project.use.baseURL ?? 'http://localhost:4173'
}

/** Whether the session stored in [file] is still signed in as [email]. */
async function signedIn(file: string, email: string): Promise<boolean> {
  // No file yet, or an unreadable one: not signed in.
  const context = await request.newContext({ baseURL: baseURL(), storageState: file }).catch(() => undefined)
  if (!context) return false
  try {
    const response = await context.get('/api/v1/auth/session')
    if (!response.ok()) return false
    const session = (await response.json()) as { state: string; user?: { email: string } }
    return session.state === 'AUTHENTICATED' && session.user?.email === email
  } finally {
    await context.dispose()
  }
}

/** Written whole or not at all: a half-written file would read as signed out and cost a sign-in. */
function writeState(file: string, state: object): void {
  mkdirSync(dirname(file), { recursive: true })
  writeFileSync(`${file}.tmp`, JSON.stringify(state))
  renameSync(`${file}.tmp`, file)
}

/** The storage state of [role] for this worker slot, signed in again if its session has gone; undefined for anonymous. */
export async function storageStateFor(role: Role): Promise<string | undefined> {
  if (role === 'anonymous') return undefined
  const file = sessionFile(role)
  if (await signedIn(file, DEMO_USERS[role])) return file
  // Inside a test, a new request context takes the test's own storage state unless given one: a sign-in carrying the
  // test role's cookie would end that session (a sign-in always starts a new one).
  const context = await request.newContext({ baseURL: baseURL(), storageState: { cookies: [], origins: [] } })
  try {
    await apiSignIn(context, baseURL(), DEMO_USERS[role])
    writeState(file, await context.storageState())
  } finally {
    await context.dispose()
  }
  return file
}

const saving = new Set<Promise<void>>()

/**
 * After each sign-in or step-up call of [context] (both move the session to a new id), saves its session as [role]'s
 * for this worker slot, so the next test starts from the session as it is now.
 */
export function keepSession(context: BrowserContext, role: SignedInRole): void {
  const file = sessionFile(role)
  context.on('requestfinished', (req) => {
    if (req.method() !== 'POST' || !new URL(req.url()).pathname.startsWith('/api/v1/auth/')) return
    const save: Promise<void> = context
      .storageState()
      .then((state) => {
        writeState(file, state)
      })
      // The context closed first: the next use checks the file and signs in again if it must.
      .catch(() => undefined)
      .finally(() => saving.delete(save))
    saving.add(save)
  })
}

/** Waits for sessions still being saved; the fixtures call it when a test ends. */
export async function sessionsSaved(): Promise<void> {
  await Promise.all([...saving])
}

/** A page in a new context signed in as [role], for a second person in a journey; its session is kept like the test's. */
export async function pageAs(browser: Browser, role: SignedInRole): Promise<Page> {
  const context = await browser.newContext({ storageState: await storageStateFor(role) })
  keepSession(context, role)
  return context.newPage()
}
