import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type { MemoView, RecordVersionView, RenditionView } from '@/api/generated/types.gen'
import { ToastProvider } from '@/components/ui/toast'
import { StepUpContext } from '@/features/auth/step-up-context'
import { accessHandler, API_ORIGIN, server } from '@/test/msw'
import { renderWithProviders as renderBare } from '@/test/render'
import { FinalRecord } from './FinalRecord'

const memo = { id: 'm1', rendition: 'READY' } as unknown as MemoView

const v1: RecordVersionView = {
  version: 1,
  state: 'READY',
  trigger: 'APPROVED',
  createdAt: '2026-11-15T14:20:00Z',
  finishedAt: '2026-11-15T14:22:00Z',
  pages: 62,
  sizeBytes: 812_000,
  classification: 'Internal',
  readable: true,
  fileId: 'f1',
  versionId: 'fv1',
}

const audit = {
  sha256: '3f9a1c07d2e84b6a9c0e5f7a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e',
  compliant: true,
  profile: '2u',
  validator: 'veraPDF 1.30.3',
  failedRules: {},
  rasterisedPages: 2,
  placeholders: 1,
  renderer: 'PDFBox 3.0.8 with Noto Sans 2.015',
}

function render(ui: React.ReactElement) {
  const request = vi.fn(() => Promise.resolve(true))
  renderBare(
    <ToastProvider>
      <StepUpContext.Provider value={{ request }}>{ui}</StepUpContext.Provider>
    </ToastProvider>,
  )
  return request
}

function record(view: RenditionView) {
  server.use(http.get(`${API_ORIGIN}/api/v1/memos/m1/rendition`, () => HttpResponse.json(view)))
}

describe('final record', () => {
  it('offers the ready record for download, and says when it is being prepared or not cleared', async () => {
    record({ state: 'READY', current: v1, versions: [v1] })
    render(<FinalRecord memo={memo} onProblem={() => undefined} />)
    const box = await screen.findByRole('region', { name: 'Final record' })
    expect(await within(box).findByText(/Version 1 · 62 pages · PDF\/A/)).toBeInTheDocument()
    expect(within(box).getByRole('link', { name: 'Download' })).toHaveAttribute(
      'href',
      '/api/v1/files/f1/versions/fv1/content',
    )
    expect(within(box).queryByText('SHA-256')).not.toBeInTheDocument()
    expect(within(box).queryByRole('button', { name: 'Make a new version' })).not.toBeInTheDocument()
  })

  it('tells a reader not cleared to the record that it exists, without a download', async () => {
    const hidden = { ...v1, readable: false, fileId: undefined, versionId: undefined, classification: 'Secret' }
    record({ state: 'READY', current: hidden, versions: [hidden] })
    render(<FinalRecord memo={memo} onProblem={() => undefined} />)
    expect(await screen.findByText(/Classified Secret\. You are not cleared to open it\./)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Download' })).not.toBeInTheDocument()
  })

  it('shows auditors the hash, the validation and what was flagged', async () => {
    record({ state: 'READY', current: { ...v1, audit }, versions: [{ ...v1, audit }] })
    render(<FinalRecord memo={memo} onProblem={() => undefined} />)
    expect(await screen.findByText(audit.sha256)).toBeInTheDocument()
    expect(screen.getByText('Valid PDF/A')).toBeInTheDocument()
    expect(screen.getByText('veraPDF 1.30.3, profile 2u')).toBeInTheDocument()
    expect(screen.getByText('2 pages without text · 1 attachment kept as an original')).toBeInTheDocument()
  })

  it('says a pending record is being prepared', async () => {
    const pending = { ...v1, state: 'PENDING' as const, readable: false, fileId: undefined, versionId: undefined }
    record({ state: 'PENDING', versions: [pending] })
    render(<FinalRecord memo={memo} onProblem={() => undefined} />)
    const box = await screen.findByRole('region', { name: 'Final record' })
    expect(within(box).getByRole('status')).toHaveTextContent('The final record is being prepared.')
  })

  it('lets a keeper make a new version with a reason, keeping the earlier one', async () => {
    const user = userEvent.setup()
    server.use(accessHandler(['memo.rendition.regenerate']))
    const v2 = {
      ...v1,
      version: 2,
      trigger: 'REGENERATE' as const,
      fileId: 'f1',
      versionId: 'fv2',
      reason: 'Checking after a converter upgrade.',
    }
    const earlier = { ...v1, reason: undefined }
    record({ state: 'READY', current: v2, versions: [v2, earlier] })
    let body: unknown
    server.use(
      http.post(`${API_ORIGIN}/api/v1/memos/m1/rendition/regenerate`, async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ state: 'PENDING', versions: [] }, { status: 202 })
      }),
    )
    render(<FinalRecord memo={memo} onProblem={() => undefined} />)
    expect(await screen.findByRole('link', { name: 'Download version 1' })).toHaveAttribute(
      'href',
      '/api/v1/files/f1/versions/fv1/content',
    )
    // Version 1 is listed with the reason it was replaced (version 2's).
    const box = await screen.findByRole('region', { name: 'Final record' })
    expect(within(box).getByText(/^v1 · .* · Checking after a converter upgrade\.$/)).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Make a new version' }))
    const dialog = await screen.findByRole('dialog', { name: 'Make a new version of the final record' })
    const make = within(dialog).getByRole('button', { name: 'Make version 3' })
    expect(make).toBeDisabled()
    await user.type(within(dialog).getByLabelText(/Reason/), 'The converter lost a table.')
    await user.click(make)
    await vi.waitFor(() => {
      expect(body).toEqual({ reason: 'The converter lost a table.' })
    })
  })
})
