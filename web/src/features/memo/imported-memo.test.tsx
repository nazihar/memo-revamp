import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type {
  ClassificationLevel,
  ImportBatchView,
  LegacyCorrectionRequest,
  LegacyMemoView,
  MemoView,
} from '@/api/generated/types.gen'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const legacy: LegacyMemoView = {
  batchId: 'b1',
  rowNumber: 214,
  sourceCode: 'LEGACY-DMS',
  legacyNumber: 'CR/2016/0108',
  importedAt: '2026-11-16T12:00:00Z',
  timeZone: 'Asia/Dhaka',
  raisedOn: '2016-03-03',
  decidedOn: '2016-03-21',
  status: 'Sanctioned',
  type: 'Credit',
  unit: 'CR-HO',
  classification: 'Conf',
  subject: 'Working capital line for Meghna Agro',
  initiator: { name: 'R. K. Bansal' },
  approvals: [
    {
      ordinal: 1,
      person: { name: 'A. Rahman', user: { id: 'u7', name: 'Demo Head of Credit' } },
      position: 'Head of Credit',
      decision: 'Recommended',
      decidedOn: '2016-03-14',
    },
    {
      ordinal: 2,
      person: { name: 'S. Karim' },
      position: 'GM Credit',
      decision: 'Sanctioned',
      decidedOn: '2016-03-21',
      remark: 'Subject to collateral',
    },
  ],
  originals: [
    {
      id: 'o1',
      role: 'MAIN',
      position: 0,
      title: 'proposal.doc',
      mediaType: 'application/msword',
      sizeBytes: 421_888,
      restricted: false,
      fileId: 'f1',
      versionId: 'v1',
    },
    {
      id: 'o2',
      role: 'ATTACHMENT',
      position: 1,
      title: 'scan.tif',
      mediaType: 'image/tiff',
      sizeBytes: 2_200_000,
      restricted: false,
      fileId: 'f2',
      versionId: 'v2',
    },
    { id: 'o3', role: 'ATTACHMENT', position: 2, restricted: true },
  ],
  corrections: [
    {
      field: 'RAISED_ON',
      before: '2016-03-13',
      after: '2016-03-03',
      reason: 'Typo in the legacy export',
      correctedAt: '2026-11-21T09:00:00Z',
      correctedBy: { id: 'u1', name: 'Demo Tenant Admin' },
    },
  ],
}

const memo: MemoView = {
  origin: 'IMPORTED',
  followsUp: [],
  id: 'm1',
  reference: 'CR/2016/0108',
  subject: 'Working capital line for Meghna Agro',
  type: { id: 't1', name: 'Credit proposal' },
  typeVersion: 2,
  classification: { id: 'l1', rank: 1, name: 'Confidential' },
  originUnit: { id: 'ou1', name: 'Credit Division' },
  status: 'APPROVED',
  route: [],
  round: 0,
  holders: [],
  sendBackTargets: [],
  allowedActions: [],
  createdAt: '2026-11-16T12:00:00Z',
  updatedAt: '2026-11-16T12:00:00Z',
  version: 1,
  interests: [],
  details: [],
  staleDetails: [],
  links: [],
  linkedFrom: [],
  canLink: false,
  coauthors: [],
  rendition: 'NONE',
  legacy,
}

const levels = [
  { id: 'l0', name: 'Internal', rank: 0 },
  { id: 'l1', name: 'Confidential', rank: 1 },
  { id: 'l2', name: 'Secret', rank: 2 },
] as ClassificationLevel[]

function handlers(view: MemoView, permissions: string[] = []) {
  return [
    sessionHandler(signedInSession()),
    accessHandler(permissions),
    csrfHandler(),
    http.get(`${API_ORIGIN}/api/v1/memos/m1`, () => HttpResponse.json<MemoView>(view)),
    http.get(`${API_ORIGIN}/api/v1/memos/m1/copies`, () =>
      HttpResponse.json({ copies: [], canEdit: false, canAdd: false, memoVersion: view.version }),
    ),
    http.get(`${API_ORIGIN}/api/v1/memos/m1/shares`, () => HttpResponse.json([])),
    http.get(`${API_ORIGIN}/api/v1/authz/classification-levels`, () => HttpResponse.json(levels)),
    http.get(`${API_ORIGIN}/api/v1/admin/imports/b1`, () =>
      HttpResponse.json({ id: 'b1', name: 'Head office 2015–2019' } as ImportBatchView),
    ),
  ]
}

describe('an imported memo (Phase 3 E06 T9, flow O1)', () => {
  it('shows the banner, the legacy details as given, the history as history, the originals and no final record', async () => {
    server.use(...handlers(memo))
    const { container } = renderRoute('/memos/m1')

    expect(await screen.findByTestId('imported-memo', {}, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByTestId('imported-badge')).toHaveTextContent('Imported')
    const banner = screen.getByRole('note')
    expect(banner).toHaveTextContent(/^Imported from LEGACY-DMS on Nov 16, 2026\./)
    expect(banner).toHaveTextContent('Created and decided in an earlier system; not verified by this system.')
    // Readers without memo.import do not see the batch's name.
    expect(banner).not.toHaveTextContent('Head office')

    const details = screen.getByRole('region', { name: 'Legacy details' })
    expect(details).toHaveTextContent('Credit proposal (legacy: “Credit”)')
    expect(details).toHaveTextContent('Credit Division (legacy: “CR-HO”)')
    expect(details).toHaveTextContent('Approved (legacy: “Sanctioned”)')
    expect(details).toHaveTextContent('R. K. Bansal (as given; not linked to a user)')
    expect(details).toHaveTextContent('Mar 3, 2016')
    expect(within(details).getByText('CR/2016/0108')).toHaveAttribute('dir', 'ltr')

    const history = screen.getByRole('region', {
      name: 'Legacy approval history (imported, not verified by this system)',
    })
    expect(history).toHaveTextContent('A. Rahman (linked: Demo Head of Credit)')
    expect(history).toHaveTextContent('Subject to collateral')

    const originals = screen.getByRole('region', { name: 'Original documents' })
    expect(within(originals).getByRole('button', { name: 'Preview proposal.doc' })).toBeInTheDocument()
    expect(within(originals).getByRole('link', { name: 'Download proposal.doc' })).toHaveAttribute(
      'href',
      '/api/v1/files/f1/versions/v1/content',
    )
    // TIFF is download-only.
    expect(within(originals).queryByRole('button', { name: 'Preview scan.tif' })).not.toBeInTheDocument()
    expect(within(originals).getByText('Download only')).toBeInTheDocument()
    expect(within(originals).getByText('Restricted original')).toBeInTheDocument()

    expect(screen.getByRole('region', { name: 'Final record' })).toHaveTextContent(
      'No final record: for an imported memo the original documents are the record.',
    )
    expect(screen.getByRole('region', { name: 'Corrections (1)' })).toHaveTextContent(
      /Raised on: 2016-03-13 → 2016-03-03 · “Typo in the legacy export” · Demo Tenant Admin/,
    )
    // No route, note sheet or action bar.
    expect(screen.queryByRole('heading', { name: 'Note sheet' })).not.toBeInTheDocument()
    expect(screen.queryByRole('toolbar')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Correct a legacy detail' })).not.toBeInTheDocument()
    await expectNoA11yViolations(container)
  }, 20_000)

  it('shows a linked initiator by name, and holders of memo.import the batch’s name', async () => {
    server.use(
      ...handlers(
        {
          ...memo,
          legacy: { ...legacy, initiator: { name: 'R. K. Bansal', user: { id: 'u3', name: 'Rakesh Bansal' } } },
        },
        ['memo.import'],
      ),
    )
    renderRoute('/memos/m1')
    expect(await screen.findByText(/\(“Head office 2015–2019”\)/, {}, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Legacy details' })).toHaveTextContent(
      'R. K. Bansal (linked: Rakesh Bansal)',
    )
  }, 20_000)

  it('lets a holder correct a detail and raise the classification, each with a reason and the step-up', async () => {
    const corrections: LegacyCorrectionRequest[] = []
    const raises: unknown[] = []
    let stepped = false
    server.use(
      ...handlers({ ...memo, allowedActions: ['CORRECT_LEGACY', 'RECLASSIFY'] }, ['memo.import']),
      http.post(`${API_ORIGIN}/api/v1/memos/m1/legacy/corrections`, async ({ request }) => {
        corrections.push((await request.json()) as LegacyCorrectionRequest)
        if (corrections.length === 1) {
          return HttpResponse.json(
            { status: 422, errors: [{ field: 'value', code: 'DATE_FUTURE', message: 'x' }] },
            { status: 422 },
          )
        }
        if (!stepped) {
          stepped = true
          return HttpResponse.json({ type: 'urn:memo:problem:step-up-required', status: 401 }, { status: 401 })
        }
        return HttpResponse.json<MemoView>({ ...memo, allowedActions: ['CORRECT_LEGACY', 'RECLASSIFY'] })
      }),
      http.post(`${API_ORIGIN}/api/v1/auth/step-up`, () => HttpResponse.json({ state: 'AUTHENTICATED' })),
      http.post(`${API_ORIGIN}/api/v1/memos/m1/legacy/reclassify`, async ({ request }) => {
        raises.push(await request.json())
        return HttpResponse.json(
          { status: 422, errors: [{ field: 'classificationId', code: 'above-clearance', message: 'x' }] },
          { status: 422 },
        )
      }),
    )
    const user = userEvent.setup()
    renderRoute('/memos/m1')
    await user.click(await screen.findByRole('button', { name: 'Correct a legacy detail' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Correct a legacy detail' })
    await user.selectOptions(within(dialog).getByRole('combobox', { name: /Detail/ }), 'DECIDED_ON')
    expect(within(dialog).getByLabelText(/Corrected value/)).toHaveValue('2016-03-21')
    await user.click(within(dialog).getByRole('button', { name: 'Confirm and verify identity' }))
    expect(within(dialog).getByText('Required.')).toBeInTheDocument()
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Wrong day in the export')
    await user.click(within(dialog).getByRole('button', { name: 'Confirm and verify identity' }))
    expect(await within(dialog).findByText('The date cannot be in the future.')).toBeInTheDocument()
    expect(corrections[0]).toEqual({ field: 'DECIDED_ON', value: '2016-03-21', reason: 'Wrong day in the export' })

    // Second try: the server asks for the step-up first.
    await user.click(within(dialog).getByRole('button', { name: 'Confirm and verify identity' }))
    const stepUp = await screen.findByRole('dialog', { name: 'Confirm it is you' })
    expect(stepUp).toBeInTheDocument()
    await user.type(within(stepUp).getByRole('textbox', { name: /Code/ }), '123456')
    await user.click(within(stepUp).getByRole('button', { name: 'Confirm' }))
    await vi.waitFor(() => {
      expect(corrections).toHaveLength(3)
    })
    expect(await screen.findByText('The correction was added.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Raise classification' }))
    const raise = await screen.findByRole('dialog', { name: 'Raise classification' })
    const level = within(raise).getByRole('combobox', { name: /New classification/ })
    expect(
      within(level)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['Secret'])
    await user.type(within(raise).getByRole('textbox', { name: /Reason/ }), 'Board material')
    await user.click(within(raise).getByRole('button', { name: 'Confirm and verify identity' }))
    expect(await within(raise).findByText('You are not cleared to that level.')).toBeInTheDocument()
    expect(raises).toEqual([{ classificationId: 'l2', reason: 'Board material' }])
  }, 30_000)

  it('reads in Arabic, right to left, with the legacy number left to right and no axe findings', async () => {
    server.use(...handlers(memo))
    const { container } = renderRoute('/memos/m1', { locale: 'ar' })
    expect(await screen.findByText('مستوردة', {}, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByRole('note')).toHaveTextContent('لم يتحقق منها هذا النظام')
    expect(screen.getByRole('region', { name: 'المستندات الأصلية' })).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'التفاصيل القديمة' })).getByText('CR/2016/0108')).toHaveAttribute(
      'dir',
      'ltr',
    )
    await expectNoA11yViolations(container)
  }, 20_000)
})
