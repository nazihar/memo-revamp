import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type {
  EditSessionView,
  HistoryRound,
  InboxPage,
  MemoView,
  OpenEditorRequest,
  OpenEditorResponse,
} from '@/api/generated/types.gen'
import { accessHandler, API_ORIGIN, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const memo: MemoView = {
  id: 'm1',
  reference: 'M-2026-00042',
  subject: 'Budget transfer to the treasury',
  type: { id: 't1', name: 'General memo' },
  classification: { id: 'c1', rank: 0, name: 'Public' },
  status: 'IN_REVIEW',
  initiator: { id: 'u1', name: 'Alice Author' },
  document: {
    fileId: 'f1',
    fileVersion: 1,
    versionId: 'v1',
    number: 2,
    name: 'budget.docx',
    sizeBytes: 1200,
    sha256: 'a'.repeat(64),
    uploadedAt: '2026-11-10T08:00:00Z',
  },
  route: [
    { ordinal: 1, approver: { id: 'u2', name: 'Bob Approver' } },
    { ordinal: 2, approver: { id: 'u3', name: 'Carol Approver' } },
  ],
  round: 2,
  holders: [{ id: 'u2', name: 'Bob Approver' }],
  myTask: { id: 'k1', role: 'DECIDE', waiting: false, shared: false },
  sendBackTargets: [],
  allowedActions: ['APPROVE', 'RETURN', 'REJECT'],
  createdAt: '2026-11-09T08:00:00Z',
  updatedAt: '2026-11-10T08:00:00Z',
  version: 4,
}

const history: HistoryRound[] = [
  {
    round: 2,
    current: true,
    actions: [
      {
        seq: 3,
        kind: 'RESUBMIT',
        actor: { id: 'u1', name: 'Alice Author' },
        remark: 'Annex added',
        documentNumber: 2,
        sha256: 'a'.repeat(64),
        occurredAt: '2026-11-10T08:00:00Z',
        to: [],
        details: {},
      },
    ],
  },
  {
    round: 1,
    current: false,
    actions: [
      {
        seq: 2,
        kind: 'RETURN',
        actor: { id: 'u2', name: 'Bob Approver' },
        remark: 'Annex B is missing',
        documentNumber: 1,
        sha256: 'b'.repeat(64),
        occurredAt: '2026-11-09T09:00:00Z',
        to: [],
        details: {},
      },
    ],
  },
]

const inbox: InboxPage = {
  items: [
    {
      taskId: 'k1',
      memo: { id: 'm1', reference: 'M-2026-00042', subject: 'Budget transfer to the treasury', restricted: false },
      round: 2,
      assignedAt: '2026-11-10T08:00:00Z',
      kind: 'DECIDE',
      stepName: 'Finance review',
    },
    {
      taskId: 'k2',
      memo: { id: 'm2', reference: null, subject: null, restricted: true },
      round: 1,
      assignedAt: '2026-11-09T08:00:00Z',
      kind: 'OPINION',
    },
  ],
  nextCursor: null,
}

function memoHandlers(view: MemoView = memo) {
  return [
    http.get(`${API_ORIGIN}/api/v1/memos/m1`, () => HttpResponse.json<MemoView>(view)),
    http.get(`${API_ORIGIN}/api/v1/memos/m1/history`, () => HttpResponse.json<HistoryRound[]>(history)),
  ]
}

describe('inbox', () => {
  it('lists my tasks newest first and shows a memo I may no longer read as restricted', async () => {
    server.use(
      sessionHandler(signedInSession()),
      accessHandler([]),
      http.get(`${API_ORIGIN}/api/v1/inbox/tasks`, () => HttpResponse.json<InboxPage>(inbox)),
    )
    const { container } = renderRoute('/inbox')

    const link = await screen.findByRole('link', { name: /Budget transfer to the treasury/ }, { timeout: 5000 })
    expect(link).toHaveAttribute('href', '/memos/m1')
    expect(screen.getByText('Restricted memo')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'My tasks' })).toHaveAttribute('aria-selected', 'true')
    await expectNoA11yViolations(container)
  })
})

describe('memo view', () => {
  it('shows the document, the route and the history by round, with earlier rounds marked', async () => {
    server.use(sessionHandler(signedInSession()), accessHandler([]), ...memoHandlers())
    const { container } = renderRoute('/memos/m1')

    expect(
      await screen.findByRole('heading', { name: 'Budget transfer to the treasury' }, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.getByText('Version 2 · budget.docx')).toBeInTheDocument()
    expect(await screen.findByText('Earlier round: its approvals no longer count.')).toBeInTheDocument()
    expect(screen.getByText('Annex B is missing')).toBeInTheDocument()
    const actions = screen.getByRole('toolbar', { name: 'Actions' })
    expect(
      within(actions)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Approve', 'Return', 'Reject'])
    await expectNoA11yViolations(container)
  })

  it('needs a remark to return, sends one key per dialog, and shows the memo the server answers with', async () => {
    const user = userEvent.setup()
    const keys: string[] = []
    server.use(
      sessionHandler(signedInSession()),
      accessHandler([]),
      ...memoHandlers(),
      http.get(`${API_ORIGIN}/api/v1/inbox/tasks`, () => HttpResponse.json<InboxPage>({ items: [], nextCursor: null })),
      http.post(`${API_ORIGIN}/api/v1/tasks/k1/return`, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key') ?? '')
        return HttpResponse.json<MemoView>({
          ...memo,
          status: 'RETURNED',
          myTask: undefined,
          allowedActions: [],
          version: 5,
        })
      }),
    )
    renderRoute('/memos/m1')

    await user.click(await screen.findByRole('button', { name: 'Return' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: /^Return .?M-2026-00042.? to its author[?]$/ })
    await user.click(within(dialog).getByRole('button', { name: 'Return' }))
    expect(within(dialog).getByText('A remark is required.')).toBeInTheDocument()
    expect(keys).toEqual([])

    await user.type(within(dialog).getByRole('textbox', { name: /Remark/ }), 'Please add annex B')
    await user.click(within(dialog).getByRole('button', { name: 'Return' }))
    await screen.findByText('My tasks', {}, { timeout: 5000 })
    expect(keys).toHaveLength(1)
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
  })
})

describe('document editor', () => {
  const script = '/office/web-apps/apps/api/documents/api.js'
  const editors: object[] = []

  /** The document server's script is not loaded in tests: stand in for its global, as if it had loaded. */
  function fakeEditorScript() {
    const tag = document.createElement('script')
    tag.dataset.editor = script
    document.head.append(tag)
    window.DocsAPI = {
      DocEditor: class {
        constructor(_element: string, config: object) {
          editors.push(config)
        }
        destroyEditor() {
          // Nothing to tear down in the stand-in.
        }
      },
    }
  }

  function editorHandlers(state: EditSessionView['state']) {
    return [
      http.post(`${API_ORIGIN}/api/v1/files/f1/edit-sessions`, async ({ request }) => {
        const body = (await request.json()) as OpenEditorRequest
        return HttpResponse.json<OpenEditorResponse>({
          sessionId: 's1',
          scriptUrl: script,
          config: { document: { key: 'mKEY' }, editorConfig: { mode: body.mode === 'EDIT' ? 'edit' : 'view' } },
        })
      }),
      http.get(`${API_ORIGIN}/api/v1/edit-sessions/s1`, () =>
        HttpResponse.json<EditSessionView>({ id: 's1', state, reason: state === 'FAILED' ? 'LOCKED' : null }),
      ),
    ]
  }

  it('offers editing where the memo allows it, and reading otherwise', async () => {
    server.use(
      sessionHandler(signedInSession()),
      accessHandler([]),
      ...memoHandlers({ ...memo, allowedActions: ['APPROVE', 'VIEW_DOCUMENT'] }),
    )
    renderRoute('/memos/m1')
    const open = await screen.findByRole('link', { name: 'Open in the browser' }, { timeout: 5000 })
    expect(open).toHaveAttribute('href', '/memos/m1/document?mode=view')
    expect(screen.queryByRole('link', { name: 'Edit in the browser' })).not.toBeInTheDocument()
  })

  it('opens the editor with the signed configuration and goes back once the save has landed', async () => {
    const user = userEvent.setup()
    fakeEditorScript()
    server.use(
      sessionHandler(signedInSession()),
      accessHandler([]),
      ...memoHandlers({
        ...memo,
        status: 'DRAFT',
        allowedActions: ['UPLOAD_DOCUMENT', 'EDIT_DOCUMENT', 'VIEW_DOCUMENT'],
      }),
      ...editorHandlers('SAVED'),
    )
    const { container } = renderRoute('/memos/m1/document?mode=edit')

    expect(await screen.findByRole('heading', { name: /Editing/ }, { timeout: 5000 })).toHaveTextContent('M-2026-00042')
    await vi.waitFor(() => {
      expect(editors.at(-1)).toMatchObject({ editorConfig: { mode: 'edit' } })
    })
    await expectNoA11yViolations(container)
    await user.click(screen.getByRole('button', { name: 'Back to the memo' }))
    expect(
      await screen.findByRole('heading', { name: 'Budget transfer to the treasury' }, { timeout: 5000 }),
    ).toBeInTheDocument()
  })

  it('says so when the save could not land', async () => {
    const user = userEvent.setup()
    fakeEditorScript()
    server.use(
      sessionHandler(signedInSession()),
      accessHandler([]),
      ...memoHandlers({ ...memo, status: 'DRAFT', allowedActions: ['EDIT_DOCUMENT'] }),
      ...editorHandlers('FAILED'),
    )
    renderRoute('/memos/m1/document?mode=edit')

    await screen.findByRole('heading', { name: /Editing/ }, { timeout: 5000 })
    await vi.waitFor(() => {
      expect(editors.length).toBeGreaterThan(0)
    })
    await user.click(screen.getByRole('button', { name: 'Back to the memo' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Your changes could not be saved')
  })
})

describe('E03 task actions', () => {
  const review: MemoView = {
    ...memo,
    myTask: { id: 'k1', role: 'DECIDE', waiting: false, stepName: 'Finance review', shared: false },
    allowedActions: ['RECOMMEND', 'RETURN', 'SEND_BACK', 'FORWARD'],
    sendBackTargets: [{ stepId: 's1', name: 'Department review', holder: { id: 'u2', name: 'Bob Approver' } }],
  }

  it('shows the step and offers the actions of a review step', async () => {
    server.use(sessionHandler(signedInSession()), accessHandler([]), ...memoHandlers(review))
    const { container } = renderRoute('/memos/m1')

    const actions = await screen.findByRole('toolbar', { name: 'Actions' }, { timeout: 5000 })
    expect(
      within(actions)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Recommend', 'Return', 'Send back', 'Forward'])
    expect(screen.getByText('Step: Finance review')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('sends the memo back to the chosen step with a remark, and asks whom to forward to', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    server.use(
      sessionHandler(signedInSession()),
      accessHandler([]),
      ...memoHandlers(review),
      http.get(`${API_ORIGIN}/api/v1/inbox/tasks`, () => HttpResponse.json<InboxPage>({ items: [], nextCursor: null })),
      http.post(`${API_ORIGIN}/api/v1/tasks/k1/send-back`, async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json<MemoView>({ ...memo, myTask: undefined, allowedActions: [] })
      }),
    )
    renderRoute('/memos/m1')

    await user.click(await screen.findByRole('button', { name: 'Forward' }, { timeout: 5000 }))
    const forward = await screen.findByRole('dialog', { name: /^Forward .?M-2026-00042.?[?]$/ })
    await user.click(within(forward).getByRole('button', { name: 'Forward' }))
    expect(within(forward).getByText('Choose a person.')).toBeInTheDocument()
    expect(within(forward).getByText('A remark is required.')).toBeInTheDocument()
    await user.click(within(forward).getByRole('button', { name: 'Cancel' }))

    await user.click(screen.getByRole('button', { name: 'Send back' }))
    const dialog = await screen.findByRole('dialog', { name: /^Send .?M-2026-00042.? back[?]$/ })
    expect(within(dialog).getByRole('combobox', { name: /Send back to/ })).toHaveDisplayValue(
      'Department review (Bob Approver)',
    )
    await user.type(within(dialog).getByRole('textbox', { name: /Remark/ }), 'Recheck the figures')
    await user.click(within(dialog).getByRole('button', { name: 'Send back' }))
    await screen.findByText('My tasks', {}, { timeout: 5000 })
    expect(bodies).toEqual([{ targetStepId: 's1', remark: 'Recheck the figures' }])
  })
})
