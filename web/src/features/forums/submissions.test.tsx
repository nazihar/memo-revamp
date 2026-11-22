import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type {
  CoverSheetRequest,
  ForumPolicyResponse,
  ForumResponse,
  MemberResponse,
  SubmissionResponse,
} from '@/api/generated/types.gen'
import { partsForPath } from '@/i18n/catalogue-parts'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const API = `${API_ORIGIN}/api/v1`

const mcc: ForumResponse = {
  id: 'f1',
  key: 'mcc',
  code: 'MCC',
  codeLocked: false,
  name: 'Management Credit Committee',
  shortName: 'Credit',
  typeId: 't1',
  typeName: 'Management committee',
  status: 'ACTIVE',
  chair: { id: 'u1', name: 'S. Ali' },
  secretaries: [{ id: 'u9', name: 'Demo Company Secretary' }],
  memberCount: 5,
  termWarnings: 0,
  myRole: 'SECRETARY',
  canManageMembers: true,
  canManage: false,
  version: 2,
}

const alco: ForumResponse = { ...mcc, id: 'f2', key: 'alco', code: 'ALCO', name: 'Asset Liability Committee' }

const policy: ForumPolicyResponse = {
  typeId: 't1',
  typeName: 'Management committee',
  version: 1,
  memberAttributes: [],
  quorum: { counts: 'VOTING', basis: 'IN_OFFICE', threshold: { kind: 'MAJORITY' }, minimum: 2 },
  outcomes: [{ kind: 'APPROVED', label: 'Approved', resolution: true }],
  termPolicy: { termMonths: 24, reminderDays: [] },
  submissionPolicy: { deadlineWorkingDays: 5, lateMode: 'CHAIR_APPROVAL', triageWorkingDays: 5 },
  quorumBasis: 5,
  quorumNeeded: 3,
  quorumOverride: 'NEVER',
  memoResumes: 'AT_MEETING_END',
}

const DAY = 86_400_000

const paper = (s: Partial<SubmissionResponse> & Pick<SubmissionResponse, 'id'>): SubmissionResponse => ({
  forumId: 'f1',
  forumName: 'Management Credit Committee',
  subjectType: 'memo',
  subjectId: `m-${s.id}`,
  memo: { id: `m-${s.id}`, reference: 'CR/2026-27/0042', subject: 'Term loan, Delta Textiles' },
  waits: true,
  purpose: 'DECISION',
  status: 'QUEUED',
  submittedBy: { id: 'u5', name: 'R. Khan' },
  submittedAt: new Date(Date.now() - 2 * DAY).toISOString(),
  version: 3,
  ...s,
})

const queued = paper({ id: 's1' })
const restricted = paper({
  id: 's2',
  memo: null,
  purpose: 'INFORMATION',
  waits: false,
  submittedBy: { id: 'u6', name: 'N. Islam' },
  submittedAt: new Date(Date.now() - 9 * DAY).toISOString(),
  remindedAt: new Date(Date.now() - DAY).toISOString(),
})
const accepted = paper({
  id: 's3',
  status: 'ACCEPTED',
  memo: { id: 'm-s3', reference: 'CR/2026-27/0050', subject: 'Overdraft, Rupali Foods' },
  targetDate: '2026-12-12',
  triagedAt: new Date(Date.now() - DAY).toISOString(),
  triagedBy: { id: 'u9', name: 'Demo Company Secretary' },
})

interface Seen {
  statusQueries: string[][]
}

function handlers(
  forum: ForumResponse = mcc,
  papers: SubmissionResponse[] = [queued, restricted, accepted],
  seen?: Seen,
) {
  return [
    sessionHandler(signedInSession()),
    accessHandler([]),
    csrfHandler(),
    http.get(`${API}/forums`, () => HttpResponse.json<ForumResponse[]>([forum, alco])),
    http.get(`${API}/forums/f1`, () => HttpResponse.json<ForumResponse>(forum)),
    http.get(`${API}/forums/f1/members`, () => HttpResponse.json<MemberResponse[]>([])),
    http.get(`${API}/forums/f1/policy`, () => HttpResponse.json<ForumPolicyResponse>(policy)),
    http.get(`${API}/authz/classification-levels`, () => HttpResponse.json([{ id: 'l0', name: 'Internal', rank: 0 }])),
    http.get(`${API}/forums/f1/submissions`, ({ request }) => {
      const status = new URL(request.url).searchParams.getAll('status')
      seen?.statusQueries.push(status)
      return HttpResponse.json<SubmissionResponse[]>(
        status.length ? [paper({ id: 's9', status: 'RETURNED', reason: 'Add the collateral valuation' })] : papers,
      )
    }),
  ]
}

async function openQueue(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('tab', { name: /^Submissions/ }, { timeout: 5000 }))
  return screen.findByRole('table', { name: 'Papers sent to Management Credit Committee' })
}

/** Opens the Triage menu of the paper [name] and picks [action]. */
async function triage(user: ReturnType<typeof userEvent.setup>, name: string, action: string) {
  await user.click(await screen.findByRole('button', { name: `Triage ${name}` }, { timeout: 5000 }))
  await user.click(await screen.findByRole('menuitem', { name: action }, { timeout: 5000 }))
  return screen.findByRole('dialog')
}

describe('a committee’s papers (Phase 4 E02 T4, flow M1)', () => {
  it('lists the waiting papers with purpose, memo or “Restricted”, sender, age and flags', async () => {
    const user = userEvent.setup()
    server.use(...handlers())
    const { container } = renderRoute('/committees/f1')

    expect(await screen.findByRole('tab', { name: 'Submissions (3)' }, { timeout: 5000 })).toBeInTheDocument()
    const table = await openQueue(user)
    expect(screen.getByRole('button', { name: 'Waiting (2)', pressed: true })).toBeInTheDocument()
    const first = within((await within(table).findByText('R. Khan')).closest('tr') as HTMLElement)
    expect(first.getByText('For decision')).toBeInTheDocument()
    expect(first.getByRole('link', { name: /CR\/2026-27\/0042\s*Term loan, Delta Textiles/ })).toHaveAttribute(
      'href',
      '/memos/m-s1',
    )
    expect(first.getByText('2 days')).toBeInTheDocument()
    expect(first.getByText('Waiting for triage')).toBeInTheDocument()

    const hidden = within(within(table).getByText('N. Islam').closest('tr') as HTMLElement)
    expect(hidden.getByText('Restricted')).toBeInTheDocument()
    expect(hidden.getByText('You are not cleared to read this memo.')).toBeInTheDocument()
    expect(hidden.queryByRole('link', { name: /Delta/ })).toBeNull()
    expect(hidden.getByText('For noting')).toBeInTheDocument()
    expect(hidden.getByText('Waiting too long')).toBeInTheDocument()
    expect(hidden.getByText('For information only')).toBeInTheDocument()
    expect(within(table).queryByText('Overdraft, Rupali Foods')).toBeNull()
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('button', { name: 'Accepted (1)' }))
    expect(await within(table).findByText('Overdraft, Rupali Foods')).toBeInTheDocument()
    expect(partsForPath('/committees/f1')).toContain('papers')
    expect(partsForPath('/memos/m1')).toContain('papers')
  }, 20_000)

  it('asks the server for the closed papers only when Closed is chosen', async () => {
    const user = userEvent.setup()
    const seen: Seen = { statusQueries: [] }
    server.use(...handlers(mcc, [queued], seen))
    renderRoute('/committees/f1')
    const table = await openQueue(user)
    await within(table).findByText('R. Khan')
    expect(seen.statusQueries.length).toBeGreaterThan(0)
    expect(seen.statusQueries.every((q) => q.length === 0)).toBe(true)

    await user.click(screen.getByRole('button', { name: 'Closed' }))
    expect(await within(table).findByText('Returned')).toBeInTheDocument()
    expect(seen.statusQueries.at(-1)).toEqual(['DECIDED', 'RETURNED', 'DECLINED', 'WITHDRAWN', 'REFERRED'])
    expect(within(table).getByRole('link', { name: 'Open the paper' })).toHaveAttribute(
      'href',
      '/committees/f1/submissions/s9',
    )
  }, 20_000)

  it('accepts a paper for a date with If-Match, explaining the deadline and what happens to the memo', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    const matches: (string | null)[] = []
    server.use(
      ...handlers(),
      http.post(`${API}/forums/f1/submissions/s1/accept`, async ({ request }) => {
        matches.push(request.headers.get('If-Match'))
        bodies.push(await request.json())
        return HttpResponse.json<SubmissionResponse>({ ...queued, status: 'ACCEPTED', version: 4 })
      }),
    )
    renderRoute('/committees/f1')
    await openQueue(user)

    const dialog = await triage(user, 'CR/2026-27/0042 Term loan, Delta Textiles', 'Accept…')
    expect(within(dialog).getByRole('heading', { name: 'Accept the paper' })).toBeInTheDocument()
    expect(
      within(dialog).getByText(/The memo keeps waiting for the committee’s decision, and its author is told\./),
    ).toBeInTheDocument()
    const date = within(dialog).getByLabelText(/Target meeting date/)
    expect(date).toHaveAccessibleDescription(
      'Papers are due 5 working days before it. Late: the chair’s approval will be needed when it goes on the agenda.',
    )
    await user.type(date, '2027-01-14')
    await user.click(within(dialog).getByRole('button', { name: 'Accept' }))

    expect(await screen.findByText('Paper accepted.')).toBeInTheDocument()
    expect(matches).toEqual(['"3"'])
    expect(bodies).toEqual([{ targetDate: '2027-01-14' }])
    expect(screen.queryByRole('dialog')).toBeNull()
  }, 20_000)

  it('needs a reason to return a memo, then returns it with If-Match', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    const matches: (string | null)[] = []
    server.use(
      ...handlers(),
      http.post(`${API}/forums/f1/submissions/s1/return`, async ({ request }) => {
        matches.push(request.headers.get('If-Match'))
        bodies.push(await request.json())
        return HttpResponse.json<SubmissionResponse>({ ...queued, status: 'RETURNED', version: 4 })
      }),
    )
    renderRoute('/committees/f1')
    await openQueue(user)

    const dialog = await triage(user, 'CR/2026-27/0042 Term loan, Delta Textiles', 'Return for more information…')
    expect(within(dialog).getByText(/The memo goes back to its author to complete\./)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Return to the author' }))
    const reason = within(dialog).getByRole('textbox', { name: /Reason/ })
    expect(reason).toHaveAccessibleDescription(/Give a reason\./)
    expect(bodies).toEqual([])

    await user.type(reason, 'Add the collateral valuation')
    await user.click(within(dialog).getByRole('button', { name: 'Return to the author' }))
    expect(await screen.findByText('Memo returned to its author.')).toBeInTheDocument()
    expect(bodies).toEqual([{ reason: 'Add the collateral valuation' }])
    expect(matches).toEqual(['"3"'])
  }, 20_000)

  it('shows the server’s REASON_REQUIRED at the field when declining', async () => {
    const user = userEvent.setup()
    server.use(
      ...handlers(),
      http.post(`${API}/forums/f1/submissions/s1/decline`, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Invalid',
            status: 422,
            errors: [{ field: 'reason', code: 'REASON_REQUIRED', message: 'Say why.' }],
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    renderRoute('/committees/f1')
    await openQueue(user)
    const dialog = await triage(user, 'CR/2026-27/0042 Term loan, Delta Textiles', 'Decline…')
    expect(within(dialog).getByText(/The committee will not take this paper\./)).toBeInTheDocument()
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), '   x')
    await user.click(within(dialog).getByRole('button', { name: 'Decline' }))
    expect(await within(dialog).findByText('Give a reason.')).toBeInTheDocument()
  }, 20_000)

  it('defers with a reason, and says when someone else got there first', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    server.use(
      ...handlers(),
      http.post(`${API}/forums/f1/submissions/s1/defer`, async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Conflict',
            status: 409,
            reason: 'WRONG_STATE',
            detail: 'This submission is accepted.',
          },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        )
      }),
    )
    renderRoute('/committees/f1')
    await openQueue(user)
    const dialog = await triage(user, 'CR/2026-27/0042 Term loan, Delta Textiles', 'Defer…')
    await user.click(within(dialog).getByRole('button', { name: 'Defer' }))
    expect(within(dialog).getByRole('textbox', { name: /Reason/ })).toHaveAccessibleDescription(/Give a reason\./)
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Await the valuation')
    await user.click(within(dialog).getByRole('button', { name: 'Defer' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Someone has already dealt with this paper. The list now shows where it stands.',
    )
    expect(bodies).toEqual([{ reason: 'Await the valuation', targetDate: null }])
  }, 20_000)

  it('refers a paper to another committee with a reason', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    server.use(
      ...handlers(),
      http.post(`${API}/forums/f1/submissions/s1/refer`, async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json<SubmissionResponse>({ ...queued, status: 'REFERRED', version: 4 })
      }),
    )
    renderRoute('/committees/f1')
    await openQueue(user)
    const dialog = await triage(user, 'CR/2026-27/0042 Term loan, Delta Textiles', 'Refer to another committee…')
    const to = within(dialog).getByRole('combobox', { name: /Committee/ })
    expect(within(to).queryByRole('option', { name: /Management Credit Committee/ })).toBeNull()
    await user.selectOptions(to, await within(to).findByRole('option', { name: 'Asset Liability Committee (ALCO)' }))
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'A liquidity question')
    await user.click(within(dialog).getByRole('button', { name: 'Refer' }))
    expect(await screen.findByText('Paper referred.')).toBeInTheDocument()
    expect(bodies).toEqual([{ forumId: 'f2', reason: 'A liquidity question' }])
  }, 20_000)

  it('edits the cover sheet and keeps the presenter', async () => {
    const user = userEvent.setup()
    const bodies: CoverSheetRequest[] = []
    const matches: (string | null)[] = []
    const presented = { ...queued, presenter: { id: 'u5', name: 'R. Khan' } }
    server.use(
      ...handlers(mcc, [presented]),
      http.patch(`${API}/forums/f1/submissions/s1`, async ({ request }) => {
        matches.push(request.headers.get('If-Match'))
        bodies.push((await request.json()) as CoverSheetRequest)
        return HttpResponse.json<SubmissionResponse>({ ...presented, version: 4 })
      }),
    )
    renderRoute('/committees/f1')
    await openQueue(user)
    const dialog = await triage(user, 'CR/2026-27/0042 Term loan, Delta Textiles', 'Edit cover sheet…')
    await user.selectOptions(within(dialog).getByRole('combobox', { name: /Purpose/ }), 'For discussion')
    await user.type(within(dialog).getByRole('textbox', { name: /Resolution sought/ }), 'Approve the term loan')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Cover sheet saved.')).toBeInTheDocument()
    expect(bodies).toEqual([
      { purpose: 'DISCUSSION', resolutionSought: 'Approve the term loan', presenterId: 'u5', targetDate: null },
    ])
    expect(matches).toEqual(['"3"'])
  }, 20_000)

  it('lets the chair read the papers without triage, and hides them from a plain member', async () => {
    const user = userEvent.setup()
    server.use(...handlers({ ...mcc, myRole: 'CHAIR', canManageMembers: false }))
    const { unmount } = renderRoute('/committees/f1')
    const table = await openQueue(user)
    await within(table).findByText('R. Khan')
    expect(screen.queryByRole('button', { name: /^Triage/ })).toBeNull()
    expect(within(table).getAllByRole('link', { name: 'Open the paper' })).toHaveLength(2)
    unmount()

    server.use(...handlers({ ...mcc, myRole: 'MEMBER', canManageMembers: false }))
    renderRoute('/committees/f1')
    expect(await screen.findByRole('tab', { name: 'Members' }, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: /Submissions/ })).toBeNull()
  }, 20_000)

  it('reads in Arabic, right to left, without accessibility violations', async () => {
    const user = userEvent.setup()
    server.use(...handlers())
    const { container } = renderRoute('/committees/f1?tab=submissions', { locale: 'ar' })
    expect(await screen.findByRole('tab', { name: 'الأوراق الواردة (3)', selected: true }, { timeout: 5000 }))
    expect(document.documentElement.dir).toBe('rtl')
    expect(await screen.findByRole('table', { name: 'الأوراق المرسلة إلى Management Credit Committee' }))
    expect(screen.getByText('مقيّدة')).toBeInTheDocument()
    expect(screen.getAllByText('لاتخاذ قرار').length).toBeGreaterThan(0)
    await expectNoA11yViolations(container)
    await user.click(screen.getByRole('button', { name: /^فرز CR\/2026-27\/0042/ }))
    expect(await screen.findByRole('menuitem', { name: 'قبول…' })).toBeInTheDocument()
  }, 20_000)
})

describe('one paper (where the notifications lead)', () => {
  it('shows the author the paper and its timeline, without triage, when the committee is hidden from them', async () => {
    server.use(
      sessionHandler(signedInSession()),
      accessHandler([]),
      http.get(`${API}/forums/f1`, () => HttpResponse.json({ status: 404, title: 'Not found' }, { status: 404 })),
      http.get(`${API}/forums/f1/submissions/s3`, () => HttpResponse.json<SubmissionResponse>(accepted)),
    )
    const { container } = renderRoute('/committees/f1/submissions/s3')
    expect(
      await screen.findByRole(
        'heading',
        { level: 1, name: 'Paper for Management Credit Committee' },
        { timeout: 5000 },
      ),
    ).toBeInTheDocument()
    const timeline = screen.getByRole('list', { name: 'What has happened to the paper' })
    expect(within(timeline).getByText(/Sent to the Management Credit Committee/)).toBeInTheDocument()
    expect(within(timeline).getByText(/Accepted for the meeting of Dec 12, 2026/)).toBeInTheDocument()
    expect(within(timeline).getByText('by Demo Company Secretary')).toBeInTheDocument()
    expect(within(timeline).getByText('Next: decided at a meeting')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the memo' })).toHaveAttribute('href', '/memos/m-s3')
    expect(screen.queryByRole('button', { name: /Defer/ })).toBeNull()
    expect(screen.queryByRole('link', { name: /All papers/ })).toBeNull()
    await expectNoA11yViolations(container)
  }, 20_000)

  it('gives the secretary the triage actions on the paper’s page', async () => {
    const user = userEvent.setup()
    server.use(
      ...handlers(),
      http.get(`${API}/forums/f1/submissions/s1`, () => HttpResponse.json(queued)),
    )
    const { router } = renderRoute('/committees/f1/submissions/s1')
    const actions = await screen.findByRole('toolbar', { name: 'Actions' }, { timeout: 5000 })
    expect(
      within(actions)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual([
      'Accept…',
      'Defer…',
      'Return for more information…',
      'Decline…',
      'Refer to another committee…',
      'Edit cover sheet…',
    ])
    await user.click(screen.getByRole('link', { name: 'All papers of the committee' }))
    await vi.waitFor(() => {
      expect(router.state.location.href).toBe('/committees/f1?tab=submissions')
    })
    expect(await screen.findByRole('tab', { name: 'Submissions (3)', selected: true })).toBeInTheDocument()
  }, 20_000)
})
