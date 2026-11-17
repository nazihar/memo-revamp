import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type {
  CopiesRequest,
  CopiesView,
  DistributionListRequest,
  DistributionListView,
  MemoTypeTemplateView,
  MemoView,
  ReceiptsView,
  ShareRequest,
  ShareView,
} from '@/api/generated/types.gen'
import { ToastProvider } from '@/components/ui/toast'
import { TemplateBlock } from '@/features/memo-types/TemplateBlock'
import { API_ORIGIN, server } from '@/test/msw'
import { renderWithProviders as renderBare } from '@/test/render'
import { DistributionListsPage } from './DistributionListsPage'
import { MemoAccess } from './MemoAccess'

const renderWithProviders = (ui: React.ReactElement) => renderBare(<ToastProvider>{ui}</ToastProvider>)

const memo = {
  id: 'm1',
  version: 7,
  status: 'DRAFT',
  readingAs: 'INITIATOR',
  route: [{ ordinal: 1, approver: { id: 'u2', name: 'Demo Head of Credit' } }],
  allowedActions: ['EDIT_COPIES'],
} as unknown as MemoView

const copies: CopiesView = {
  canEdit: true,
  canAdd: false,
  memoVersion: 7,
  copies: [
    {
      id: 'c1',
      recipient: { kind: 'LIST', id: 'l1', name: 'Credit committee' },
      sendWhen: 'AFTER_APPROVAL',
      sent: 0,
      read: 0,
      addedAt: '2026-11-15T08:00:00Z',
      addedBy: { id: 'u1', name: 'Demo User' },
    },
    {
      id: 'c2',
      recipient: { kind: 'USER', id: 'u5', name: 'Demo Auditor' },
      sendWhen: 'AT_SUBMIT',
      sent: 1,
      read: 1,
      sentAt: '2026-11-15T09:00:00Z',
      addedAt: '2026-11-15T08:00:00Z',
      addedBy: { id: 'u1', name: 'Demo User' },
    },
  ],
}

const share: ShareView = {
  id: 's1',
  person: { id: 'u7', name: 'Demo Legal Counsel' },
  grantedBy: { id: 'u1', name: 'Demo User' },
  reason: 'Review of the guarantee',
  grantedAt: '2026-11-15T08:00:00Z',
  canRevoke: true,
}

function accessHandlers(over: { copies?: CopiesView; shares?: ShareView[] } = {}) {
  return [
    http.get(`${API_ORIGIN}/api/v1/memos/m1/copies`, () => HttpResponse.json(over.copies ?? copies)),
    http.get(`${API_ORIGIN}/api/v1/memos/m1/shares`, () => HttpResponse.json(over.shares ?? [share])),
    http.get(`${API_ORIGIN}/api/v1/distribution-lists`, () =>
      HttpResponse.json<DistributionListView[]>([{ id: 'l2', name: 'Risk team', version: 0, members: [] }]),
    ),
  ]
}

describe('people with access', () => {
  it('shows approvers, copies with how many read, and shares with their reason; revoking asks the server', async () => {
    const user = userEvent.setup()
    let revoked = false
    server.use(
      ...accessHandlers(),
      http.delete(`${API_ORIGIN}/api/v1/memos/m1/shares/s1`, () => {
        revoked = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderWithProviders(<MemoAccess memo={memo} onProblem={() => undefined} />)
    const box = screen.getByRole('region', { name: 'People with access' })
    expect(within(box).getByText('Demo Head of Credit')).toBeInTheDocument()
    expect(await within(box).findByRole('button', { name: '1 person · 1 read' })).toBeInTheDocument()
    expect(within(box).getByText('Demo Legal Counsel')).toBeInTheDocument()
    expect(within(box).getByText('by Demo User: “Review of the guarantee”')).toBeInTheDocument()

    await user.click(within(box).getByRole('button', { name: 'Revoke' }))
    await vi.waitFor(() => {
      expect(revoked).toBe(true)
    })
  })

  it('tells a copy recipient they read for information, and offers them neither sharing nor copies', async () => {
    server.use(...accessHandlers({ copies: { ...copies, canEdit: false }, shares: [] }))
    renderWithProviders(<MemoAccess memo={{ ...memo, readingAs: 'COPY' }} onProblem={() => undefined} />)
    expect(screen.getByRole('note')).toHaveTextContent('You received a copy of this memo for information')
    expect(screen.queryByRole('button', { name: 'Share' })).not.toBeInTheDocument()
    expect(await screen.findByRole('button', { name: '1 person · 1 read' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Copy to…' })).not.toBeInTheDocument()
  })

  it('shares with a reason', async () => {
    const user = userEvent.setup()
    let body: ShareRequest | undefined
    server.use(
      ...accessHandlers({ shares: [] }),
      http.get(`${API_ORIGIN}/api/v1/directory/search`, () =>
        HttpResponse.json({
          users: [{ person: { id: 'u7', displayName: 'Demo Legal Counsel', email: 'legal@bank.example' } }],
          positions: [],
          units: [],
        }),
      ),
      http.post(`${API_ORIGIN}/api/v1/memos/m1/shares`, async ({ request }) => {
        body = (await request.json()) as ShareRequest
        return HttpResponse.json({ ...share }, { status: 201 })
      }),
    )
    renderWithProviders(<MemoAccess memo={memo} onProblem={() => undefined} />)
    await user.click(screen.getByRole('button', { name: 'Share' }))
    const dialog = await screen.findByRole('dialog', { name: 'Share this memo' })
    const share_ = within(dialog).getByRole('button', { name: 'Share' })
    expect(share_).toBeDisabled()
    await user.type(within(dialog).getByRole('combobox', { name: /Person/ }), 'Demo Le')
    await user.click(await within(dialog).findByRole('option', { name: /Demo Legal Counsel/ }))
    await user.type(within(dialog).getByLabelText(/Reason/), 'Review of the guarantee')
    await user.click(share_)
    await vi.waitFor(() => {
      expect(body).toEqual({ userId: 'u7', reason: 'Review of the guarantee', expiresAt: null })
    })
  })

  it('edits copies before submit: unsent recipients with when to send, saved with the memo version', async () => {
    const user = userEvent.setup()
    let sent: { body: CopiesRequest; ifMatch: string | null } | undefined
    server.use(
      ...accessHandlers(),
      http.put(`${API_ORIGIN}/api/v1/memos/m1/copies`, async ({ request }) => {
        sent = { body: (await request.json()) as CopiesRequest, ifMatch: request.headers.get('If-Match') }
        return HttpResponse.json(copies)
      }),
    )
    renderWithProviders(<MemoAccess memo={memo} onProblem={() => undefined} />)
    await user.click(await screen.findByRole('button', { name: 'Copy to…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Copy to' })
    // The copy already sent is listed but cannot change.
    expect(within(dialog).getByText('Demo Auditor')).toBeInTheDocument()
    expect(within(dialog).queryByRole('combobox', { name: 'When to send to Demo Auditor' })).not.toBeInTheDocument()
    await user.selectOptions(within(dialog).getByRole('combobox', { name: /distribution list/ }), 'Risk team')
    await user.selectOptions(
      within(dialog).getByRole('combobox', { name: 'When to send to Credit committee' }),
      'At submit',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    await vi.waitFor(() => {
      expect(sent).toEqual({
        ifMatch: '"7"',
        body: {
          copies: [
            { kind: 'LIST', id: 'l1', sendWhen: 'AT_SUBMIT' },
            { kind: 'LIST', id: 'l2', sendWhen: 'AFTER_APPROVAL' },
          ],
        },
      })
    })
  })

  it('lists receipts: who opened which version, and who was not sent the copy', async () => {
    const user = userEvent.setup()
    server.use(
      ...accessHandlers(),
      http.get(`${API_ORIGIN}/api/v1/memos/m1/receipts`, () =>
        HttpResponse.json<ReceiptsView>({
          copies: [
            {
              person: { id: 'u5', name: 'Demo Auditor' },
              openedAt: '2026-11-15T09:30:00Z',
              documentVersion: 2,
              outcome: 'SENT',
            },
            { person: { id: 'u8', name: 'Demo Teller' }, via: 'Credit committee', outcome: 'NOT_CLEARED' },
          ],
          approvers: [{ person: { id: 'u2', name: 'Demo Head of Credit' } }],
        }),
      ),
    )
    renderWithProviders(<MemoAccess memo={memo} onProblem={() => undefined} />)
    await user.click(await screen.findByRole('button', { name: '1 person · 1 read' }))
    const dialog = await screen.findByRole('dialog', { name: 'Copies and receipts' })
    expect(await within(dialog).findByText(/\(v2\)/)).toBeInTheDocument()
    expect(within(dialog).getByText('Not sent: not cleared')).toBeInTheDocument()
    expect(within(dialog).getByText('Not opened yet')).toBeInTheDocument()
  })
})

describe('memo-type template', () => {
  const info: MemoTypeTemplateView = {
    owner: 'tv1',
    template: {
      fileId: 'f1',
      versionId: 'fv1',
      name: 'letterhead.docx',
      sizeBytes: 20480,
      uploadedAt: '2026-11-15T08:00:00Z',
      tags: ['subject', 'amout'],
      problems: [
        {
          code: 'UNKNOWN_TAG',
          part: 'Body',
          paragraph: 4,
          excerpt: 'Amount: {{amout}}',
          tag: 'amout',
          suggestion: 'amount',
        },
      ],
      previewed: false,
    },
    available: [{ tag: 'subject', label: 'Subject', kind: 'VALUE' }],
  }

  it('lists what the check found, with where and a suggestion, and holds the preview back until fixed', async () => {
    server.use(http.get(`${API_ORIGIN}/api/v1/admin/memo-types/t1/versions/2/template`, () => HttpResponse.json(info)))
    renderWithProviders(<TemplateBlock typeId="t1" number={2} revision={3} draft />)
    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText(/Unknown tag amout/)).toBeInTheDocument()
    expect(within(alert).getByText(/Body, paragraph 4: “Amount: \{\{amout\}\}”/)).toBeInTheDocument()
    expect(within(alert).getByText('Did you mean amount?')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Preview as PDF' })).toBeDisabled()
    expect(screen.getByText('Preview it before submitting the version')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy {{subject}}' })).toBeInTheDocument()
  })

  it('removes the template with the version revision', async () => {
    const user = userEvent.setup()
    let ifMatch: string | null = null
    server.use(
      http.get(`${API_ORIGIN}/api/v1/admin/memo-types/t1/versions/2/template`, () =>
        HttpResponse.json({ ...info, template: { ...info.template, problems: [] } }),
      ),
      http.delete(`${API_ORIGIN}/api/v1/admin/memo-types/t1/versions/2/template`, ({ request }) => {
        ifMatch = request.headers.get('If-Match')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderWithProviders(<TemplateBlock typeId="t1" number={2} revision={3} draft />)
    await user.click(await screen.findByRole('button', { name: 'Remove' }))
    await vi.waitFor(() => {
      expect(ifMatch).toBe('"3"')
    })
  })

  it('is read-only outside a draft', async () => {
    server.use(http.get(`${API_ORIGIN}/api/v1/admin/memo-types/t1/versions/2/template`, () => HttpResponse.json(info)))
    renderWithProviders(<TemplateBlock typeId="t1" number={2} revision={3} draft={false} />)
    expect(await screen.findByText(/letterhead\.docx/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument()
    expect(screen.queryByText('Replace the template')).not.toBeInTheDocument()
  })
})

describe('distribution lists', () => {
  it('creates a list of people and positions', async () => {
    const user = userEvent.setup()
    let body: DistributionListRequest | undefined
    server.use(
      http.get(`${API_ORIGIN}/api/v1/distribution-lists`, () => HttpResponse.json([])),
      http.get(`${API_ORIGIN}/api/v1/directory/search`, () =>
        HttpResponse.json({
          users: [{ person: { id: 'u5', displayName: 'Demo Auditor', email: 'audit@bank.example' } }],
          positions: [],
          units: [],
        }),
      ),
      http.post(`${API_ORIGIN}/api/v1/distribution-lists`, async ({ request }) => {
        body = (await request.json()) as DistributionListRequest
        return HttpResponse.json({ id: 'l9', name: 'Audit', version: 0, members: [] }, { status: 201 })
      }),
    )
    renderWithProviders(<DistributionListsPage />)
    expect(await screen.findByText('No distribution lists yet.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add list' }))
    const dialog = await screen.findByRole('dialog', { name: 'Add list' })
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('A name and at least one member are required.')
    await user.type(within(dialog).getByLabelText(/^Name/), 'Audit')
    await user.type(within(dialog).getByRole('combobox', { name: 'Member' }), 'Demo Au')
    await user.click(await within(dialog).findByRole('option', { name: /Demo Auditor/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Add member' }))
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    await vi.waitFor(() => {
      expect(body).toEqual({ name: 'Audit', members: [{ kind: 'USER', id: 'u5' }] })
    })
  })
})
