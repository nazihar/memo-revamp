import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { createIntl } from 'react-intl'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  ActionAbilities,
  ActionEntriesResponse,
  ActionResponse,
  ActionSummary,
  MemoTypeOption,
  MyActionsResponse,
} from '@/api/generated/types.gen'
import { partsForPath } from '@/i18n/catalogue-parts'
import {
  accessHandler,
  API_ORIGIN,
  csrfHandler,
  myActionsHandler,
  server,
  sessionHandler,
  signedInSession,
} from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'
import { actionRefusal, fieldProblems } from './problems'

const API = `${API_ORIGIN}/api/v1`

// The upload itself (ADR-0029) is FileUpload's: jsdom's fetch cannot send a file to a relative address, so here the
// control hands back the uploaded file at once, with the purpose and owner it was given.
vi.mock('@/components/page/FileUpload', () => ({
  FileUpload: ({
    label,
    purpose,
    owner,
    onUploaded,
  }: {
    label: string
    purpose: string
    owner?: string
    onUploaded: (u: { fileId: string; versionId: string; name: string }) => void
  }) => (
    <label>
      {label}
      <input
        type="file"
        data-purpose={purpose}
        data-owner={owner}
        onChange={(e) => {
          const file = e.target.files?.item(0)
          if (file) onUploaded({ fileId: `f-${file.name}`, versionId: 'v1', name: file.name })
        }}
      />
    </label>
  ),
}))

const can = (over: Partial<ActionAbilities> = {}): ActionAbilities => ({
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
  ...over,
})

/** What the owner may do on an open action of a committee whose secretary agrees new dates. */
const OWNER = can({ update: true, confidence: true, requestDue: true, raiseMemo: true, linkMemo: true, markDone: true })
/** A contributor: updates without "How is it going?", raises memos, never marks it done. */
const CONTRIBUTOR = can({ update: true, raiseMemo: true, linkMemo: true })

/** BRC A9/2026, owned by the Head of Treasury (A. Karim), overdue, its date revised once (flow AL's drawing). */
const action = (over: Partial<ActionResponse> = {}): ActionResponse => ({
  id: 'a1',
  forumId: 'f1',
  committee: 'Board Risk Committee',
  reference: 'BRC A9/2026',
  title: 'Revise the liquidity contingency plan',
  context: 'Revise the contingency funding plan to reflect the new stress scenarios.',
  description: null,
  classificationId: 'l2',
  contributors: [{ id: 'u3', name: 'R. Hasan' }],
  createdAt: '2026-11-01T09:00:00Z',
  dueChange: 'SECRETARY_AGREES',
  dueDate: '2026-11-28',
  originalDueDate: '2026-11-14',
  dueRevisions: 1,
  issuedAt: '2026-11-01T09:00:00Z',
  memos: [],
  owner: {
    kind: 'POSITION',
    position: { id: 'p1', title: 'Head of Treasury' },
    holder: { id: 'u1', name: 'A. Karim' },
  },
  priority: 'NORMAL',
  rag: { colour: 'RED', word: 'OVERDUE' },
  reopenCount: 0,
  source: {
    kind: 'DECISION',
    label: 'BRC 3/2026, item 7 — Resolution BRC/2026/011',
    meetingId: 'm3',
    itemId: 'i7',
    itemNumber: '7',
    itemTitle: 'Liquidity contingency plan',
  },
  status: 'IN_PROGRESS',
  stepName: 'Owner',
  version: 4,
  viewerRecused: false,
  warnings: [],
  can: OWNER,
  ...over,
})

const entries: ActionEntriesResponse = {
  actionId: 'a1',
  entries: [
    {
      seq: 4,
      kind: 'UPDATE',
      at: '2026-11-26T10:00:00Z',
      actor: { id: 'u1', name: 'A. Karim' },
      text: 'Draft plan with Treasury Ops; final by 2 Dec.',
      confidence: 'AT_RISK',
      details: {},
      files: [],
    },
    {
      seq: 3,
      kind: 'DUE_CHANGED',
      at: '2026-11-14T10:00:00Z',
      actor: { id: 'u9', name: 'S. Ahmed' },
      text: 'Stress results delayed.',
      details: { from: '2026-11-14', to: '2026-11-28', revisions: 1 },
      files: [],
    },
    {
      seq: 2,
      kind: 'UPDATE',
      at: '2026-11-10T10:00:00Z',
      actor: { id: 'u3', name: 'R. Hasan' },
      text: 'Stress scenarios received from Risk.',
      details: {},
      files: [{ id: 'f1', name: 'scenarios.xlsx', mediaType: 'application/vnd.ms-excel', sizeBytes: 1200 }],
    },
    {
      seq: 1,
      kind: 'ISSUED',
      at: '2026-11-01T09:00:00Z',
      actor: { id: 'u9', name: 'S. Ahmed' },
      details: { reference: 'BRC A9/2026', dueDate: '2026-11-14' },
      files: [],
    },
  ],
}

interface Sent {
  path: string
  body: unknown
  ifMatch: string | null
  key: string | null
}

/** The action page's backend: [view] (replaced by what a change answers), its timeline, and every POST it was sent. */
function actionPage(view: ActionResponse = action()) {
  let current = view
  const sent: Sent[] = []
  server.use(
    sessionHandler(signedInSession()),
    accessHandler(['memo.create']),
    csrfHandler(),
    http.get(`${API}/actions/a1`, () => HttpResponse.json<ActionResponse>(current)),
    http.get(`${API}/actions/a1/entries`, () => HttpResponse.json<ActionEntriesResponse>(entries)),
  )
  /** Answers POST [path] with [answer] (the action as it is after, or a problem with its status). */
  const answer = (path: string, respond: (body: unknown) => ActionResponse | { problem: object; status: number }) => {
    server.use(
      http.post(`${API}/actions/a1/${path}`, async ({ request }) => {
        const body: unknown = await request.json().catch(() => undefined)
        sent.push({
          path,
          body,
          ifMatch: request.headers.get('If-Match'),
          key: request.headers.get('Idempotency-Key'),
        })
        const out = respond(body)
        if ('problem' in out) {
          return HttpResponse.json(out.problem, {
            status: out.status,
            headers: { 'Content-Type': 'application/problem+json' },
          })
        }
        current = out
        return HttpResponse.json<ActionResponse>(out)
      }),
    )
  }
  return { sent, answer }
}

const summary = (over: Partial<ActionSummary> = {}): ActionSummary => ({
  id: 'a1',
  forumId: 'f1',
  committee: 'Board Risk Committee',
  reference: 'BRC A9/2026',
  title: 'Revise the liquidity contingency plan',
  dueDate: '2026-11-28',
  originalDueDate: '2026-11-14',
  dueRevisions: 1,
  priority: 'NORMAL',
  rag: { colour: 'RED', word: 'OVERDUE' },
  sourceKind: 'DECISION',
  sourceLabel: 'BRC 3/2026, item 7',
  status: 'IN_PROGRESS',
  version: 4,
  ...over,
})

const mine: MyActionsResponse = {
  tab: 'MINE',
  counts: { mine: 3, toVerify: 2, contributing: 1 },
  nextCursor: null,
  items: [
    summary(),
    summary({
      id: 'a2',
      committee: 'Management Credit Committee',
      reference: 'MCC A1/2026',
      title: 'Report covenant compliance quarterly',
      dueDate: '2026-12-05',
      originalDueDate: '2026-12-05',
      dueRevisions: 0,
      rag: { colour: 'AMBER', word: 'DUE_SOON' },
    }),
    summary({
      id: 'a3',
      reference: 'BRC A14/2026',
      title: 'Present the revised collateral policy',
      dueDate: '2027-03-12',
      originalDueDate: '2027-03-12',
      dueRevisions: 0,
      rag: { colour: 'GREEN', word: 'ON_TRACK' },
    }),
  ],
}

beforeEach(() => {
  server.events.removeAllListeners()
  server.use(
    sessionHandler(signedInSession()),
    accessHandler([]),
    http.get(`${API}/inbox/tasks`, () => HttpResponse.json({ items: [], nextCursor: null })),
  )
})

describe('My actions in the inbox (Phase 4 E07 T11, flow AK)', () => {
  it('shows My actions beside My tasks with what waits, each list counted, rows with their colour in words', async () => {
    const asked: string[] = []
    server.use(
      http.get(`${API}/me/actions`, ({ request }) => {
        const tab = new URL(request.url).searchParams.get('tab') ?? 'MINE'
        asked.push(tab)
        return HttpResponse.json<MyActionsResponse>(
          tab === 'TO_VERIFY' ? { ...mine, tab: 'TO_VERIFY', items: [] } : { ...mine, tab: tab as 'MINE' },
        )
      }),
    )
    const user = userEvent.setup()
    const { container, router } = renderRoute('/inbox')
    const tab = await screen.findByRole('tab', { name: 'My actions (5)' }, { timeout: 5000 })
    const tabs = screen.getAllByRole('tab').map((t) => t.textContent)
    expect(tabs.slice(0, 2)).toEqual(['My tasks', 'My actions (5)'])
    await user.click(tab)
    expect(router.state.location.search).toEqual({ tab: 'actions' })

    expect(await screen.findByRole('button', { name: 'To do (3)', pressed: true })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'To verify (2)', pressed: false })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'I’m involved (1)', pressed: false })).toBeInTheDocument()
    const rows = screen.getAllByRole('link', { name: /A\d+\/20\d\d/ })
    expect(rows.map((r) => r.getAttribute('href'))).toEqual(['/actions/a1', '/actions/a2', '/actions/a3'])
    // The colour is a word with an icon, never colour alone; the date says where it started.
    expect(rows[0]).toHaveTextContent('Overdue')
    expect(rows[0]).toHaveTextContent('Board Risk Committee · due Nov 28, 2026 (originally Nov 14, 2026, revised once)')
    expect(rows[1]).toHaveTextContent('Due soon')
    expect(rows[1]).toHaveTextContent('Management Credit Committee · due Dec 5, 2026')
    expect(rows[2]).toHaveTextContent('On track')
    expect(within(rows[0]!).getByText('Overdue').closest('[data-rag]')).toHaveAttribute('data-rag', 'RED')
    expect(screen.getByRole('heading', { name: 'Key' })).toBeInTheDocument()
    expect(screen.getByText('Due soon, at risk, or no recent update')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'To verify (2)' }))
    expect(router.state.location.search).toEqual({ tab: 'actions', list: 'verify' })
    expect(
      await screen.findByText(
        'Nothing is waiting for you to verify. Actions marked as done that you check will appear here.',
      ),
    ).toBeInTheDocument()
    expect(asked).toContain('TO_VERIFY')
    await expectNoA11yViolations(container)
  })

  it('adds no tab for a person with no actions, and says what would appear when opened by its address', async () => {
    const { router } = renderRoute('/inbox')
    expect(await screen.findByRole('tab', { name: 'My tasks' }, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: /My actions/ })).toBeNull()
    await router.navigate({ to: '/inbox', search: { tab: 'actions' } })
    expect(
      await screen.findByText('You have no actions. Actions given to you by a committee will appear here.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'My actions', selected: true })).toBeInTheDocument()
  })

  it('loads the actions part with the inbox, the action page and the report, never in core', () => {
    expect(partsForPath('/inbox')).toEqual(['inbox', 'actions'])
    expect(partsForPath('/actions/a1')).toEqual(['actions'])
    expect(partsForPath('/meetings/m1/action-report')).toEqual(expect.arrayContaining(['actions', 'actionLinks']))
    expect(partsForPath('/memos/m1')).toContain('actionLinks')
    expect(partsForPath('/memos/m1')).not.toContain('actions')
  })
})

describe('the action page (Phase 4 E07 T11, flow AL)', () => {
  it('says who, by when and from where, with the owner’s buttons and the timeline in words', async () => {
    actionPage()
    const { container } = renderRoute('/actions/a1')
    expect(
      await screen.findByRole(
        'heading',
        { level: 1, name: 'Revise the liquidity contingency plan' },
        { timeout: 5000 },
      ),
    )
    expect(screen.getByText('BRC A9/2026 · Board Risk Committee')).toBeInTheDocument()
    expect(screen.getAllByText('Overdue')[0]?.closest('[data-rag]')).toHaveAttribute('data-rag', 'RED')
    const facts = container.querySelector('dl') as HTMLElement
    const fact = (term: string) => within(facts).getByText(term).nextElementSibling?.textContent
    expect(fact('Owner')).toBe('Head of Treasury (A. Karim)')
    expect(fact('Also')).toBe('R. Hasan')
    expect(fact('Due')).toBe('November 28, 2026originally November 14, 2026, revised once')
    expect(fact('From')).toContain('BRC 3/2026, item 7 — Resolution BRC/2026/011: Liquidity contingency plan')
    expect(within(facts).getByRole('link', { name: 'Open the meeting' })).toHaveAttribute('href', '/meetings/m3')
    expect(fact('What the committee asked')).toBe(
      '“Revise the contingency funding plan to reflect the new stress scenarios.”',
    )
    expect(fact('Where it stands')).toBe('In progress · Owner')

    const next = screen.getByRole('region', { name: 'What you can do' })
    expect(
      within(next)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Post an update', 'Ask for a new date', 'Raise a memo', 'Link an existing memo', 'Mark as done'])

    const timeline = screen.getByRole('region', { name: 'Timeline' })
    await within(timeline).findAllByRole('listitem')
    const items = Array.from(timeline.querySelectorAll(':scope ol > li'))
    expect(items.map((i) => i.querySelector('p')?.textContent)).toEqual([
      'Update from A. Karim · At risk',
      'Due date changed from November 14, 2026 to November 28, 2026 by S. Ahmed',
      'Update from R. Hasan',
      'Sent to its owner by S. Ahmed, due November 14, 2026',
    ])
    expect(within(timeline).getByText('“Stress results delayed.”')).toBeInTheDocument()
    expect(within(timeline).getByRole('link', { name: 'scenarios.xlsx' })).toHaveAttribute(
      'href',
      '/api/v1/files/f1/content',
    )
    expect(screen.getByRole('region', { name: 'Follow-up memos' })).toHaveTextContent(
      'No memo follows up this action yet.',
    )
    await expectNoA11yViolations(container)
  })

  it('posts an update with how it is going and a file, then says so and moves focus there', async () => {
    const { sent, answer } = actionPage()
    answer('updates', () => action({ version: 5, confidence: 'AT_RISK', rag: { colour: 'AMBER', word: 'AT_RISK' } }))
    const user = userEvent.setup()
    renderRoute('/actions/a1')
    await user.click(await screen.findByRole('button', { name: 'Post an update' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Post an update' })

    // Nothing written: the field says so and takes focus, and nothing is sent.
    await user.click(within(dialog).getByRole('button', { name: 'Post the update' }))
    const text = within(dialog).getByRole('textbox', { name: /What has happened\?/ })
    expect(text).toHaveFocus()
    expect(text).toHaveAccessibleDescription('This is needed.')
    expect(sent).toHaveLength(0)

    await user.type(text, 'Draft plan shared with Treasury Ops.')
    const how = within(dialog).getByRole('group', { name: 'How is it going?' })
    expect(
      within(how)
        .getAllByRole('radio')
        .map((r) => r.closest('label')?.textContent),
    ).toEqual(['On track', 'At risk', 'Off track'])
    await user.click(within(how).getByRole('radio', { name: 'At risk' }))
    await user.upload(
      within(dialog).getByLabelText('Attach files'),
      new File(['%PDF'], 'plan.pdf', { type: 'application/pdf' }),
    )
    expect(await within(dialog).findByText('plan.pdf is attached.')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Remove plan.pdf' })).toBeInTheDocument()
    const input = within(dialog).getByLabelText('Attach files')
    expect(input).toHaveAttribute('data-purpose', 'action.evidence')
    expect(input).toHaveAttribute('data-owner', 'a1')
    await user.click(within(dialog).getByRole('button', { name: 'Post the update' }))

    const said = await screen.findByText('Your update was posted.')
    expect(said).toHaveAttribute('role', 'status')
    await waitFor(() => {
      expect(said).toHaveFocus()
    })
    expect(sent).toEqual([
      {
        path: 'updates',
        body: { text: 'Draft plan shared with Treasury Ops.', confidence: 'AT_RISK', fileIds: ['f-plan.pdf'] },
        ifMatch: '"4"',
        key: expect.any(String) as unknown,
      },
    ])
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(await screen.findByText('At risk', { selector: '[data-rag] *, [data-rag]' })).toBeInTheDocument()
  })

  it('asks for a new date, says the date stays until agreed, and words a refusal (409 PENDING_REQUEST)', async () => {
    const { sent, answer } = actionPage()
    let refuse = true
    answer('due-requests', () =>
      refuse
        ? {
            status: 409,
            problem: { status: 409, reason: 'PENDING_REQUEST', detail: 'A new date was asked for already' },
          }
        : action({
            version: 5,
            pendingRequest: {
              id: 'r1',
              newDate: '2026-12-20',
              reason: 'Stress results delayed.',
              requestedAt: '2026-11-27T09:00:00Z',
              requestedBy: { id: 'u1', name: 'A. Karim' },
            },
          }),
    )
    const user = userEvent.setup()
    renderRoute('/actions/a1')
    await user.click(await screen.findByRole('button', { name: 'Ask for a new date' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Ask for a new date' })
    expect(dialog).toHaveAccessibleDescription(
      'The committee secretary will be asked to agree. Until then the date stays November 28, 2026.',
    )
    await user.type(within(dialog).getByLabelText(/New date/), '2026-12-20')
    await user.type(within(dialog).getByRole('textbox', { name: /Why/ }), 'Stress results delayed.')
    await user.click(within(dialog).getByRole('button', { name: 'Ask for this date' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'A new date was asked for already. Wait for the answer.',
    )

    refuse = false
    await user.click(within(dialog).getByRole('button', { name: 'Ask for this date' }))
    expect(
      await screen.findByText('Your request was sent. The date stays the same until it is agreed.'),
    ).toBeInTheDocument()
    expect(sent.map((s) => s.body)).toEqual([
      { newDate: '2026-12-20', reason: 'Stress results delayed.' },
      { newDate: '2026-12-20', reason: 'Stress results delayed.' },
    ])
    // One key per dialog: a retry of the same request is the same request.
    expect(sent[0]?.key).toBe(sent[1]?.key)
    expect(
      screen.getByText(
        'New date requested: December 20, 2026. Until it is agreed, the action stays due November 28, 2026.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByText('Asked by A. Karim: “Stress results delayed.”')).toBeInTheDocument()
  })

  it('marks it as done with what was done; evidence the committee requires is asked for at the field', async () => {
    const { sent, answer } = actionPage()
    answer('done', (body) =>
      (body as { fileIds?: string[] }).fileIds?.length
        ? action({ version: 5, status: 'DONE_PENDING', rag: { colour: 'BLUE', word: 'TO_VERIFY' }, can: can() })
        : {
            status: 422,
            problem: {
              status: 422,
              type: 'urn:memo:problem:validation',
              errors: [
                {
                  field: 'fileIds',
                  code: 'EVIDENCE_REQUIRED',
                  message: 'This committee asks for evidence: attach the files.',
                },
              ],
            },
          },
    )
    const user = userEvent.setup()
    renderRoute('/actions/a1')
    await user.click(await screen.findByRole('button', { name: 'Mark as done' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Mark as done' })
    await user.type(
      within(dialog).getByRole('textbox', { name: /What was done/ }),
      'Plan revised and approved by ALCO.',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Mark as done' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'This committee asks for evidence: attach the files.',
    )

    await user.upload(
      within(dialog).getByLabelText('Evidence'),
      new File(['%PDF'], 'plan.pdf', { type: 'application/pdf' }),
    )
    await within(dialog).findByText('plan.pdf is attached.')
    await user.click(within(dialog).getByRole('button', { name: 'Mark as done' }))
    expect(
      await screen.findByText('Sent to the committee secretary to verify. You will be told when it is closed.', {
        selector: '[role=status]',
      }),
    ).toBeInTheDocument()
    expect(sent[1]?.body).toEqual({ note: 'Plan revised and approved by ALCO.', fileIds: ['f-plan.pdf'] })
    expect(screen.getAllByText('Done, to be verified')[0]?.closest('[data-rag]')).toHaveAttribute('data-rag', 'BLUE')
    expect(screen.queryByRole('button', { name: 'Mark as done' })).toBeNull()
  })

  it('words the holder rule (403 NOT_HOLDER) and reloads an action that moved on (409 WRONG_STATE)', async () => {
    const { answer } = actionPage()
    let reason = 'NOT_HOLDER'
    answer('done', () => ({ status: reason === 'NOT_HOLDER' ? 403 : 409, problem: { status: 403, reason } }))
    let reads = 0
    server.use(
      http.get(`${API}/actions/a1`, () => {
        reads += 1
        return HttpResponse.json<ActionResponse>(action())
      }),
    )
    const user = userEvent.setup()
    renderRoute('/actions/a1')
    await user.click(await screen.findByRole('button', { name: 'Mark as done' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Mark as done' })
    await user.type(within(dialog).getByRole('textbox', { name: /What was done/ }), 'Done.')
    await user.click(within(dialog).getByRole('button', { name: 'Mark as done' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Whoever holds the action now marks it as done. Someone is acting for you.',
    )
    const before = reads
    reason = 'WRONG_STATE'
    await user.click(within(dialog).getByRole('button', { name: 'Mark as done' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'The action has moved on meanwhile, so this cannot be done now. It has been reloaded.',
    )
    await waitFor(() => {
      expect(reads).toBeGreaterThan(before)
    })
  })

  it('gives a contributor updates without “How is it going?” and no Mark as done', async () => {
    const { sent, answer } = actionPage(action({ can: CONTRIBUTOR }))
    answer('updates', () => action({ version: 5, can: CONTRIBUTOR }))
    const user = userEvent.setup()
    renderRoute('/actions/a1')
    const next = await screen.findByRole('region', { name: 'What you can do' }, { timeout: 5000 })
    expect(
      within(next)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Post an update', 'Raise a memo', 'Link an existing memo'])
    await user.click(within(next).getByRole('button', { name: 'Post an update' }))
    const dialog = await screen.findByRole('dialog', { name: 'Post an update' })
    expect(within(dialog).queryByRole('group', { name: 'How is it going?' })).toBeNull()
    await user.type(within(dialog).getByRole('textbox', { name: /What has happened\?/ }), 'Scenarios sent.')
    await user.click(within(dialog).getByRole('button', { name: 'Post the update' }))
    await screen.findByText('Your update was posted.')
    expect(sent[0]?.body).toEqual({ text: 'Scenarios sent.' })
  })

  it('shows a member the action and its timeline with nothing to do', async () => {
    actionPage(action({ can: can(), contributors: [] }))
    renderRoute('/actions/a1')
    expect(await screen.findByText('There is nothing for you to do on this action.', {}, { timeout: 5000 }))
    expect(within(screen.getByRole('region', { name: 'What you can do' })).queryAllByRole('button')).toHaveLength(0)
    expect(screen.getByRole('region', { name: 'Timeline' })).toBeInTheDocument()
  })

  it('shows a recused owner the action with the item’s number only, no link to it, and why (ADR-0062)', async () => {
    actionPage(
      action({
        viewerRecused: true,
        source: {
          kind: 'ITEM',
          label: 'BRC 3/2026, item 7',
          meetingId: 'm3',
          itemId: 'i7',
          itemNumber: '7',
          itemTitle: null,
        },
      }),
    )
    const { container } = renderRoute('/actions/a1')
    expect(
      await screen.findByText(
        'You declared an interest in the item this action comes from. You see the action because you are named on it, but not the item or its papers.',
        {},
        { timeout: 5000 },
      ),
    ).toBeInTheDocument()
    expect(screen.getByText('BRC 3/2026, item 7')).toBeInTheDocument()
    expect(screen.queryByText(/Liquidity contingency plan/)).toBeNull()
    expect(screen.queryByRole('link', { name: 'Open the meeting' })).toBeNull()
    // Their own actions are theirs as usual.
    expect(screen.getByRole('button', { name: 'Mark as done' })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('says an action the viewer may not see is not there (404, never 403)', async () => {
    server.use(http.get(`${API}/actions/a1`, () => HttpResponse.json({ status: 404 }, { status: 404 })))
    renderRoute('/actions/a1')
    expect(
      await screen.findByText('This action is not there, or it is not yours to see.', {}, { timeout: 5000 }),
    ).toBeInTheDocument()
  })

  it('lets the verifier close or send back, a send-back needing a remark (flow AM’s dialog)', async () => {
    const { sent, answer } = actionPage(
      action({ status: 'DONE_PENDING', rag: { colour: 'BLUE', word: 'TO_VERIFY' }, can: can({ verify: true }) }),
    )
    answer('verify', () => action({ version: 5, status: 'IN_PROGRESS', can: can() }))
    const user = userEvent.setup()
    renderRoute('/actions/a1')
    await user.click(await screen.findByRole('button', { name: 'Verify' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Verify the action' })
    await user.click(within(dialog).getByRole('radio', { name: 'No: send it back to the owner' }))
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    const remark = within(dialog).getByRole('textbox', { name: /Remark/ })
    expect(remark).toHaveFocus()
    expect(remark).toHaveAccessibleDescription(/Say what is missing to send it back\./)
    await user.type(remark, 'The valuation report is not attached.')
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByText('The action went back to its owner.')).toBeInTheDocument()
    expect(sent[0]?.body).toEqual({ decision: 'SEND_BACK', remark: 'The valuation report is not attached.' })
  })
})

const types: MemoTypeOption[] = [
  {
    id: 't1',
    name: 'General memo',
    hasFields: false,
    numberingMoment: 'ON_SUBMIT',
    stepUpOnApprove: false,
    template: true,
  },
  {
    id: 't2',
    name: 'Credit proposal',
    hasFields: true,
    numberingMoment: 'ON_APPROVAL',
    stepUpOnApprove: true,
    template: true,
  },
]

describe('follow-up memos (Phase 4 E07 T11, flow AP)', () => {
  beforeEach(() => {
    server.use(
      http.get(`${API}/memo-types`, () => HttpResponse.json<MemoTypeOption[]>(types)),
      http.get(`${API}/authz/classification-levels`, () =>
        HttpResponse.json([
          { id: 'l1', name: 'Internal', rank: 1, version: 1 },
          { id: 'l2', name: 'Confidential', rank: 2, version: 1 },
        ]),
      ),
    )
  })

  it('raises a memo with the type chooser, the action’s title and level, a lower level only when confirmed', async () => {
    actionPage()
    const { router } = renderRoute('/actions/a1')
    server.use(
      http.get(`${API}/me/access`, () =>
        HttpResponse.json({
          permissions: [{ key: 'memo.create', tenantWide: true, scopeUnitIds: [] }],
          clearance: { rank: 2, name: 'Confidential' },
        }),
      ),
    )
    const bodies: unknown[] = []
    server.use(
      http.post(`${API}/actions/a1/memos`, async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json({ memoId: 'm9', templateFilled: true, action: action() }, { status: 201 })
      }),
    )
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Raise a memo' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Raise a memo from BRC A9/2026' })
    expect(within(dialog).getByRole('radio', { name: /General memo/ })).toBeChecked()
    expect(within(dialog).getByRole('textbox', { name: 'Subject' })).toHaveValue(
      'Revise the liquidity contingency plan',
    )
    expect(within(dialog).getByText('Confidential (from the action)')).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Change' }))
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Level' }), 'Internal')
    await user.click(within(dialog).getByRole('button', { name: 'Create the draft memo' }))
    expect(within(dialog).getByRole('combobox', { name: 'Level' })).toHaveAccessibleDescription(
      'This level is lower than the action’s. Tick the confirmation to use it.',
    )
    expect(bodies).toHaveLength(0)
    await user.click(
      within(dialog).getByRole('checkbox', {
        name: 'This memo will be Internal; the action is Confidential. Tick to confirm the lower level.',
      }),
    )
    await user.click(within(dialog).getByRole('radio', { name: /Credit proposal/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Create the draft memo' }))
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/memos/m9')
    })
    expect(bodies).toEqual([
      {
        typeId: 't2',
        subject: 'Revise the liquidity contingency plan',
        classificationId: 'l1',
        confirmLowerLevel: true,
      },
    ])
  })

  it('links an existing memo found by search, and words ALREADY_LINKED', async () => {
    const { answer } = actionPage()
    let already = true
    answer('memos/link', () =>
      already
        ? { status: 409, problem: { status: 409, reason: 'ALREADY_LINKED' } }
        : action({
            version: 5,
            memos: [
              {
                kind: 'LINKED',
                memoId: 'm8',
                linkedAt: '2026-11-27T09:00:00Z',
                readable: true,
                reference: 'MEMO/2026/0412',
                subject: 'Revised contingency funding plan',
                status: 'IN_REVIEW',
              },
            ],
          }),
    )
    const terms: string[] = []
    server.use(
      http.post(`${API}/search/suggest`, async ({ request }) => {
        const { q } = (await request.json()) as { q: string }
        terms.push(q)
        return HttpResponse.json([
          { memoId: 'm8', number: 'MEMO/2026/0412', subject: 'Revised contingency funding plan' },
        ])
      }),
    )
    const user = userEvent.setup()
    renderRoute('/actions/a1')
    await user.click(await screen.findByRole('button', { name: 'Link an existing memo' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Link an existing memo' })
    await user.type(
      within(dialog).getByRole('searchbox', { name: 'Find a memo by its number or subject' }),
      'contingency',
    )
    const link = await within(dialog).findByRole('button', {
      name: 'Link MEMO/2026/0412 — Revised contingency funding plan',
    })
    expect(terms.at(-1)).toBe('contingency')
    await user.click(link)
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('This memo already follows up the action.')
    already = false
    await user.click(link)
    expect(await screen.findByText('The memo is linked to this action.')).toBeInTheDocument()
    const memos = screen.getByRole('region', { name: 'Follow-up memos' })
    expect(
      within(memos).getByRole('link', { name: 'MEMO/2026/0412 — Revised contingency funding plan' }),
    ).toHaveAttribute('href', '/memos/m8')
    expect(within(memos).getByText('In review')).toBeInTheDocument()
  })

  it('shows a memo the viewer cannot read without its number, and reminds the owner once it is approved', async () => {
    actionPage(
      action({
        memos: [
          {
            kind: 'RAISED',
            memoId: 'm8',
            linkedAt: '2026-11-27T09:00:00Z',
            readable: true,
            reference: 'MEMO/2026/0412',
            subject: 'Revised plan',
            status: 'APPROVED',
          },
          { kind: 'LINKED', memoId: 'm7', linkedAt: '2026-11-28T09:00:00Z', readable: false, status: null },
        ],
      }),
    )
    renderRoute('/actions/a1')
    const memos = await screen.findByRole('region', { name: 'Follow-up memos' }, { timeout: 5000 })
    expect(within(memos).getByText('Approved')).toBeInTheDocument()
    expect(within(memos).getByText('Raised from this action')).toBeInTheDocument()
    expect(within(memos).getByText('A follow-up memo (you do not have access)')).toBeInTheDocument()
    expect(within(memos).queryByText(/MEMO\/2026\/0411/)).toBeNull()
    expect(
      within(memos).getByText('The follow-up memo was approved. You can now mark the action as done.'),
    ).toBeInTheDocument()
  })
})

describe('in Arabic', () => {
  it('reads the action page right to left, with Arabic words', async () => {
    actionPage()
    const { container } = renderRoute('/actions/a1', { locale: 'ar' })
    expect(await screen.findByRole('button', { name: 'انشر تحديثًا' }, { timeout: 5000 })).toBeInTheDocument()
    expect(document.documentElement.dir).toBe('rtl')
    expect(screen.getAllByText('متأخر')[0]?.closest('[data-rag]')).toHaveAttribute('data-rag', 'RED')
    expect(screen.getByRole('region', { name: 'السجل الزمني' })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('reads My actions in Arabic', async () => {
    server.use(myActionsHandler(mine))
    const { container } = renderRoute('/inbox?tab=actions', { locale: 'ar' })
    expect(await screen.findByRole('button', { name: /^للإنجاز/ }, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'مفتاح الألوان' })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })
})

describe('refusals in words (design §3)', () => {
  const intl = createIntl({ locale: 'en', messages: {} })
  const terms = { forum: 'committee', Forum: 'Committee' }
  it.each([
    [{ status: 403, reason: 'RECUSED' }, 'You declared an interest in this item, so another officer does this.'],
    [{ status: 409, reason: 'PENDING_REQUEST' }, 'A new date was asked for already. Wait for the answer.'],
    [{ status: 409, reason: 'TOO_LATE' }, 'It is too late for this. Raise a new action instead.'],
    [{ status: 403, reason: 'NOT_VERIFIER' }, 'You are not the one to verify this action.'],
    [{ status: 409, reason: 'CLAIMED' }, 'Another verifier is checking this action already.'],
    [{ status: 403, reason: 'NOT_INVOLVED' }, 'Only the action’s owner and the people involved raise its memos.'],
    [{ status: 403, reason: 'ASK_FOR_IT' }, 'This committee agrees new dates: ask for a new date instead.'],
    [
      { status: 409, reason: 'OWNER_CHANGES' },
      'This committee lets the owner change the date: change it instead of asking.',
    ],
    [{ status: 412 }, 'Someone changed this action meanwhile. It has been reloaded: check it and try again.'],
    [{ status: 404 }, 'This action is no longer there for you.'],
    [{ status: 429 }, 'Too many tries. Wait a moment and try again.'],
  ])('%o', (problem, words) => {
    expect(actionRefusal(intl, terms, problem)).toBe(words)
  })

  it('words the field problems, `fileIds/2` counting as `fileIds`', () => {
    const problem = {
      status: 422,
      errors: [
        { field: 'newDate', code: 'SAME_DATE', message: 'x' },
        { field: 'fileIds/2', code: 'unknown', message: 'x' },
        { field: 'memoId', code: 'NOT_READABLE', message: 'x' },
        { field: 'classificationId', code: 'LOWER_THAN_ACTION', message: 'x' },
        { field: 'text', code: 'LENGTH', message: 'x' },
      ],
    }
    expect(fieldProblems(intl, terms, problem)).toEqual({
      newDate: 'The action is due on that date already.',
      fileIds: 'A file is not one you attached to this action. Remove it and attach it again.',
      memoId: 'Choose a memo you can open.',
      classificationId: 'This level is lower than the action’s. Tick the confirmation to use it.',
      text: 'This is too long: at most 4,000 characters.',
    })
  })
})
