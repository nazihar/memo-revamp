import { expect, type Browser, type Page } from '@playwright/test'
import { confirmIfAsked, demoSecret } from './auth'
import { docx } from './docx'
import { assertNoA11yViolations } from './fixtures'
import { chooseMemoType } from './memo-type'
import { pageAs } from './roles'

/**
 * Helpers of the Phase 4 E03 meeting journeys (`meeting-agenda.spec`, `meeting-page.spec`): a credit proposal sent to
 * the credit committee, and the agenda API for setting up a meeting a journey only reads.
 */

/** A calendar day [days] from today as `YYYY-MM-DD`, on the browser's clock. */
export function dayFromNow(days: number): string {
  const d = new Date(Date.now() + days * 86_400_000)
  return `${String(d.getFullYear())}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** Opens [button]'s dialog on the memo and confirms it (with a step-up if asked). */
async function act(page: Page, memoUrl: string, button: string) {
  await page.goto(memoUrl)
  await page.getByRole('toolbar', { name: 'Actions' }).getByRole('button', { name: button }).click()
  await page.getByRole('dialog').getByRole('button', { name: button }).click()
  await confirmIfAsked(page, demoSecret('MEMO_DEMO_TOTP_SECRET'), page.getByText('Done.'))
  await expect(page.getByText('Done.')).toBeVisible()
}

/**
 * Demo User's credit proposal of BDT 12 million on [subject], recommended by the head of credit: above the demo's
 * committee threshold, it then waits for the Management Credit Committee (E02 T5). Returns the memo's address.
 */
export async function creditProposalToCommittee(browser: Browser, subject: string): Promise<string> {
  const page = await pageAs(browser, 'user')
  await page.goto('/memos/new')
  await chooseMemoType(page, 'Credit proposal')
  await page.getByLabel('Subject').fill(subject)
  await page.getByRole('textbox', { name: /Customer/ }).fill('Delta Textiles Ltd')
  await page.getByRole('textbox', { name: /Facility amount/ }).fill('12000000')
  await page.getByRole('radio', { name: 'Term loan' }).check()
  await page.getByRole('button', { name: 'Create draft' }).click()
  await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible()
  const memoUrl = page.url()
  await page.getByLabel('Upload the document').setInputFiles({
    name: 'proposal.docx',
    mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    buffer: docx(`Term loan for ${subject}`),
  })
  await expect(page.getByText(/^Version 1 ·/)).toBeVisible()
  await act(page, memoUrl, 'Submit')
  const head = await pageAs(browser, 'headCredit')
  await act(head, memoUrl, 'Recommend')
  await head.context().close()
  await page.context().close()
  return memoUrl
}

/** The agenda and meeting API as the person signed in on [page], with the CSRF token, `If-Match` and idempotency keys. */
export class MeetingApi {
  constructor(private readonly page: Page) {}

  private async token(): Promise<string> {
    await this.page.request.get('/api/v1/auth/csrf')
    const cookie = (await this.page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value ?? ''
    return decodeURIComponent(cookie)
  }

  async get<T>(path: string): Promise<T> {
    const response = await this.page.request.get(`/api/v1${path}`)
    expect(response.ok(), `GET ${path}: ${String(response.status())}`).toBe(true)
    return (await response.json()) as T
  }

  /** POSTs, PATCHes or PUTs [body]; [version] goes as `If-Match`. Answers the JSON and the new ETag's version. */
  async send<T>(
    path: string,
    body: unknown,
    { version, method = 'POST' }: { version?: number; method?: 'POST' | 'PATCH' | 'PUT' } = {},
  ): Promise<T> {
    const headers: Record<string, string> = {
      'X-XSRF-TOKEN': await this.token(),
      'Idempotency-Key': crypto.randomUUID(),
    }
    if (version !== undefined) headers['If-Match'] = `"${String(version)}"`
    const response = await this.page.request.fetch(`/api/v1${path}`, { method, headers, data: body })
    expect(response.ok(), `${method} ${path}: ${String(response.status())} ${await response.text()}`).toBe(true)
    return (await response.json()) as T
  }

  /** Uploads [bytes] as a paper of [meetingId]; the file's id. */
  async upload(meetingId: string, name: string, bytes: Buffer): Promise<string> {
    const response = await this.page.request.post(
      `/api/v1/files?purpose=meeting.paper&owner=${encodeURIComponent(meetingId)}`,
      {
        headers: {
          'X-XSRF-TOKEN': await this.token(),
          'Idempotency-Key': crypto.randomUUID(),
          'Content-Type': 'application/octet-stream',
          'Content-Disposition': `attachment; filename="${name}"`,
        },
        data: bytes,
      },
    )
    expect(response.ok(), `upload ${name}: ${String(response.status())} ${await response.text()}`).toBe(true)
    return ((await response.json()) as { fileId: string }).fileId
  }

  /** Uploads a new version of the paper [fileId]. */
  async replace(fileId: string, name: string, bytes: Buffer): Promise<void> {
    const file = await this.get<{ version: number }>(`/files/${fileId}`)
    const response = await this.page.request.post(`/api/v1/files/${fileId}/versions`, {
      headers: {
        'X-XSRF-TOKEN': await this.token(),
        'Idempotency-Key': crypto.randomUUID(),
        'If-Match': `"${String(file.version)}"`,
        'Content-Type': 'application/octet-stream',
        'Content-Disposition': `attachment; filename="${name}"`,
      },
      data: bytes,
    })
    expect(response.ok(), `replace ${name}: ${String(response.status())} ${await response.text()}`).toBe(true)
  }
}

/** The draft agenda as the API answers it (only what the journeys read). */
export interface Draft {
  version: number
  sections: { id: string; title: string; items: { id: string; title: string }[] }[]
}

/** The id of the committee with [code] (its officers and members see it). */
export async function committeeId(api: MeetingApi, code: string): Promise<string> {
  const forums = await api.get<{ items: { id: string; code: string }[] } | { id: string; code: string }[]>('/forums')
  const list = Array.isArray(forums) ? forums : forums.items
  const forum = list.find((f) => f.code === code)
  if (!forum) throw new Error(`no committee ${code}`)
  return forum.id
}

/** The signed-in person's user id. */
export async function myId(page: Page): Promise<string> {
  const session = (await (await page.request.get('/api/v1/auth/session')).json()) as { user?: { id: string } }
  if (!session.user) throw new Error('not signed in')
  return session.user.id
}

/**
 * axe over the whole page, from the top of the document. The shell's top bar is sticky, so after a step that moved focus
 * down the page (the builder focuses the row it changed) a control can sit partly under the bar, and axe's target-size
 * check then measures only its uncovered part (a 44 px section menu failed in CI with 12.5 px showing). Checking from the
 * top keeps the check about the controls' size and spacing rather than the scroll position; the focused control itself
 * is kept clear of the bar by the page's scroll padding.
 */
export async function checkPageA11y(page: Page): Promise<void> {
  await page.evaluate(() => {
    window.scrollTo(0, 0)
  })
  await assertNoA11yViolations(page)
}
