import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { createIntl } from 'react-intl'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  ActionAbilities,
  ActionEntriesResponse,
  ActionReportResponse,
  ActionResponse,
  ActionSummary,
  DecisionResponse,
  ForumActionsResponse,
  ForumPolicyResponse,
  ForumResponse,
  ForumTypeResponse,
  ForumTypeVersionResponse,
  MeetingActionsResponse,
  MeetingResponse,
  MemberAgendaResponse,
  MemberItemResponse,
} from '@/api/generated/types.gen'
import { partsForPath } from '@/i18n/catalogue-parts'
import {
  accessHandler,
  API_ORIGIN,
  csrfHandler,
  server,
  sessionHandler,
  signedInSession,
  suggestedDueHandler,
} from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'
import { officerFieldText, officerRefusal, reasonWords } from './problems'

const API = `${API_ORIGIN}/api/v1`

vi.mock('@/features/meetings/time', async (original) => ({
  ...(await original<typeof import('@/features/meetings/time')>()),
  viewerZone: () => 'Asia/Dhaka',
}))

const NOTHING: ActionAbilities = {
  acceptRisk: false,
  cancel: false,
  changeDue: false,
  changeOwner: false,
  confidence: false,
  decideDue: false,
  discard: false,
  edit: false,
  issue: false,
  linkMemo: false,
  markDone: false,
  overrideRag: false,
  raiseMemo: false,
  reopen: false,
  requestDue: false,
  start: false,
  update: false,
  updateOnBehalf: false,
  verify: false,
  holdBack: false,
}
const can = (over: Partial<ActionAbilities> = {}): ActionAbilities => ({ ...NOTHING, ...over })
/** A secretary not recused, on an open action. */
const SECRETARY = can({
  edit: true,
  changeDue: true,
  changeOwner: true,
  overrideRag: true,
  cancel: true,
  updateOnBehalf: true,
  linkMemo: true,
  holdBack: true,
})

/** BRC 3/2026, held on 12 December 2026 in Dhaka; the reader is its secretary. */
const meeting: MeetingResponse = {
  id: 'm1',
  forumId: 'f1',
  forumName: 'Board Risk Committee',
  forumCode: 'BRC',
  typeVersion: 2,
  kind: 'ORDINARY',
  title: null,
  startsAt: '2026-12-12T04:00:00Z',
  endsAt: '2026-12-12T07:00:00Z',
  timeZone: 'Asia/Dhaka',
  localStartsAt: '2026-12-12T10:00:00',
  localEndsAt: '2026-12-12T13:00:00',
  mode: 'IN_PERSON',
  location: 'Board Room',
  classificationId: 'l1',
  number: 3,
  label: 'BRC 3/2026',
  status: 'HELD',
  relation: 'OFFICER',
  canManage: true,
  guests: [],
  version: 5,
}

const item = (
  id: string,
  number: string,
  title: string,
  extra: Partial<MemberItemResponse> = {},
): MemberItemResponse => ({
  id,
  number,
  title,
  kind: 'SUBMISSION',
  purpose: 'DECISION',
  access: 'FULL',
  late: 'NONE',
  changes: [],
  papers: [],
  presenters: [],
  subItems: [],
  links: [],
  ...extra,
})

const agenda = (
  items: MemberItemResponse[],
  status: MemberAgendaResponse['status'] = 'HELD',
): MemberAgendaResponse => ({
  meetingId: 'm1',
  status,
  startsAt: meeting.startsAt,
  endsAt: meeting.endsAt,
  timeZone: 'Asia/Dhaka',
  revision: 1,
  latestRevision: 1,
  preview: false,
  sections: [{ id: 's1', kind: 'STANDARD', membersOnly: false, title: 'For decision', items }],
})

const decision: DecisionResponse = {
  id: 'd5',
  itemId: 'i5',
  itemNumber: '5',
  outcome: 'APPROVED_WITH_CONDITIONS',
  outcomeLabel: 'Approved with conditions',
  resolution: true,
  text: 'It was RESOLVED that the credit facility be approved.',
  conditions: ['Security over the new spinning unit before drawdown.', 'Quarterly covenant report to the committee.'],
  decidedAt: '2026-12-12T05:00:00Z',
  seq: 1,
  state: 'RELEASED',
  toBeRatified: false,
  ratificationRequired: false,
  viewerRecused: false,
  version: 3,
  vote: { mode: 'CONSENSUS' },
  dissent: [],
  abstentions: [],
  tookNoPart: [],
  recordedBy: { id: 'sec', name: 'K. Secretary' },
  recordedAt: '2026-12-12T05:10:00Z',
}

const policy: ForumPolicyResponse = {
  typeId: 't1',
  typeName: 'Board committee',
  version: 2,
  memberAttributes: [],
  outcomes: [{ kind: 'APPROVED_WITH_CONDITIONS', label: 'Approved with conditions', resolution: true }],
  quorum: { counts: 'VOTING', basis: 'IN_OFFICE', recusedCount: false, remoteCounts: true, alternatesCount: true },
  quorumBasis: 7,
  quorumNeeded: 4,
  quorumOverride: 'NEVER',
  memoResumes: 'AT_MEETING_END',
  submissionPolicy: {},
  termPolicy: {},
}

const summary = (over: Partial<ActionSummary> = {}): ActionSummary => ({
  id: 'a1',
  forumId: 'f1',
  committee: 'Board Risk Committee',
  reference: null,
  title: 'Report covenant compliance quarterly',
  status: 'DRAFT',
  priority: 'NORMAL',
  sourceKind: 'DECISION',
  sourceLabel: 'BRC 3/2026, item 5 — decision, condition 2',
  meetingId: 'm1',
  itemId: 'i5',
  decisionId: 'd5',
  conditionIndex: 1,
  owner: { kind: 'POSITION', position: { id: 'p1', title: 'Head of Credit Risk' } },
  dueDate: '2027-03-12',
  dueRevisions: 0,
  rag: { colour: 'NONE', word: 'DRAFT' },
  version: 1,
  ...over,
})

const action = (over: Partial<ActionResponse> = {}): ActionResponse => ({
  id: 'a9',
  version: 4,
  forumId: 'f1',
  committee: 'Board Risk Committee',
  reference: 'BRC A9/2026',
  status: 'IN_PROGRESS',
  priority: 'NORMAL',
  title: 'Revise the liquidity contingency plan',
  context: 'Revise the contingency funding plan to reflect the new stress scenarios.',
  classificationId: 'l1',
  source: {
    kind: 'DECISION',
    label: 'BRC 3/2026, item 7 — Resolution BRC/2026/011',
    meetingId: 'm1',
    itemId: 'i7',
    itemNumber: '7',
    itemTitle: 'Liquidity contingency plan',
  },
  owner: {
    kind: 'POSITION',
    position: { id: 'p2', title: 'Head of Treasury' },
    holder: { id: 'u1', name: 'A. Karim' },
  },
  contributors: [],
  dueDate: '2026-12-28',
  originalDueDate: '2026-12-14',
  dueRevisions: 1,
  stepName: 'Owner',
  createdAt: '2026-11-01T09:00:00Z',
  issuedAt: '2026-11-01T09:00:00Z',
  viewerRecused: false,
  warnings: [],
  can: SECRETARY,
  memos: [],
  rag: { colour: 'RED', word: 'OVERDUE' },
  dueChange: 'SECRETARY_AGREES',
  reopenCount: 0,
  evidenceRequired: false,
  ...over,
})

interface Sent {
  method: string
  path: string
  body: Record<string, unknown> | undefined
  key: string | null
  ifMatch: string | null
}

const problem = (status: number, extra: Record<string, unknown>) =>
  HttpResponse.json(
    { type: 'about:blank', status, ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )

/** Records every write: its path, body and headers. */
function recorder() {
  const sent: Sent[] = []
  const record = async (request: Request, path: string) => {
    const body = (await request.json().catch(() => undefined)) as Record<string, unknown> | undefined
    const s = {
      method: request.method,
      path,
      body,
      key: request.headers.get('Idempotency-Key'),
      ifMatch: request.headers.get('If-Match'),
    }
    sent.push(s)
    return s
  }
  return { sent, record }
}

const directory = http.get(`${API}/directory/search`, ({ request }) => {
  const q = new URL(request.url).searchParams.get('q') ?? ''
  const kind = new URL(request.url).searchParams.get('kind')
  if (kind === 'position') {
    return HttpResponse.json({
      users: [],
      units: [],
      positions: [
        { id: 'p1', title: 'Head of Credit Risk', unitName: 'Credit Risk', holder: { displayName: 'S. Chowdhury' } },
      ],
    })
  }
  const person = q.startsWith('Ra')
    ? { id: 'u3', displayName: 'R. Hasan', email: 'r.hasan@bank.example' }
    : { id: 'u4', displayName: 'A. Rahman', email: 'a.rahman@bank.example' }
  return HttpResponse.json({ users: [{ person }], positions: [], units: [] })
})

/** The meeting page and its decision panel, held: item 5 decided with two conditions, one draft from condition 2. */
function meetingPage({
  m = meeting,
  actions = [summary()],
  items = [item('i5', '5', 'Credit facility — Karnaphuli Textiles Ltd')],
  decisions = [decision],
}: {
  m?: MeetingResponse
  actions?: ActionSummary[]
  items?: MemberItemResponse[]
  decisions?: DecisionResponse[]
} = {}) {
  const { sent, record } = recorder()
  const state = { actions }
  server.use(
    sessionHandler(signedInSession()),
    accessHandler([]),
    csrfHandler(),
    directory,
    http.get(`${API}/authz/classification-levels`, () =>
      HttpResponse.json([
        { id: 'l1', name: 'Confidential', rank: 1 },
        { id: 'l2', name: 'Secret', rank: 2 },
      ]),
    ),
    http.get(`${API}/meetings/m1`, () => HttpResponse.json<MeetingResponse>(m)),
    http.get(`${API}/meetings/m1/agenda`, () => HttpResponse.json<MemberAgendaResponse>(agenda(items, 'HELD'))),
    http.get(`${API}/forums/f1/policy`, () => HttpResponse.json<ForumPolicyResponse>(policy)),
    http.get(`${API}/meetings/m1/attendance`, () =>
      HttpResponse.json({
        meetingId: 'm1',
        status: m.status,
        canRecord: false,
        correcting: false,
        counts: { inPerson: 5, remote: 0, apologies: 0, expected: 0, left: 0, absent: 0, total: 5 },
        people: [],
        events: [],
        quorum: { quorate: true, basis: 7, needed: 4, present: 5, unmet: [], seats: [] },
      }),
    ),
    http.get(`${API}/meetings/m1/quorum`, () =>
      HttpResponse.json({ quorate: true, basis: 7, needed: 4, present: 5, unmet: [], seats: [] }),
    ),
    http.get(`${API}/meetings/m1/quorum/changes`, () => HttpResponse.json([])),
    http.get(`${API}/meetings/m1/decisions`, () => HttpResponse.json({ meetingId: 'm1', decisions })),
    http.get(`${API}/forums/f1/decisions/to-be-ratified`, () => HttpResponse.json([])),
    http.get(`${API}/meetings/m1/minutes`, () => problem(404, {})),
    http.get(`${API}/meetings/m1/items/:itemId/notes`, ({ params }) =>
      HttpResponse.json({ itemId: String(params.itemId), seq: 0, text: '' }),
    ),
    http.get(`${API}/meetings/m1/declarations/mine`, () =>
      HttpResponse.json({ meetingId: 'm1', declarations: [], canDeclare: false }),
    ),
    http.get(`${API}/forums/f1/meetings`, () =>
      HttpResponse.json({
        items: [
          {
            id: 'm2',
            forumId: 'f1',
            forumName: 'Board Risk Committee',
            forumCode: 'BRC',
            kind: 'ORDINARY',
            label: 'BRC 4/2026',
            mode: 'IN_PERSON',
            relation: 'OFFICER',
            status: 'SCHEDULED',
            startsAt: '2099-03-12T04:00:00Z',
            endsAt: '2099-03-12T07:00:00Z',
            localStartsAt: '2099-03-12T10:00:00',
            localEndsAt: '2099-03-12T13:00:00',
            timeZone: 'Asia/Dhaka',
          },
        ],
        nextCursor: null,
      }),
    ),
    http.get(`${API}/meetings/m1/actions`, () =>
      HttpResponse.json<MeetingActionsResponse>({
        meetingId: 'm1',
        actions: state.actions,
        drafts: state.actions.filter((a) => a.status === 'DRAFT').length,
      }),
    ),
    http.get(`${API}/actions/:id`, ({ params }) =>
      HttpResponse.json<ActionResponse>(
        action({
          id: String(params.id),
          reference: null,
          status: 'DRAFT',
          title: 'Report covenant compliance quarterly',
          can: can({ edit: true, issue: true, discard: true }),
          owner: { kind: 'POSITION', position: { id: 'p1', title: 'Head of Credit Risk' } },
          dueDate: '2027-03-12',
          warnings: [{ code: 'OWNER_RECUSED', userId: 'u5' }],
          contributors: [{ id: 'u5', name: 'S. Chowdhury' }],
        }),
      ),
    ),
  )
  return { sent, record, state }
}

beforeEach(() => {
  server.events.removeAllListeners()
})

describe('Raising actions from decisions and the meeting (Phase 4 E07 T10, flow AJ)', () => {
  it('makes a condition an action: the form starts from it, the owner is a position, due by the next meeting, sent at once', async () => {
    const user = userEvent.setup()
    const { sent, record, state } = meetingPage()
    server.use(
      http.post(`${API}/meetings/m1/actions`, async ({ request }) => {
        const s = await record(request, '/meetings/m1/actions')
        const raised = action({
          id: 'a2',
          reference: null,
          status: 'DRAFT',
          version: 1,
          title: String(s.body?.title),
          can: can({ edit: true, issue: true, discard: true }),
        })
        return HttpResponse.json<ActionResponse>(raised, { status: 201 })
      }),
      http.post(`${API}/actions/a2/issue`, async ({ request }) => {
        await record(request, '/actions/a2/issue')
        state.actions = [...state.actions, summary({ id: 'a2', reference: 'BRC A1/2026', status: 'OPEN' })]
        return HttpResponse.json<ActionResponse>(action({ id: 'a2', reference: 'BRC A1/2026', status: 'OPEN' }))
      }),
    )
    const { container } = renderRoute('/meetings/m1?tab=decisions')
    const actions = await screen.findByRole('region', { name: /^Actions from this item/ }, { timeout: 8000 })
    // The draft already raised from condition 2, in words: draft, owner, date.
    expect(within(actions).getByText('(1 draft)')).toBeInTheDocument()
    expect(within(actions).getByText('Report covenant compliance quarterly')).toBeInTheDocument()
    expect(within(actions).getByText('Head of Credit Risk (no one holds it now) · by Mar 12, 2027')).toBeInTheDocument()
    expect(
      within(actions).getByRole('button', { name: 'Edit Report covenant compliance quarterly' }),
    ).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Make condition 1 an action' }))
    const form = await screen.findByRole('dialog', { name: 'New action — from Item 5, condition 1' })
    expect(within(form).getByRole('textbox', { name: 'What is to be done' })).toHaveValue(
      'Security over the new spinning unit before drawdown.',
    )
    expect(within(form).getByRole('textbox', { name: /What the owner is told/ })).toHaveValue(
      'Security over the new spinning unit before drawdown.',
    )
    expect(
      within(form).getByText('The owner will see this text. They will not see the item’s papers or the discussion.'),
    ).toBeInTheDocument()
    await user.click(within(form).getByRole('radio', { name: 'A position' }))
    await user.type(within(form).getByRole('combobox', { name: 'Who' }), 'Head')
    await user.click(await within(form).findByRole('option', { name: /Head of Credit Risk/ }))
    expect(within(form).getByText('Now: Credit Risk · S. Chowdhury')).toBeInTheDocument()
    // By the next meeting is the first choice, with its date.
    expect(within(form).getByRole('radio', { name: 'By the next meeting (March 12, 2099)' })).toBeChecked()
    // Every button is a 44 px target.
    for (const b of within(form).getAllByRole('button')) {
      if (b.getAttribute('aria-label') !== 'Close') expect(b.className).toMatch(/min-h-11|size-11/)
    }
    await expectNoA11yViolations(container)
    await user.click(within(form).getByRole('button', { name: 'Save and send to the owner' }))

    const said = await screen.findByText('BRC A1/2026 was sent to its owner.')
    expect(said).toHaveFocus()
    expect(screen.queryByRole('dialog', { name: /New action/ })).not.toBeInTheDocument()
    const raise = sent.find((s) => s.path === '/meetings/m1/actions')
    expect(raise?.key).toMatch(/^[0-9a-f-]{36}$/)
    expect(raise?.body).toMatchObject({
      source: 'DECISION',
      itemId: 'i5',
      decisionId: 'd5',
      conditionIndex: 0,
      title: 'Security over the new spinning unit before drawdown.',
      owner: { kind: 'POSITION', positionId: 'p1' },
      dueDate: '2099-03-12',
      priority: 'NORMAL',
      contributors: [],
    })
    const issue = sent.find((s) => s.path === '/actions/a2/issue')
    expect(issue?.ifMatch).toBe('"1"')
    expect(issue?.key).toMatch(/^[0-9a-f-]{36}$/)
    expect(issue?.key).not.toBe(raise?.key)
  }, 40_000)

  it('during the meeting offers only "Save as draft"; a refusal comes back in words and an edited draft keeps its warnings', async () => {
    const user = userEvent.setup()
    const { sent, record } = meetingPage({ m: { ...meeting, status: 'IN_PROGRESS' } })
    let tries = 0
    server.use(
      http.post(`${API}/meetings/m1/actions`, async ({ request }) => {
        await record(request, '/meetings/m1/actions')
        tries += 1
        return tries === 1
          ? problem(422, { errors: [{ field: 'dueDate', code: 'DUE_IN_PAST', message: 'Please enter a date.' }] })
          : problem(403, { reason: 'RECUSED' })
      }),
    )
    renderRoute('/meetings/m1?tab=decisions')
    const actions = await screen.findByRole('region', { name: /^Actions from this item/ }, { timeout: 8000 })
    await user.click(within(actions).getByRole('button', { name: 'Add an action' }))
    const form = await screen.findByRole('dialog', { name: 'New action — from Item 5' })
    expect(
      within(form).getByText(
        'During the meeting, actions are saved as drafts. Send them to their owners once it is held.',
      ),
    ).toBeInTheDocument()
    expect(within(form).queryByRole('button', { name: 'Save and send to the owner' })).not.toBeInTheDocument()
    // Nothing to do yet: the title is asked for, in words, at its field.
    await user.click(within(form).getByRole('button', { name: 'Save as draft' }))
    const title = within(form).getByRole('textbox', { name: 'What is to be done' })
    expect(await within(form).findByText('Say what is to be done.')).toBeInTheDocument()
    expect(title).toHaveFocus()
    expect(sent).toHaveLength(0)
    await user.type(title, 'Board training on climate risk')
    await user.click(within(form).getByRole('button', { name: 'Save as draft' }))
    expect(await within(form).findByText(/^Please enter a date on or after today, for example/)).toBeInTheDocument()
    await user.click(within(form).getByRole('button', { name: 'Save as draft' }))
    expect(
      await within(form).findByText(
        'You declared an interest in this item, so you cannot raise or change its actions. Another secretary can.',
      ),
    ).toHaveAttribute('role', 'alert')
    // The same key on every try: a retry acts once.
    expect(new Set(sent.map((s) => s.key)).size).toBe(1)
    await user.click(within(form).getByRole('button', { name: 'Cancel' }))

    // Editing the draft: its warnings in words, the owner and the person involved by name.
    await user.click(within(actions).getByRole('button', { name: 'Edit Report covenant compliance quarterly' }))
    const edit = await screen.findByRole('dialog', { name: 'Edit the draft action' })
    expect(
      within(edit).getByText(
        'S. Chowdhury declared an interest in this item. They will see this action, but not the item.',
      ),
    ).toBeInTheDocument()
    expect(within(edit).getByRole('button', { name: 'Remove S. Chowdhury' })).toBeInTheDocument()
  }, 40_000)

  it('sends the meeting’s drafts from the meeting page and says which were not sent and why', async () => {
    const user = userEvent.setup()
    const { sent, record, state } = meetingPage({
      actions: [
        summary(),
        summary({
          id: 'a3',
          title: 'Board training on climate risk',
          sourceKind: 'MEETING',
          itemId: null,
          owner: null,
        }),
        summary({
          id: 'a4',
          reference: 'BRC A3/2026',
          status: 'OPEN',
          title: 'Revise the collateral policy',
          rag: { colour: 'GREEN', word: 'ON_TRACK' },
          sourceLabel: 'BRC 3/2026, item 6',
          itemId: 'i6',
        }),
      ],
    })
    server.use(
      http.post(`${API}/meetings/m1/actions/issue`, async ({ request }) => {
        await record(request, '/meetings/m1/actions/issue')
        state.actions = state.actions.map((a) =>
          a.id === 'a1' ? { ...a, status: 'OPEN', reference: 'BRC A4/2026' } : a,
        )
        return HttpResponse.json({
          results: [
            { actionId: 'a1', issued: true, reference: 'BRC A4/2026' },
            { actionId: 'a3', issued: false, reason: 'REQUIRED' },
          ],
        })
      }),
    )
    const { container } = renderRoute('/meetings/m1')
    const section = await screen.findByRole('region', { name: 'Actions from this meeting' }, { timeout: 8000 })
    expect(within(section).getByText('1 action sent to its owner')).toBeInTheDocument()
    expect(within(section).getByText('2 draft actions not sent')).toBeInTheDocument()
    // A sent action opens on its page; a draft has Edit; every row says its colour or "Draft" in words.
    expect(within(section).getByRole('link', { name: 'Open BRC A3/2026' })).toHaveAttribute('href', '/actions/a4')
    expect(within(section).getByText('On track')).toBeInTheDocument()
    expect(within(section).getAllByText('Draft')).toHaveLength(2)
    await expectNoA11yViolations(container)
    await user.click(within(section).getByRole('button', { name: 'Send the 2 drafts to their owners' }))
    const said = await within(section).findByText(/^1 sent\. 1 draft was not sent:/)
    expect(said).toHaveFocus()
    expect(said).toHaveTextContent('Board training on climate risk: Choose who owns the action. Choose when it is due.')
    expect(sent.filter((s) => s.path === '/meetings/m1/actions/issue')).toHaveLength(1)
  }, 40_000)

  it('"Mark as held" offers to send the drafts, ticked; the drafts go once the meeting is held', async () => {
    const user = userEvent.setup()
    const live = { ...meeting, status: 'IN_PROGRESS' as const }
    const { sent, record } = meetingPage({ m: live, actions: [summary(), summary({ id: 'a3' })] })
    server.use(
      http.post(`${API}/meetings/m1/end`, async ({ request }) => {
        await record(request, '/meetings/m1/end')
        return HttpResponse.json<MeetingResponse>({ ...live, status: 'HELD' })
      }),
      http.post(`${API}/meetings/m1/actions/issue`, async ({ request }) => {
        await record(request, '/meetings/m1/actions/issue')
        return HttpResponse.json({
          results: [
            { actionId: 'a1', issued: true, reference: 'BRC A4/2026' },
            { actionId: 'a3', issued: true, reference: 'BRC A5/2026' },
          ],
        })
      }),
    )
    renderRoute('/meetings/m1')
    await user.click(await screen.findByRole('button', { name: /^Actions for / }, { timeout: 8000 }))
    await user.click(await screen.findByRole('menuitem', { name: /held/i }))
    const dialog = await screen.findByRole('dialog')
    const tick = await within(dialog).findByRole('checkbox', {
      name: 'Send the 2 draft actions to their owners now',
    })
    expect(tick).toBeChecked()
    expect(tick.closest('label')?.className).toMatch(/min-h-11/)
    await user.click(within(dialog).getByRole('button', { name: /held/i }))
    expect(await screen.findByText('The meeting is held. 2 actions were sent to their owners.')).toBeInTheDocument()
    expect(sent.map((s) => s.path)).toEqual(['/meetings/m1/end', '/meetings/m1/actions/issue'])
  }, 40_000)

  it('a recused secretary (ADR-0062) gets no action buttons on the item they declared an interest in', async () => {
    meetingPage({
      items: [
        item('i5', '5', 'Credit facility — Karnaphuli Textiles Ltd', { recused: true }),
        item('i6', '6', 'Working capital, Karnaphuli'),
      ],
    })
    const user = userEvent.setup()
    renderRoute('/meetings/m1?tab=decisions')
    await user.click(await screen.findByRole('button', { name: /^Item 5 — Credit facility/ }, { timeout: 8000 }))
    const p = await screen.findByRole('region', { name: 'Item 5 — Credit facility — Karnaphuli Textiles Ltd' })
    // The decision is there in words, without the secretary's buttons.
    expect(await within(p).findByText('Quarterly covenant report to the committee.')).toBeInTheDocument()
    expect(within(p).queryByRole('button', { name: /Make condition/ })).not.toBeInTheDocument()
    expect(within(p).queryByRole('region', { name: /^Actions from this item/ })).not.toBeInTheDocument()
    expect(within(p).queryByRole('button', { name: 'Add an action' })).not.toBeInTheDocument()
  }, 30_000)

  it('a member sees no officer lists on the meeting page', async () => {
    meetingPage({ m: { ...meeting, relation: 'MEMBER', canManage: false } })
    renderRoute('/meetings/m1')
    expect(await screen.findByText('Saturday, December 12, 2026', {}, { timeout: 8000 })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Actions from this meeting' })).not.toBeInTheDocument()
  }, 30_000)
})

/** The action page as a secretary, a recused secretary or a member: the server's `can` decides the buttons. */
function actionPage(view: ActionResponse) {
  const { sent, record } = recorder()
  let current = view
  const entries: ActionEntriesResponse = {
    actionId: view.id,
    entries: [
      {
        seq: 3,
        kind: 'DONE',
        at: '2026-12-02T10:00:00Z',
        actor: { id: 'u1', name: 'A. Karim' },
        text: 'Plan revised and approved by ALCO on 1 Dec.',
        details: {},
        files: [{ id: 'f9', name: 'CFP-2026-v3.pdf', mediaType: 'application/pdf', sizeBytes: 120_000 }],
      },
    ],
  }
  server.use(
    sessionHandler(signedInSession()),
    accessHandler([]),
    csrfHandler(),
    directory,
    http.get(`${API}/actions/${view.id}`, () => HttpResponse.json<ActionResponse>(current)),
    http.get(`${API}/actions/${view.id}/entries`, () => HttpResponse.json<ActionEntriesResponse>(entries)),
  )
  const answer = (path: string, respond: (body: Record<string, unknown> | undefined) => Response | ActionResponse) => {
    server.use(
      http.post(`${API}/actions/${view.id}/${path}`, async ({ request }) => {
        const s = await record(request, path)
        const out = respond(s.body)
        if (out instanceof Response) return out
        current = out
        return HttpResponse.json<ActionResponse>(out)
      }),
    )
  }
  return { sent, answer }
}

describe('The officers’ buttons on the action page (flow AN)', () => {
  it('offers “Keep out of the next report” only when the server’s can.holdBack says so', async () => {
    actionPage(action({ can: { ...SECRETARY, holdBack: false } }))
    renderRoute('/actions/a9')
    const officer = await screen.findByRole('region', { name: 'For the committee’s officers' }, { timeout: 8000 })
    expect(within(officer).getByRole('button', { name: 'Edit' })).toBeInTheDocument()
    expect(within(officer).queryByRole('button', { name: 'Keep out of the next report' })).not.toBeInTheDocument()
  }, 30_000)

  it('shows the officer’s buttons the server allows, and changes the owner with a reason and If-Match', async () => {
    const user = userEvent.setup()
    const { sent, answer } = actionPage(action())
    answer('owner', () =>
      action({
        version: 5,
        owner: { kind: 'PERSON', person: { id: 'u4', name: 'A. Rahman' }, holder: { id: 'u4', name: 'A. Rahman' } },
      }),
    )
    const { container } = renderRoute('/actions/a9')
    const officer = await screen.findByRole('region', { name: 'For the committee’s officers' }, { timeout: 8000 })
    const names = within(officer)
      .getAllByRole('button')
      .map((b) => b.textContent)
    expect(names).toEqual([
      'Edit',
      'Record an update for the owner',
      'Change the owner',
      'Set the colour',
      'Keep out of the next report',
      'Cancel the action',
    ])
    expect(screen.getByRole('button', { name: 'Change the date' })).toBeInTheDocument()
    expect(screen.queryByText('There is nothing for you to do on this action.')).not.toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.click(within(officer).getByRole('button', { name: 'Change the owner' }))
    const dialog = await screen.findByRole('dialog', { name: 'Change the owner' })
    expect(within(dialog).getByText('Now: Head of Treasury (A. Karim)')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Change the owner' }))
    expect(await within(dialog).findByText('Choose who owns the action.')).toBeInTheDocument()
    expect(within(dialog).getByText('Give a reason.')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('radio', { name: 'A person' }))
    await user.type(within(dialog).getByRole('combobox', { name: 'Who' }), 'A. R')
    await user.click(await within(dialog).findByRole('option', { name: /A\. Rahman/ }))
    await user.type(within(dialog).getByRole('textbox', { name: 'Why it changes' }), 'A. Karim moved to Treasury Ops.')
    await user.click(within(dialog).getByRole('button', { name: 'Change the owner' }))
    const said = await screen.findByText('The owner was changed.')
    expect(said).toHaveFocus()
    expect(sent).toEqual([
      expect.objectContaining({
        path: 'owner',
        ifMatch: '"4"',
        body: { owner: { kind: 'PERSON', userId: 'u4' }, reason: 'A. Karim moved to Treasury Ops.' },
      }),
    ])
  }, 40_000)

  it('cancels with the risk accepted, recorded for the chair with how they said so; the chair needs no "how"', async () => {
    const user = userEvent.setup()
    const { sent, answer } = actionPage(action())
    answer('cancel', () => action({ status: 'CANCELLED', cancelKind: 'RISK_ACCEPTED', can: can({ reopen: true }) }))
    renderRoute('/actions/a9')
    await user.click(await screen.findByRole('button', { name: 'Cancel the action' }, { timeout: 8000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Cancel the action' })
    await user.click(within(dialog).getByRole('radio', { name: 'Risk accepted by the chair' }))
    expect(
      within(dialog).getByText('Only the chair accepts the risk. You record it for the chair: say how they told you.'),
    ).toBeInTheDocument()
    await user.type(within(dialog).getByRole('textbox', { name: 'Reason' }), 'Superseded by the new risk appetite.')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel the action' }))
    expect(await within(dialog).findByText('Say how the chair told you they accept the risk.')).toBeInTheDocument()
    expect(sent).toHaveLength(0)
    await user.click(within(dialog).getByRole('radio', { name: 'By phone' }))
    await user.click(within(dialog).getByRole('button', { name: 'Cancel the action' }))
    expect(await screen.findByText('The action was cancelled.')).toHaveFocus()
    expect(sent[0]?.body).toEqual({
      kind: 'RISK_ACCEPTED',
      reason: 'Superseded by the new risk appetite.',
      how: 'PHONE',
    })
    // Cancelled: the page now offers Reopen, as the server says.
    expect(await screen.findByRole('button', { name: 'Reopen' })).toBeInTheDocument()
  }, 40_000)

  it('sets the colour by hand, takes it away, holds it back, answers a date request; refusals in words', async () => {
    const user = userEvent.setup()
    const pending = action({
      can: { ...SECRETARY, decideDue: true },
      pendingRequest: {
        id: 'r1',
        newDate: '2027-01-20',
        reason: 'Stress results delayed.',
        requestedBy: { id: 'u1', name: 'A. Karim' },
        requestedAt: '2026-12-01T10:00:00Z',
      },
    })
    const { sent, answer } = actionPage(pending)
    answer('rag', (body) =>
      action({
        ...pending,
        version: 5,
        rag: {
          colour: 'AMBER',
          word: 'OVERRIDE_AMBER',
          override: { rag: 'AMBER', reason: String(body?.reason), by: { id: 'sec', name: 'K. Secretary' } },
        },
      }),
    )
    answer('report-hold', () => problem(409, { reason: 'NO_MEETING' }))
    answer('due-requests/r1/decide', () => action({ version: 6, dueDate: '2027-01-20', dueRevisions: 2 }))
    renderRoute('/actions/a9')
    await user.click(await screen.findByRole('button', { name: 'Set the colour' }, { timeout: 8000 }))
    let dialog = await screen.findByRole('dialog', { name: 'Set the colour by hand' })
    await user.click(within(dialog).getByRole('radio', { name: 'Amber: at risk' }))
    await user.type(within(dialog).getByRole('textbox', { name: 'Why it changes' }), 'Plan is with ALCO.')
    await user.click(within(dialog).getByRole('button', { name: 'Set the colour' }))
    expect(await screen.findByText('The colour was set by hand.')).toHaveFocus()
    expect(sent.find((s) => s.path === 'rag')?.body).toEqual({ rag: 'AMBER', reason: 'Plan is with ALCO.' })

    await user.click(screen.getByRole('button', { name: 'Keep out of the next report' }))
    dialog = await screen.findByRole('dialog', { name: 'Keep it out of the next report' })
    await user.type(within(dialog).getByRole('textbox', { name: 'Why' }), 'Discussed in camera.')
    await user.click(within(dialog).getByRole('button', { name: 'Keep it out' }))
    expect(
      await within(dialog).findByText(
        'The committee has no next meeting yet, so there is no report to keep it out of.',
      ),
    ).toHaveAttribute('role', 'alert')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    await user.click(screen.getByRole('button', { name: 'Answer the date request' }))
    dialog = await screen.findByRole('dialog', { name: 'The owner asks for a new date' })
    expect(
      within(dialog).getByText(
        'A. Karim asks for January 20, 2027 instead of December 28, 2026: “Stress results delayed.”',
      ),
    ).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Answer' }))
    expect(await screen.findByText('The new date was agreed. The owner is told.')).toHaveFocus()
    expect(sent.find((s) => s.path === 'due-requests/r1/decide')).toMatchObject({
      body: { decision: 'AGREE' },
      ifMatch: '"5"',
    })
  }, 40_000)

  it('records an update the owner gave by phone, for the owner who holds the task', async () => {
    const user = userEvent.setup()
    const { sent, answer } = actionPage(action())
    answer('updates', () => action({ version: 5 }))
    renderRoute('/actions/a9')
    await user.click(await screen.findByRole('button', { name: 'Record an update for the owner' }, { timeout: 8000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Record an update for Head of Treasury (A. Karim)' })
    await user.type(within(dialog).getByRole('textbox', { name: 'What has happened?' }), 'Draft with Treasury Ops.')
    await user.click(within(dialog).getByRole('button', { name: 'Record the update' }))
    expect(await within(dialog).findByText('Say how you heard from the owner.')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('radio', { name: 'By phone' }))
    await user.click(within(dialog).getByRole('radio', { name: 'At risk' }))
    await user.click(within(dialog).getByRole('button', { name: 'Record the update' }))
    expect(await screen.findByText('The update was recorded for the owner.')).toHaveFocus()
    expect(sent[0]?.body).toEqual({
      text: 'Draft with Treasury Ops.',
      onBehalfOf: 'u1',
      how: 'PHONE',
      confidence: 'AT_RISK',
    })
  }, 40_000)

  it('a recused secretary (ADR-0062) and a member get no officer buttons: the server allows none', async () => {
    actionPage(action({ can: NOTHING, viewerRecused: false }))
    const { unmount } = renderRoute('/actions/a9')
    expect(await screen.findByRole('heading', { name: 'Revise the liquidity contingency plan' }, { timeout: 8000 }))
    expect(screen.queryByRole('region', { name: 'For the committee’s officers' })).not.toBeInTheDocument()
    expect(screen.getByText('There is nothing for you to do on this action.')).toBeInTheDocument()
    unmount()
  }, 30_000)

  it('verifies with what was done, its evidence and its memos in view (flow AM1)', async () => {
    const user = userEvent.setup()
    const { sent, answer } = actionPage(
      action({
        status: 'DONE_PENDING',
        rag: { colour: 'BLUE', word: 'TO_VERIFY' },
        can: can({ verify: true }),
        memos: [
          {
            memoId: 'mm1',
            kind: 'RAISED',
            readable: true,
            reference: 'MEMO/2026/0412',
            subject: 'Revised contingency funding plan',
            status: 'APPROVED',
            linkedAt: '2026-11-20T10:00:00Z',
          },
        ],
      }),
    )
    answer('verify', () => action({ status: 'CLOSED', rag: { colour: 'GREY', word: 'CLOSED' }, can: NOTHING }))
    renderRoute('/actions/a9')
    await user.click(await screen.findByRole('button', { name: 'Verify' }, { timeout: 8000 }))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText('Done by A. Karim on Dec 2, 2026:')).toBeInTheDocument()
    expect(within(dialog).getByText('“Plan revised and approved by ALCO on 1 Dec.”')).toBeInTheDocument()
    expect(within(dialog).getByText(/CFP-2026-v3\.pdf/)).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: /MEMO\/2026\/0412/ })).toHaveAttribute('href', '/memos/mm1')
    await user.click(within(dialog).getAllByRole('button').at(-1)!)
    await waitFor(() => {
      expect(sent.map((s) => s.path)).toEqual(['verify'])
    })
  }, 40_000)
})

const forum: ForumResponse = {
  id: 'f1',
  key: 'board-risk',
  code: 'BRC',
  codeLocked: false,
  name: 'Board Risk Committee',
  shortName: 'Risk',
  typeId: 't1',
  typeName: 'Board committee',
  status: 'ACTIVE',
  secretaries: [{ id: 'sec', name: 'K. Secretary' }],
  memberCount: 7,
  termWarnings: 0,
  canManageMembers: true,
  canManage: false,
  myRole: 'SECRETARY',
  version: 3,
}

const tracker = (over: Partial<ForumActionsResponse> = {}): ForumActionsResponse => ({
  forumId: 'f1',
  committee: 'Board Risk Committee',
  items: [
    summary({
      id: 'a9',
      reference: 'BRC A9/2026',
      status: 'IN_PROGRESS',
      title: 'Revise the liquidity contingency plan',
      owner: { kind: 'POSITION', position: { id: 'p2', title: 'Head of Treasury' } },
      dueDate: '2026-11-28',
      dueRevisions: 1,
      rag: { colour: 'RED', word: 'OVERDUE' },
      sourceLabel: 'BRC 3/2026, item 7',
    }),
    summary({
      id: 'a11',
      reference: 'BRC A11/2026',
      status: 'OPEN',
      title: 'Board training on climate risk',
      owner: { kind: 'PERSON', person: { id: 'u9', name: 'Company Secretary' } },
      dueDate: '2026-12-02',
      rag: { colour: 'AMBER', word: 'AT_RISK' },
      sourceLabel: 'BRC 3/2026',
    }),
  ],
  nextCursor: null,
  matching: 2,
  totals: { red: 3, amber: 2, green: 7, toVerify: 2, closed: 4, drafts: 1, all: 19 },
  key: { amberWorkingDays: 10, staleWorkingDays: 20 },
  can: { raise: true, export: true, askForUpdates: true, askedToday: false },
  ...over,
})

function trackerPage(view: ForumActionsResponse | Response, f: ForumResponse = forum) {
  const { sent, record } = recorder()
  const asked: URLSearchParams[] = []
  server.use(
    sessionHandler(signedInSession()),
    accessHandler([]),
    csrfHandler(),
    http.get(`${API}/forums/f1`, () => HttpResponse.json<ForumResponse>(f)),
    http.get(`${API}/forums/f1/members`, () => HttpResponse.json([])),
    http.get(`${API}/forums/f1/policy`, () => HttpResponse.json<ForumPolicyResponse>(policy)),
    http.get(`${API}/forums/f1/meetings`, () => HttpResponse.json({ items: [], nextCursor: null })),
    http.get(`${API}/forums/f1/submissions`, () => HttpResponse.json([])),
    http.get(`${API}/authz/classification-levels`, () => HttpResponse.json([])),
    http.get(`${API}/forums/f1/actions`, ({ request }) => {
      asked.push(new URL(request.url).searchParams)
      return view instanceof Response ? view.clone() : HttpResponse.json<ForumActionsResponse>(view)
    }),
  )
  return { sent, record, asked }
}

describe('The committee’s action tracker (flow AN)', () => {
  it('lists the rows with their colour in words, the totals as toggles, the key with this committee’s numbers', async () => {
    const user = userEvent.setup()
    const { asked } = trackerPage(tracker())
    const { container, router } = renderRoute('/committees/f1?tab=actions')
    const region = await screen.findByRole('region', { name: 'Board Risk Committee — Actions' }, { timeout: 8000 })
    expect(router.state.location.search).toEqual({ tab: 'actions' })
    const rows = within(region).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('BRC A9/2026')
    expect(rows[0]).toHaveTextContent('Head of Treasury (no one holds it now)')
    expect(rows[0]).toHaveTextContent('Nov 28, 2026 (rev. 1)')
    expect(within(rows[0]!).getByText('Overdue').closest('[data-rag]')).toHaveAttribute('data-rag', 'RED')
    expect(within(rows[1]!).getByText('At risk')).toBeInTheDocument()
    expect(within(region).getByRole('link', { name: 'BRC A9/2026' })).toHaveAttribute('href', '/actions/a9')
    const totals = within(region).getByRole('group', { name: 'Totals' })
    expect(
      within(totals)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Overdue or off track: 3', 'Due soon or at risk: 2', 'On track: 7', 'To verify: 2'])
    expect(within(region).getByText('Closed or cancelled: 4 · Drafts: 1')).toBeInTheDocument()
    expect(within(region).getByText('2 actions match.')).toBeInTheDocument()
    expect(within(region).getByText('Due within 10 working days, at risk, or no recent update')).toBeInTheDocument()
    expect(
      within(region).getByText(
        'This committee: amber when due within 10 working days, at risk, or with no update for 20 working days.',
      ),
    ).toBeInTheDocument()
    expect(asked[0]?.getAll('status')).toEqual(['OPEN', 'IN_PROGRESS', 'DONE_PENDING'])
    expect(asked[0]?.get('sort')).toBe('DUE')
    await expectNoA11yViolations(container)

    await user.click(within(totals).getByRole('button', { name: 'Overdue or off track: 3' }))
    await waitFor(() => {
      expect(asked.at(-1)?.get('rag')).toBe('RED')
    })
    expect(within(totals).getByRole('button', { name: 'Overdue or off track: 3' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    await user.selectOptions(within(region).getByRole('combobox', { name: 'Colour' }), 'OVERDUE')
    await waitFor(() => {
      expect(asked.at(-1)?.get('overdue')).toBe('true')
    })
    await user.type(within(region).getByRole('searchbox', { name: 'Search actions' }), 'liquidity')
    await waitFor(() => {
      expect(asked.at(-1)?.get('text')).toBe('liquidity')
    })
  }, 40_000)

  it('asks owners for an update and exports, with the refusals in words (ALREADY_ASKED, too many exports)', async () => {
    const user = userEvent.setup()
    const { sent, record } = trackerPage(tracker())
    let asks = 0
    server.use(
      http.post(`${API}/forums/f1/actions/ask-for-updates`, async ({ request }) => {
        await record(request, 'ask')
        asks += 1
        return asks === 1 ? HttpResponse.json({ asked: 4 }) : problem(409, { reason: 'ALREADY_ASKED' })
      }),
      http.get(`${API}/forums/f1/actions/export`, () => problem(429, { type: 'urn:memo:problem:rate-limited' })),
    )
    const { unmount } = renderRoute('/committees/f1?tab=actions')
    let region = await screen.findByRole('region', { name: 'Board Risk Committee — Actions' }, { timeout: 8000 })
    await user.click(within(region).getByRole('button', { name: 'Ask owners for an update' }))
    expect(await within(region).findByText('4 owners were asked for an update.')).toHaveFocus()
    // Once a day: the button gives way to the words.
    expect(within(region).queryByRole('button', { name: 'Ask owners for an update' })).not.toBeInTheDocument()
    expect(
      within(region).getByText('Owners were asked for an update today. You can ask again tomorrow.'),
    ).toBeInTheDocument()
    unmount()
    // Another secretary asked a moment ago, after this page was read: the refusal in words.
    renderRoute('/committees/f1?tab=actions')
    region = await screen.findByRole('region', { name: 'Board Risk Committee — Actions' }, { timeout: 8000 })
    await user.click(within(region).getByRole('button', { name: 'Ask owners for an update' }))
    expect(
      await within(region).findByText('The owners were asked today already. You can ask again tomorrow.'),
    ).toHaveFocus()
    expect(sent[0]?.key).not.toBe(sent[1]?.key)
    await user.click(within(region).getByRole('button', { name: 'Export' }))
    expect(
      await within(region).findByText('You have exported ten times this hour. Try again later.'),
    ).toBeInTheDocument()
  }, 40_000)

  it('raises a direct action from "New action", with where it comes from', async () => {
    const user = userEvent.setup()
    const { sent, record } = trackerPage(tracker())
    server.use(
      directory,
      http.post(`${API}/forums/f1/actions`, async ({ request }) => {
        const s = await record(request, 'direct')
        return HttpResponse.json<ActionResponse>(
          action({ id: 'a20', reference: null, status: 'DRAFT', title: String(s.body?.title) }),
          { status: 201 },
        )
      }),
    )
    renderRoute('/committees/f1?tab=actions')
    const region = await screen.findByRole('region', { name: 'Board Risk Committee — Actions' }, { timeout: 8000 })
    await user.click(within(region).getByRole('button', { name: 'New action' }))
    const form = await screen.findByRole('dialog', { name: 'New action for the Board Risk Committee' })
    // No meeting is planned: the date is chosen, with quick picks.
    expect(within(form).queryByRole('radio', { name: /By the next meeting/ })).not.toBeInTheDocument()
    await user.type(within(form).getByRole('textbox', { name: 'What is to be done' }), 'Circulate the revised policy')
    await user.type(
      within(form).getByRole('textbox', { name: /Where it comes from/ }),
      'Chair’s instruction of 3 December',
    )
    await user.click(within(form).getByRole('button', { name: 'In a month' }))
    await user.type(within(form).getByRole('combobox', { name: 'Find a person to add' }), 'Ra')
    await user.click(await within(form).findByRole('option', { name: /R\. Hasan/ }))
    await user.click(within(form).getByRole('button', { name: 'Add this person' }))
    expect(within(form).getByRole('button', { name: 'Remove R. Hasan' })).toBeInTheDocument()
    await user.click(within(form).getByRole('button', { name: 'Save as draft' }))
    expect(await within(region).findByText('Saved as a draft: Circulate the revised policy.')).toHaveFocus()
    expect(sent[0]?.body).toMatchObject({
      title: 'Circulate the revised policy',
      sourceNote: 'Chair’s instruction of 3 December',
      contributors: ['u3'],
      owner: null,
    })
    expect(String(sent[0]?.body?.dueDate)).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  }, 40_000)

  it('offers the committee type’s default due date first, from the server’s business calendar', async () => {
    const user = userEvent.setup()
    const { sent, record } = trackerPage(tracker())
    server.use(
      directory,
      suggestedDueHandler({ defaultDue: 'WORKING_DAYS', basis: 'WORKING_DAYS', date: '2027-01-11', workingDays: 10 }),
      http.post(`${API}/forums/f1/actions`, async ({ request }) => {
        const s = await record(request, 'direct')
        return HttpResponse.json<ActionResponse>(
          action({ id: 'a21', reference: null, status: 'DRAFT', title: String(s.body?.title) }),
          { status: 201 },
        )
      }),
    )
    renderRoute('/committees/f1?tab=actions')
    const region = await screen.findByRole('region', { name: 'Board Risk Committee — Actions' }, { timeout: 8000 })
    await user.click(within(region).getByRole('button', { name: 'New action' }))
    const form = await screen.findByRole('dialog', { name: 'New action for the Board Risk Committee' })
    await user.type(within(form).getByRole('textbox', { name: 'What is to be done' }), 'Circulate the revised policy')
    const picks = within(form).getByRole('group', { name: 'Quick picks' })
    const first = await within(picks).findByRole('button', { name: 'In 10 working days (the committee’s usual)' })
    expect(within(picks).getAllByRole('button')[0]).toBe(first)
    await user.click(first)
    expect(within(form).getByText('Due January 11, 2027.')).toBeInTheDocument()
    await user.click(within(form).getByRole('button', { name: 'Save as draft' }))
    expect(await within(region).findByText('Saved as a draft: Circulate the revised policy.')).toHaveFocus()
    expect(sent[0]?.body).toMatchObject({ dueDate: '2027-01-11' })
  }, 40_000)

  it('says the owners were asked today instead of offering to ask again', async () => {
    trackerPage(tracker({ can: { raise: true, export: true, askForUpdates: false, askedToday: true } }))
    renderRoute('/committees/f1?tab=actions')
    const region = await screen.findByRole('region', { name: 'Board Risk Committee — Actions' }, { timeout: 8000 })
    expect(
      within(region).getByText('Owners were asked for an update today. You can ask again tomorrow.'),
    ).toBeInTheDocument()
    expect(within(region).queryByRole('button', { name: 'Ask owners for an update' })).not.toBeInTheDocument()
  }, 30_000)

  it('members see the rows without the officers’ buttons; someone with no rows is told so (404)', async () => {
    trackerPage(tracker({ can: { raise: false, export: false, askForUpdates: false, askedToday: false } }), {
      ...forum,
      myRole: 'MEMBER',
      canManageMembers: false,
    })
    const { unmount } = renderRoute('/committees/f1?tab=actions')
    const region = await screen.findByRole('region', { name: 'Board Risk Committee — Actions' }, { timeout: 8000 })
    expect(within(region).queryByRole('button', { name: 'New action' })).not.toBeInTheDocument()
    expect(within(region).queryByRole('button', { name: 'Export' })).not.toBeInTheDocument()
    expect(within(region).queryByRole('button', { name: 'Ask owners for an update' })).not.toBeInTheDocument()
    unmount()
    trackerPage(problem(404, {}), { ...forum, myRole: 'MEMBER' })
    renderRoute('/committees/f1?tab=actions')
    expect(
      await screen.findByText('There are no actions of this committee for you to see.', {}, { timeout: 8000 }),
    ).toBeInTheDocument()
  }, 40_000)

  it('in Arabic, right to left, the tracker has no serious axe findings and its words are Arabic', async () => {
    trackerPage(tracker())
    const { container } = renderRoute('/committees/f1?tab=actions', { locale: 'ar' })
    expect(
      await screen.findByRole('button', { name: 'متأخر أو خارج المسار: 3' }, { timeout: 8000 }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'إجراء جديد' })).toBeInTheDocument()
    expect(document.documentElement.dir).toBe('rtl')
    await expectNoA11yViolations(container)
  }, 40_000)
})

const report: ActionReportResponse = {
  reportId: 'r1',
  meetingId: 'm1',
  forumId: 'f1',
  committee: 'Board Risk Committee',
  meetingLabel: 'BRC 4/2026',
  revision: 1,
  revisions: [1],
  itemId: 'i3',
  builtAt: '2026-11-28T10:05:00Z',
  sections: [
    {
      section: 'TO_CLOSE',
      count: 2,
      corrections: [],
      rows: [
        {
          actionId: 'a9',
          reference: 'BRC A9/2026',
          sourceLabel: 'BRC 3/2026, item 7',
          title: 'Revise the liquidity contingency plan',
          owner: 'Head of Treasury',
          dueRevisions: 0,
          rag: { colour: 'BLUE', word: 'TO_VERIFY' },
          status: 'DONE_PENDING',
          memos: [],
          canClose: true,
        },
        {
          actionId: 'a10',
          reference: 'BRC A10/2026',
          sourceLabel: 'BRC 3/2026, item 8',
          title: 'Update the liquidity policy',
          owner: 'K. Secretary',
          dueRevisions: 0,
          rag: { colour: 'BLUE', word: 'TO_VERIFY' },
          status: 'DONE_PENDING',
          memos: [],
          canClose: true,
        },
      ],
    },
  ],
  more: 0,
  heldBack: 0,
  summary: 'Action taken report: 2 to close',
  key: { amberWorkingDays: 10, staleWorkingDays: 20 },
  can: { closeAgreed: true },
}

describe('Closing at the meeting (flow AM2)', () => {
  it('closes the actions the committee agreed and says which could not be closed by this officer', async () => {
    const user = userEvent.setup()
    const { sent, record } = recorder()
    server.use(
      sessionHandler(signedInSession()),
      accessHandler([]),
      csrfHandler(),
      http.get(`${API}/meetings/m1`, () => HttpResponse.json<MeetingResponse>({ ...meeting, status: 'IN_PROGRESS' })),
      http.get(`${API}/meetings/m1/action-report`, () => HttpResponse.json<ActionReportResponse>(report)),
      http.post(`${API}/meetings/m1/actions/close-agreed`, async ({ request }) => {
        await record(request, 'close')
        return HttpResponse.json({
          results: [
            { actionId: 'a9', closed: true, reference: 'BRC A9/2026' },
            { actionId: 'a10', closed: false, reason: 'NOT_VERIFIER' },
          ],
        })
      }),
    )
    const { container } = renderRoute('/meetings/m1/action-report')
    const region = await screen.findByRole('region', { name: 'Close what the committee agreed' }, { timeout: 8000 })
    await user.click(within(region).getByRole('button', { name: 'Close the actions the committee agreed (0)' }))
    expect(within(region).getByText('Tick at least one action.')).toBeInTheDocument()
    expect(within(region).getByRole('checkbox', { name: 'Agreed to close BRC A9/2026' })).toHaveFocus()
    await user.click(within(region).getByRole('checkbox', { name: 'Agreed to close BRC A9/2026' }))
    await user.click(within(region).getByRole('checkbox', { name: 'Agreed to close BRC A10/2026' }))
    await expectNoA11yViolations(container)
    await user.click(within(region).getByRole('button', { name: 'Close the actions the committee agreed (2)' }))
    const said = await within(region).findByText(/^1 closed\. 1 was not closed:/)
    expect(said).toHaveFocus()
    expect(said).toHaveTextContent('BRC A10/2026: you are its owner or involved in it, so another officer closes it')
    expect(sent[0]?.body).toEqual({ actionIds: ['a9', 'a10'] })
  }, 40_000)

  it('offers only the actions the server says this reader may close', async () => {
    const rows = report.sections[0]?.rows ?? []
    const mixed: ActionReportResponse = {
      ...report,
      sections: [
        { ...report.sections[0]!, rows: rows.map((r) => ({ ...r, canClose: r.actionId === 'a9' })) },
        ...report.sections.slice(1),
      ],
    }
    server.use(
      sessionHandler(signedInSession()),
      accessHandler([]),
      http.get(`${API}/meetings/m1/action-report`, () => HttpResponse.json<ActionReportResponse>(mixed)),
    )
    renderRoute('/meetings/m1/action-report')
    const region = await screen.findByRole('region', { name: 'Close what the committee agreed' }, { timeout: 8000 })
    expect(within(region).getByRole('checkbox', { name: 'Agreed to close BRC A9/2026' })).toBeInTheDocument()
    expect(within(region).queryByRole('checkbox', { name: 'Agreed to close BRC A10/2026' })).not.toBeInTheDocument()
  }, 30_000)

  it('is not offered to members, nor before the meeting starts', async () => {
    server.use(
      sessionHandler(signedInSession()),
      accessHandler([]),
      http.get(`${API}/meetings/m1/action-report`, () =>
        HttpResponse.json<ActionReportResponse>({ ...report, heldBack: null, can: { closeAgreed: false } }),
      ),
    )
    renderRoute('/meetings/m1/action-report')
    expect(await screen.findByText('Revise the liquidity contingency plan', {}, { timeout: 8000 })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Close what the committee agreed' })).not.toBeInTheDocument()
  }, 30_000)
})

const BASE = `${API}/admin/forum-types`
const version = (over: Partial<ForumTypeVersionResponse> = {}): ForumTypeVersionResponse => ({
  number: 2,
  state: 'PUBLISHED',
  revision: 4,
  name: 'Board committee',
  memberAttributes: [],
  quorum: { counts: 'VOTING', basis: 'IN_OFFICE', threshold: { kind: 'MAJORITY' }, minimum: 2 },
  outcomes: [{ kind: 'APPROVED', label: 'Approved', resolution: true }],
  termPolicy: {},
  submissionPolicy: {},
  agenda: { chairAgreement: false, sections: [] },
  meetings: {},
  minutes: {
    approval: 'AT_NEXT_MEETING',
    circulationWorkingDays: 5,
    draftWorkingDays: 10,
    memoResumes: 'AT_MEETING_END',
    resolutionNumbering: { parts: [{ kind: 'SEQUENCE', digits: 3 }], reset: 'YEARLY', fyStartMonth: 7 },
  },
  actions: {
    amberWorkingDays: 10,
    staleWorkingDays: 20,
    dueChange: 'SECRETARY_AGREES',
    verifiedBy: 'SECRETARY',
    evidenceRequired: false,
    defaultDue: 'NEXT_MEETING',
    defaultDueWorkingDays: 20,
    updateRequestWorkingDays: 5,
    definitionKey: 'action-item',
  },
  createdAt: '2026-11-18T08:00:00Z',
  ...over,
})

function typePage(versions: ForumTypeVersionResponse[], permissions: string[]) {
  const { sent, record } = recorder()
  server.use(
    sessionHandler(signedInSession(true)),
    accessHandler(permissions),
    csrfHandler(),
    http.get(BASE, () => HttpResponse.json<ForumTypeResponse[]>([])),
    http.get(`${BASE}/presets`, () =>
      HttpResponse.json([
        {
          key: 'BOARD_AUDIT_COMMITTEE',
          name: 'Board audit committee',
          settings: { actions: { ...version().actions, evidenceRequired: true } },
        },
      ]),
    ),
    http.get(`${BASE}/action-definitions`, () =>
      HttpResponse.json([{ key: 'action-item', name: 'Action items (built in)', routed: false }]),
    ),
    http.get(`${BASE}/:id`, () =>
      HttpResponse.json<ForumTypeResponse>({ id: 't1', code: 'BC', name: 'Board committee', active: true }),
    ),
    http.get(`${BASE}/:id/versions`, () => HttpResponse.json(versions)),
    http.get(`${BASE}/:id/versions/:number`, ({ params }) =>
      HttpResponse.json(versions.find((v) => String(v.number) === params.number) ?? versions[0]),
    ),
    http.get(`${BASE}/:id/versions/:number/minutes-template`, () =>
      HttpResponse.json({ problems: [], warnings: [], refusals: [], available: [], tags: [], owner: 'x' }),
    ),
    http.get(`${API}/authz/classification-levels`, () => HttpResponse.json([])),
    http.put(`${BASE}/:id/versions/:number`, async ({ request }) => {
      const s = await record(request, 'save')
      const actions = (s.body as { actions: { amberWorkingDays: number } }).actions
      if (actions.amberWorkingDays === 0) {
        return problem(422, {
          errors: [{ field: 'actions/amberWorkingDays', code: 'RANGE', message: 'Between 1 and 60 working days.' }],
        })
      }
      return HttpResponse.json(versions[0])
    }),
  )
  return { sent }
}

describe('The committee type’s Actions tab (flow AQ)', () => {
  it('a draft: changes the rules, copies a preset, saves with If-Match; a range problem shows at its field; the changes view words it', async () => {
    const user = userEvent.setup()
    const draft = version({ number: 3, state: 'DRAFT', revision: 5 })
    const { sent } = typePage([draft, version()], ['forum.types.manage'])
    const { container } = renderRoute('/admin/committee-types/t1?version=3')
    await user.click(await screen.findByRole('tab', { name: 'Actions' }, { timeout: 8000 }))
    const amber = screen.getByRole('spinbutton', { name: 'Amber when due within' })
    expect(amber).toHaveValue(10)
    expect(screen.getByRole('checkbox', { name: 'Use the “no recent update” rule' })).toBeChecked()
    expect(screen.getByRole('radio', { name: 'The secretary’s agreement' })).toBeChecked()
    expect(screen.getByRole('combobox', { name: /Reminders and escalation follow the workflow/ })).toHaveValue(
      'action-item',
    )
    expect(screen.getByRole('link', { name: 'Open in the builder' })).toHaveAttribute(
      'href',
      '/admin/workflows/action-item',
    )
    await expectNoA11yViolations(container)

    await user.clear(amber)
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Between 1 and 60 working days.', { selector: '.text-xs' })).toBeInTheDocument()
    await user.type(amber, '5')
    await user.click(screen.getByRole('checkbox', { name: 'Use the “no recent update” rule' }))
    await user.click(screen.getByRole('radio', { name: 'The chair' }))
    await user.selectOptions(screen.getByRole('combobox', { name: /Start from an example/ }), 'BOARD_AUDIT_COMMITTEE')
    await user.click(screen.getByRole('button', { name: 'Use these values' }))
    expect(screen.getByText('The example’s values are in the draft. Save to keep them.')).toBeInTheDocument()
    expect(
      screen.getByRole('checkbox', { name: 'Evidence is required when an action is marked as done' }),
    ).toBeChecked()
    // The example's values replace the actions settings, so set the amber again.
    await user.clear(amber)
    await user.type(amber, '5')
    await user.click(screen.getByRole('tab', { name: /Changes/ }))
    expect(document.body.textContent).toContain('Actions: evidence required when marked as done:Nochanges toYes')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => {
      expect(sent.filter((s) => s.path === 'save')).toHaveLength(2)
    })
    const saved = sent.at(-1)
    expect(saved?.ifMatch).toBe('"5"')
    expect((saved?.body as { actions: unknown }).actions).toEqual({
      ...version().actions,
      amberWorkingDays: 5,
      evidenceRequired: true,
    })
  }, 40_000)

  it('maker-checker: the approver reads the version in review without changing it', async () => {
    const user = userEvent.setup()
    const review = version({
      number: 3,
      state: 'IN_REVIEW',
      revision: 6,
      actions: { ...version().actions, staleWorkingDays: 0 },
      submittedBy: { id: 'u2', name: 'Admin Two' },
      submittedAt: '2026-11-18T10:20:00Z',
    })
    typePage([review, version()], ['forum.types.approve'])
    renderRoute('/admin/committee-types/t1?version=3')
    await user.click(await screen.findByRole('tab', { name: 'Actions' }, { timeout: 8000 }))
    expect(screen.getByRole('spinbutton', { name: 'Amber when due within' })).toBeDisabled()
    expect(screen.getByRole('checkbox', { name: 'Use the “no recent update” rule' })).not.toBeChecked()
    expect(screen.queryByRole('button', { name: 'Use these values' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve and publish' })).toBeInTheDocument()
  }, 30_000)
})

describe('Catalogue parts and refusal words', () => {
  it('loads the officer part with the meeting, committee and action pages, the Actions tab’s with the type pages', () => {
    expect(partsForPath('/meetings/m1')).toEqual(expect.arrayContaining(['actions', 'actionsOfficer', 'decisions']))
    expect(partsForPath('/committees/f1')).toEqual(expect.arrayContaining(['actions', 'actionsOfficer']))
    expect(partsForPath('/actions/a1')).toEqual(['actions', 'actionsOfficer'])
    expect(partsForPath('/admin/committee-types/t1')).toContain('forumActions')
    expect(partsForPath('/inbox')).toEqual(['inbox', 'actions'])
  })

  it('words every officer refusal of design §3 and the field problems', () => {
    const intl = createIntl({ locale: 'en', messages: {} })
    const terms = { forum: 'committee', Forum: 'Committee', forums: 'committees' }
    const refusal = (reason: string, status = 409) => officerRefusal(intl, terms, { status, reason })
    expect(refusal('RECUSED', 403)).toBe(
      'You declared an interest in this item, so you cannot raise or change its actions. Another secretary can.',
    )
    expect(refusal('NO_OVERRIDE')).toBe('No colour is set by hand on this action.')
    expect(refusal('NO_MEETING')).toBe(
      'The committee has no next meeting yet, so there is no report to keep it out of.',
    )
    expect(refusal('ALREADY_HELD')).toBe('This action is kept out of that meeting’s report already.')
    expect(refusal('ALREADY_ASKED')).toBe('The owners were asked today already. You can ask again tomorrow.')
    expect(refusal('TOO_LATE')).toBe('It is too late for this. Raise a new action instead.')
    expect(refusal('PENDING_REQUEST')).toBe('A new date was asked for already. Wait for the answer.')
    expect(refusal('NOT_VERIFIER', 403)).toBe('You are not the one to verify this action.')
    const field = (f: string, code: string) => officerFieldText(intl, terms, f, code)
    expect(field('owner/userId', 'OWNER_NOT_CLEARED')).toBe(
      'This person is not cleared for the action’s level, or is not active, and cannot own it.',
    )
    expect(field('contributors/1', 'RECUSED_NAMED')).toBe(
      'This person declared an interest in the item and cannot help with this action.',
    )
    expect(field('contributors', 'SAME_PERSON')).toBe('The owner is not also among the people involved.')
    expect(field('owner', 'SAME_OWNER')).toBe('This is the action’s owner already.')
    expect(field('classificationId', 'CLASSIFICATION')).toBe(
      'An action is never below its source’s level, nor above your own clearance.',
    )
    expect(field('conditionIndex', 'CONDITION')).toBe('The decision has no such condition.')
    expect(field('dueDate', 'NOT_EDITABLE')).toBe('Once the action is sent, this changes with its own button.')
    expect(field('supersededBy', 'SUPERSEDED_BY')).toBe('Choose the issued action of this committee that replaces it.')
    expect(field('dueDate', 'DUE_IN_PAST')).toMatch(
      /^Please enter a date on or after today, for example \d\d\/\d\d\/\d{4}\.$/,
    )
    expect(reasonWords(intl, terms, 'NOT_VERIFIER')).toBe(
      'you are its owner or involved in it, so another officer closes it',
    )
    expect(reasonWords(intl, terms, 'OWNER_NOT_CLEARED')).toBe(
      'This person is not cleared for the action’s level, or is not active, and cannot own it.',
    )
  })
})
