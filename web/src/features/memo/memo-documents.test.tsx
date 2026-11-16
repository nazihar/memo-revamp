import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type {
  AttachmentView,
  ClassificationLevel,
  HistoryRound,
  MemoView,
  VersionComparison,
} from '@/api/generated/types.gen'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const memo: MemoView = {
  origin: 'NATIVE',
  id: 'm1',
  reference: null,
  numberedWhen: 'ON_SUBMIT',
  subject: 'Branch refurbishment',
  type: { id: 't1', name: 'General memo' },
  classification: { id: 'c0', rank: 0, name: 'Internal' },
  status: 'DRAFT',
  initiator: { id: 'u1', name: 'Alice Author' },
  document: {
    fileId: 'f1',
    fileVersion: 2,
    versionId: 'v2',
    number: 2,
    name: 'refurbishment.docx',
    sizeBytes: 1200,
    sha256: 'a'.repeat(64),
    uploadedAt: '2026-11-10T08:00:00Z',
  },
  route: [],
  round: 0,
  holders: [],
  sendBackTargets: [],
  allowedActions: ['EDIT', 'UPLOAD_DOCUMENT', 'EDIT_ROUTE', 'SUBMIT', 'DISCARD'],
  createdAt: '2026-11-09T08:00:00Z',
  updatedAt: '2026-11-10T08:00:00Z',
  version: 3,
  interests: [],
  details: [],
  staleDetails: [],
  links: [],
  linkedFrom: [],
  canLink: true,
  coauthors: [{ id: 'u2', name: 'Bob Builder' }],
  rendition: 'NONE',
}

const attachments: AttachmentView[] = [
  {
    id: 'a1',
    restricted: false,
    position: 0,
    fileId: 'fa1',
    title: 'quotation.pdf',
    mediaType: 'application/pdf',
    sizeBytes: 250_000,
    versionId: 'va1',
    versionNumber: 1,
    version: 0,
  },
  { id: 'a2', restricted: true, position: 1 },
]

function handlers(view: MemoView = memo) {
  return [
    sessionHandler(signedInSession()),
    accessHandler(['memo.create']),
    csrfHandler(),
    http.get(`${API_ORIGIN}/api/v1/memos/m1`, () => HttpResponse.json<MemoView>(view)),
    http.get(`${API_ORIGIN}/api/v1/memos/m1/history`, () => HttpResponse.json<HistoryRound[]>([])),
    http.get(`${API_ORIGIN}/api/v1/memos/m1/attachments`, () => HttpResponse.json<AttachmentView[]>(attachments)),
    http.get(`${API_ORIGIN}/api/v1/authz/classification-levels`, () =>
      HttpResponse.json<ClassificationLevel[]>([
        { id: 'c0', name: 'Internal', rank: 0 } as ClassificationLevel,
        { id: 'c1', name: 'Confidential', rank: 1 } as ClassificationLevel,
      ]),
    ),
  ]
}

describe('memo attachments, comparison and co-authors (Phase 3 E02 T7)', () => {
  it('lists attachments, a restricted one without details, and reclassifies one behind its version', async () => {
    const user = userEvent.setup()
    const patches: { body: unknown; ifMatch: string | null }[] = []
    server.use(...handlers())
    server.use(
      // Cleared for Confidential, so it may classify an attachment there.
      http.get(`${API_ORIGIN}/api/v1/me/access`, () =>
        HttpResponse.json({
          permissions: [{ key: 'memo.create', tenantWide: true, scopeUnitIds: [] }],
          clearance: { rank: 1, name: 'Confidential' },
        }),
      ),
      http.patch(`${API_ORIGIN}/api/v1/memos/m1/attachments/a1`, async ({ request }) => {
        patches.push({ body: await request.json(), ifMatch: request.headers.get('If-Match') })
        return HttpResponse.json<AttachmentView>({ ...attachments[0]!, version: 1 })
      }),
    )
    const { container } = renderRoute('/memos/m1')

    const section = (await screen.findByRole('heading', { name: 'Attachments' }, { timeout: 5000 })).closest('section')!
    expect(await within(section).findByText('quotation.pdf')).toBeInTheDocument()
    expect(within(section).getByText('Restricted attachment')).toBeInTheDocument()
    await user.selectOptions(
      within(section).getByRole('combobox', { name: 'Classification of quotation.pdf' }),
      'Confidential',
    )
    await waitFor(() => {
      expect(patches).toEqual([{ body: { classificationId: 'c1' }, ifMatch: '"0"' }])
    })
    await expectNoA11yViolations(container)
  }, 20_000)

  it('compares two versions side by side, with removed and added words marked', async () => {
    const user = userEvent.setup()
    server.use(...handlers())
    server.use(
      http.get(`${API_ORIGIN}/api/v1/memos/m1/files/f1/compare`, () =>
        HttpResponse.json<VersionComparison>({
          from: {
            number: 1,
            versionId: 'v1',
            name: 'refurbishment.docx',
            sha256: 'b'.repeat(64),
            uploadedAt: '2026-11-09T08:00:00Z',
            uploadedBy: { id: 'u1', name: 'Alice Author' },
            state: 'DONE',
            truncated: false,
            text: 'Budget 2 million\nThree branches',
          },
          to: {
            number: 2,
            versionId: 'v2',
            name: 'refurbishment.docx',
            sha256: 'a'.repeat(64),
            uploadedAt: '2026-11-10T08:00:00Z',
            uploadedBy: { id: 'u2', name: 'Bob Builder' },
            state: 'DONE',
            truncated: false,
            text: 'Budget 3 million\nThree branches',
          },
        }),
      ),
    )
    const { container } = renderRoute('/memos/m1')
    await user.click(await screen.findByRole('button', { name: 'Compare versions' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Compare versions' })
    expect(await within(dialog).findByText('1 change')).toBeInTheDocument()
    expect(within(dialog).getByText('2', { selector: 'del' })).toBeInTheDocument()
    expect(within(dialog).getByText('3', { selector: 'ins' })).toBeInTheDocument()
    expect(
      within(dialog).getByRole('columnheader', { name: /Version 2 · refurbishment.docx · Bob Builder/ }),
    ).toBeInTheDocument()
    await expectNoA11yViolations(container)
  }, 20_000)

  it('the initiator removes a co-author', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    server.use(...handlers())
    server.use(
      sessionHandler({ ...signedInSession(), user: { ...signedInSession().user!, id: 'u1' } }),
      http.put(`${API_ORIGIN}/api/v1/memos/m1/coauthors`, async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json<MemoView>({ ...memo, coauthors: [] })
      }),
    )
    renderRoute('/memos/m1')
    await user.click(await screen.findByRole('button', { name: 'Remove Bob Builder' }, { timeout: 5000 }))
    await waitFor(() => {
      expect(bodies).toEqual([{ userIds: [] }])
    })
    expect(await screen.findByText('Nobody yet.')).toBeInTheDocument()
  }, 20_000)
})
