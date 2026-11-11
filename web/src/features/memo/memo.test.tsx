import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { HistoryRound, InboxPage, MemoView } from '@/api/generated/types.gen'
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
  myTask: { id: 'k1' },
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
    },
    {
      taskId: 'k2',
      memo: { id: 'm2', reference: null, subject: null, restricted: true },
      round: 1,
      assignedAt: '2026-11-09T08:00:00Z',
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
