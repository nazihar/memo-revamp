import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type {
  ForumPolicyResponse,
  ForumResponse,
  ResolutionCheckResponse,
  ResolutionEntryResponse,
  ResolutionExtractResponse,
  ResolutionRegisterResponse,
  ResolutionResponse,
} from '@/api/generated/types.gen'
import { partsForPath } from '@/i18n/catalogue-parts'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const API = `${API_ORIGIN}/api/v1`

const brc: ForumResponse = {
  id: 'f1',
  key: 'board-risk',
  code: 'BRC',
  codeLocked: true,
  name: 'Board Risk Committee',
  shortName: 'Risk',
  typeId: 't1',
  typeName: 'Board committee',
  status: 'ACTIVE',
  secretaries: [{ id: 'u9', name: 'K. Secretary' }],
  memberCount: 7,
  termWarnings: 0,
  canManageMembers: false,
  canManage: false,
  myRole: 'SECRETARY',
  version: 3,
}

const policy: ForumPolicyResponse = {
  typeId: 't1',
  typeName: 'Board committee',
  version: 2,
  memberAttributes: [],
  quorum: { counts: 'VOTING', basis: 'IN_OFFICE' },
  outcomes: [],
  termPolicy: {},
  submissionPolicy: {},
  quorumBasis: 7,
  quorumNeeded: 4,
  quorumOverride: 'NEVER',
  memoResumes: 'AT_MEETING_END',
}

const entry = (sequence: number, extra: Partial<ResolutionEntryResponse> = {}): ResolutionEntryResponse => ({
  id: `r${String(sequence)}`,
  number: `BRC/2026/${String(sequence).padStart(3, '0')}`,
  sequence,
  numberedAt: '2026-12-12T08:00:00Z',
  state: 'IN_FORCE',
  restricted: false,
  meetingId: 'm1',
  meetingLabel: 'BRC 3/2026',
  itemNumber: '5',
  itemTitle: 'Term loan, Delta Textiles',
  outcome: 'APPROVED_WITH_CONDITIONS',
  outcomeLabel: 'Approved with conditions',
  memo: { id: 'memo1', reference: 'CR/HO-CR/2026-27/0042' },
  ...extra,
})

const register: ResolutionRegisterResponse = {
  forumId: 'f1',
  period: '2026',
  periods: ['2026', '2025'],
  entries: [
    entry(16, {
      restricted: true,
      itemNumber: '6',
      itemTitle: null,
      outcome: undefined,
      outcomeLabel: null,
      memo: null,
    }),
    entry(15, { itemNumber: '8', itemTitle: 'Committee terms of reference', outcomeLabel: 'Approved', memo: null }),
    entry(14),
    entry(13, { state: 'VOID', voidReason: 'Corrected to Noted', memo: null }),
  ],
}

const resolution = (extra: Partial<ResolutionResponse> = {}): ResolutionResponse => ({
  id: 'r14',
  decisionId: 'd5',
  forumId: 'f1',
  forumName: 'Board Risk Committee',
  full: true,
  number: 'BRC/2026/014',
  numberedAt: '2026-12-12T08:00:00Z',
  state: 'IN_FORCE',
  meetingId: 'm1',
  meetingLabel: 'BRC 3/2026',
  meetingDate: '2026-12-12',
  itemNumber: '5',
  itemTitle: 'Term loan, Delta Textiles',
  outcome: 'APPROVED_WITH_CONDITIONS',
  outcomeLabel: 'Approved with conditions',
  text: 'It was RESOLVED that the term loan of BDT 60 million to Delta Textiles be approved.',
  conditions: ['Security perfected before disbursement'],
  validUntil: '2027-03-10',
  memo: { id: 'memo1', reference: 'CR/HO-CR/2026-27/0042' },
  releasedAt: '2026-12-12T08:01:00Z',
  minutesApprovedAt: null,
  mayIssueExtract: true,
  viewerRecused: false,
  ...extra,
})

const extract = (extra: Partial<ResolutionExtractResponse> = {}): ResolutionExtractResponse => ({
  id: 'x1',
  resolutionId: 'r14',
  issuedAt: '2026-12-14T06:00:00Z',
  issuedBy: { id: 'u9', name: 'K. Secretary' },
  minutesApproved: false,
  certificationHash: 'c'.repeat(64),
  state: 'PENDING',
  ...extra,
})

function forumHandlers(forum: ForumResponse = brc, permissions: string[] = []) {
  const checks: string[] = []
  server.use(
    sessionHandler(signedInSession()),
    accessHandler(permissions),
    csrfHandler(),
    http.get(`${API}/forums/f1`, () => HttpResponse.json<ForumResponse>(forum)),
    http.get(`${API}/forums/f1/members`, () => HttpResponse.json([])),
    http.get(`${API}/forums/f1/policy`, () => HttpResponse.json<ForumPolicyResponse>(policy)),
    http.get(`${API}/forums/f1/submissions`, () => HttpResponse.json({ items: [], nextCursor: null })),
    http.get(`${API}/authz/classification-levels`, () => HttpResponse.json([])),
    http.get(`${API}/forums/f1/resolutions`, () => HttpResponse.json<ResolutionRegisterResponse>(register)),
    http.get(`${API}/forums/f1/resolutions/check`, ({ request }) => {
      checks.push(new URL(request.url).searchParams.get('period') ?? '')
      return HttpResponse.json<ResolutionCheckResponse>({
        forumId: 'f1',
        period: '2026',
        complete: true,
        firstNumber: 1,
        lastNumber: 16,
        inForce: 15,
        void: 1,
        missing: [],
        missingCount: 0,
        duplicates: [],
        outOfRange: [],
      })
    }),
  )
  return { checks }
}

describe('The resolution register (Phase 4 E06 T10, flow AC1)', () => {
  it('lists the year’s resolutions, latest first, with restricted and void rows in words, and checks the register', async () => {
    const user = userEvent.setup()
    const { checks } = forumHandlers()
    const { container } = renderRoute('/committees/f1?tab=resolutions')
    expect(partsForPath('/committees/f1')).toContain('resolutions')
    expect(
      await screen.findByRole('heading', { name: 'Board Risk Committee — Resolutions' }, { timeout: 5000 }),
    ).toBeInTheDocument()
    const table = screen.getByRole('table', { name: 'Board Risk Committee — Resolutions' })
    const [, ...rows] = within(table).getAllByRole('row')
    expect(rows.map((r) => within(r).getByRole('rowheader').textContent)).toEqual([
      'BRC/2026/016',
      'BRC/2026/015',
      'BRC/2026/014',
      'BRC/2026/013',
    ])
    expect(rows[0]).toHaveTextContent('Restricted')
    expect(rows[0]).not.toHaveTextContent('Term loan')
    expect(rows[2]).toHaveTextContent('Approved with conditions')
    expect(within(rows[2]!).getByRole('link', { name: 'CR/HO-CR/2026-27/0042' })).toHaveAttribute(
      'href',
      '/memos/memo1',
    )
    expect(rows[3]).toHaveTextContent('Void: Corrected to Noted')
    expect(within(rows[2]!).getByRole('link', { name: 'BRC/2026/014' })).toHaveAttribute('href', '/resolutions/r14')
    // Only an administrator sets the starting number.
    expect(screen.queryByRole('button', { name: 'Set the starting resolution number…' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Check the register' }))
    expect(await screen.findByText('1 to 16 are all accounted for (15 in force, 1 void).')).toBeInTheDocument()
    expect(checks).toContain('2026')
    await expectNoA11yViolations(container)
  }, 30_000)

  it('a member has no Resolutions tab', async () => {
    forumHandlers({ ...brc, myRole: 'MEMBER' })
    renderRoute('/committees/f1?tab=resolutions')
    expect(await screen.findByRole('tab', { name: 'Members' }, { timeout: 5000 })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(screen.queryByRole('tab', { name: 'Resolutions' })).not.toBeInTheDocument()
  }, 30_000)

  it('an administrator sets the starting number with a step-up; ALREADY_STARTED is said in words', async () => {
    const user = userEvent.setup()
    forumHandlers({ ...brc, canManage: true, myRole: undefined }, ['forums.manage'])
    let steppedUp = false
    const sent: { period: string; body: unknown }[] = []
    server.use(
      http.put(`${API}/forums/f1/resolution-counters/:period`, async ({ request, params }) => {
        if (!steppedUp) {
          return HttpResponse.json(
            { type: 'urn:memo:problem:step-up-required', status: 401, stepUpWindowSeconds: 300 },
            { status: 401 },
          )
        }
        sent.push({ period: String(params.period), body: await request.json() })
        return HttpResponse.json(
          { type: 'urn:memo:problem:conflict', status: 409, reason: 'ALREADY_STARTED' },
          { status: 409 },
        )
      }),
      http.post(`${API}/auth/step-up`, () => {
        steppedUp = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderRoute('/committees/f1?tab=resolutions')
    await user.click(
      await screen.findByRole('button', { name: 'Set the starting resolution number…' }, { timeout: 5000 }),
    )
    const dialog = await screen.findByRole('dialog', { name: 'Set the starting resolution number' })
    expect(within(dialog).getByLabelText(/^Numbering period/)).toHaveValue('2026')
    await user.type(within(dialog).getByLabelText(/^The next number to give/), '120')
    await user.click(within(dialog).getByRole('button', { name: 'Confirm with your authenticator' }))
    const stepUp = await screen.findByRole('dialog', { name: 'Confirm it is you' })
    await user.type(within(stepUp).getByLabelText('Code'), '123456')
    await user.click(within(stepUp).getByRole('button', { name: 'Confirm' }))
    expect(
      await screen.findByText('Numbers have already been given in this period, so its starting number cannot be set.'),
    ).toBeInTheDocument()
    expect(sent).toEqual([{ period: '2026', body: { next: 120 } }])
  }, 30_000)
})

function resolutionHandlers(r: ResolutionResponse, extracts: () => ResolutionExtractResponse[] | Response) {
  server.use(
    sessionHandler(signedInSession()),
    accessHandler([]),
    csrfHandler(),
    http.get(`${API}/resolutions/r14`, () => HttpResponse.json<ResolutionResponse>(r)),
    http.get(`${API}/resolutions/r14/extracts`, () => {
      const answer = extracts()
      return answer instanceof Response ? answer : HttpResponse.json(answer)
    }),
  )
}

describe('One resolution and its extracts (flow AC2, AC3)', () => {
  it('shows the resolution in words and issues a certified extract with a step-up, then opens it once made', async () => {
    const user = userEvent.setup()
    let issued = false
    let polls = 0
    let steppedUp = false
    resolutionHandlers(resolution(), () => {
      if (!issued) return []
      polls += 1
      return [polls > 1 ? extract({ state: 'READY', sha256: 'a'.repeat(64), pages: 1, compliant: true }) : extract()]
    })
    server.use(
      http.post(`${API}/resolutions/r14/extracts`, () => {
        if (!steppedUp) {
          return HttpResponse.json(
            { type: 'urn:memo:problem:step-up-required', status: 401, stepUpWindowSeconds: 300 },
            { status: 401 },
          )
        }
        issued = true
        return HttpResponse.json<ResolutionExtractResponse>(extract(), { status: 202 })
      }),
      http.post(`${API}/auth/step-up`, () => {
        steppedUp = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const { container } = renderRoute('/resolutions/r14')
    expect(partsForPath('/resolutions/r14')).toContain('resolutions')
    expect(
      await screen.findByRole('heading', { name: 'Resolution BRC/2026/014' }, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.getByText('In force')).toBeInTheDocument()
    expect(
      screen.getByText('Board Risk Committee · BRC 3/2026 · December 12, 2026 · item 5, Term loan, Delta Textiles'),
    ).toBeInTheDocument()
    expect(screen.getByText('APPROVED WITH CONDITIONS')).toBeInTheDocument()
    expect(screen.getByText('Security perfected before disbursement')).toBeInTheDocument()
    expect(screen.getByText('Valid until March 10, 2027')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'CR/HO-CR/2026-27/0042 (moved on December 12, 2026)' })).toBeInTheDocument()
    expect(screen.getByText('not yet approved')).toBeInTheDocument()
    expect(await screen.findByText('Extracts issued: none.')).toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('button', { name: 'Issue a certified extract' }))
    const dialog = await screen.findByRole('dialog', { name: 'Issue a certified extract' })
    expect(
      within(dialog).getByText('You will certify this extract as a secretary of the Board Risk Committee.'),
    ).toBeInTheDocument()
    expect(within(dialog).getByText('It will say that the minutes have not yet been approved.')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Confirm with your authenticator' }))
    const stepUp = await screen.findByRole('dialog', { name: 'Confirm it is you' })
    await user.type(within(stepUp).getByLabelText('Code'), '123456')
    await user.click(within(stepUp).getByRole('button', { name: 'Confirm' }))

    const open = await screen.findByRole('link', { name: 'Open the extract (PDF)' }, { timeout: 8000 })
    expect(open).toHaveAttribute('href', '/api/v1/resolutions/r14/extracts/x1/content')
    await waitFor(() => {
      expect(open).toHaveFocus()
    })
    expect(screen.getByText(`Fingerprint (SHA-256): ${'a'.repeat(64)}`)).toBeInTheDocument()
    expect(screen.getByText('says the minutes are not yet approved')).toBeInTheDocument()
  }, 30_000)

  it('refusals in words: NOT_RELEASED, and a recused reader of the memo sees no extracts', async () => {
    const user = userEvent.setup()
    resolutionHandlers(resolution(), () => [])
    server.use(
      http.post(`${API}/resolutions/r14/extracts`, () =>
        HttpResponse.json({ type: 'urn:memo:problem:conflict', status: 409, reason: 'NOT_RELEASED' }, { status: 409 }),
      ),
    )
    renderRoute('/resolutions/r14')
    await user.click(await screen.findByRole('button', { name: 'Issue a certified extract' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Issue a certified extract' })
    await user.click(within(dialog).getByRole('button', { name: 'Confirm with your authenticator' }))
    expect(
      await within(dialog).findByText('An extract can be issued once the memo has moved on with this decision.'),
    ).toBeInTheDocument()
  }, 30_000)

  it('a recused reader reads the recused line and no extracts (403)', async () => {
    resolutionHandlers(resolution({ full: false, viewerRecused: true, mayIssueExtract: false, memo: null }), () =>
      HttpResponse.json({ type: 'about:blank', status: 403 }, { status: 403 }),
    )
    renderRoute('/resolutions/r14', { locale: 'ar' })
    expect(
      await screen.findByText('أعلنتَ عن مصلحة في هذا البند ولم تشارك فيه.', {}, { timeout: 8000 }),
    ).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: 'المستخرجات المصدّقة' })).not.toBeInTheDocument()
    })
    expect(screen.queryByRole('link', { name: 'العودة إلى الاجتماع' })).not.toBeInTheDocument()
  }, 30_000)
})
