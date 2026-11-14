import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type {
  ClassificationLevel,
  HistoryRound,
  MemoRequest,
  MemoTypeForm,
  MemoTypeOption,
  MemoView,
} from '@/api/generated/types.gen'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const types: MemoTypeOption[] = [
  { id: 't0', name: 'General memo', hasFields: false, numberingMoment: 'ON_SUBMIT', stepUpOnApprove: true },
  { id: 't1', name: 'Credit proposal', hasFields: true, numberingMoment: 'ON_APPROVAL', stepUpOnApprove: true },
]

const creditForm: MemoTypeForm = {
  id: 't1',
  name: 'Credit proposal',
  version: 2,
  numberingMoment: 'ON_APPROVAL',
  fields: [
    {
      key: 'amount',
      label: 'Facility amount',
      type: 'AMOUNT',
      required: true,
      attribute: true,
      inbox: false,
      rules: { currencies: ['BDT', 'USD'] },
    },
    {
      key: 'purpose',
      label: 'Purpose',
      type: 'CHOICE',
      required: false,
      attribute: false,
      inbox: false,
      rules: {
        choices: [
          { key: 'wc', label: 'Working capital' },
          { key: 'term', label: 'Term loan' },
        ],
      },
    },
  ],
}

const memo: MemoView = {
  id: 'm1',
  reference: null,
  numberedWhen: 'ON_APPROVAL',
  subject: 'Working capital line',
  type: { id: 't1', name: 'Credit proposal' },
  typeVersion: 2,
  classification: { id: 'c1', rank: 0, name: 'Internal' },
  status: 'DRAFT',
  initiator: { id: 'u1', name: 'Alice Author' },
  route: [],
  round: 1,
  holders: [],
  sendBackTargets: [],
  allowedActions: ['EDIT', 'DISCARD'],
  createdAt: '2026-11-09T08:00:00Z',
  updatedAt: '2026-11-10T08:00:00Z',
  version: 3,
  interests: [],
  details: [
    {
      key: 'amount',
      label: 'Facility amount',
      type: 'AMOUNT',
      value: { value: '6000000.00', currency: 'BDT' },
    },
    { key: 'purpose', label: 'Purpose', type: 'CHOICE', value: 'wc', display: 'Working capital' },
  ],
  staleDetails: [],
  links: [],
  linkedFrom: [
    {
      id: 'l9',
      kind: 'FOLLOWS_UP',
      memoId: 'm9',
      reference: 'CP/0009',
      subject: 'Renewal',
      status: 'DRAFT',
      removable: false,
    },
  ],
  canLink: true,
}

function handlers(view: MemoView = memo) {
  return [
    sessionHandler(signedInSession()),
    accessHandler(['memo.create']),
    csrfHandler(),
    http.get(`${API_ORIGIN}/api/v1/memo-types`, () => HttpResponse.json<MemoTypeOption[]>(types)),
    http.get(`${API_ORIGIN}/api/v1/memo-types/t1/form`, () => HttpResponse.json<MemoTypeForm>(creditForm)),
    http.get(`${API_ORIGIN}/api/v1/authz/classification-levels`, () =>
      HttpResponse.json<ClassificationLevel[]>([{ id: 'c1', name: 'Internal', rank: 0 } as ClassificationLevel]),
    ),
    http.get(`${API_ORIGIN}/api/v1/memos/m1`, () => HttpResponse.json<MemoView>(view)),
    http.get(`${API_ORIGIN}/api/v1/memos/m1/history`, () => HttpResponse.json<HistoryRound[]>([])),
  ]
}

describe('new memo (Phase 3 E01 T7)', () => {
  it('asks for the type first, then shows that type’s details and sends them with the draft', async () => {
    const user = userEvent.setup()
    const bodies: MemoRequest[] = []
    server.use(
      ...handlers(),
      http.post(`${API_ORIGIN}/api/v1/memos`, async ({ request }) => {
        bodies.push((await request.json()) as MemoRequest)
        return HttpResponse.json<MemoView>(memo, { status: 201 })
      }),
    )
    const { container } = renderRoute('/memos/new')

    const credit = await screen.findByRole('radio', { name: /Credit proposal/ }, { timeout: 5000 })
    expect(screen.getByRole('radio', { name: /General memo/ })).toBeChecked()
    expect(credit).toHaveAccessibleName(/Numbered when finally approved · Asks for details/)
    expect(screen.queryByText('Facility amount')).toBeNull()

    await user.click(credit)
    const amount = await screen.findByRole('textbox', { name: /Facility amount/ }, { timeout: 3000 })
    await user.type(screen.getByRole('textbox', { name: /Subject/ }), 'Working capital line')
    await user.type(amount, '6000000')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Currency' }), 'USD')
    await user.click(screen.getByRole('radio', { name: 'Working capital' }))
    await expectNoA11yViolations(container)
    await user.click(screen.getByRole('button', { name: 'Create draft' }))

    await screen.findByRole('heading', { name: 'Working capital line' }, { timeout: 5000 })
    expect(bodies).toEqual([
      {
        subject: 'Working capital line',
        typeId: 't1',
        metadata: { amount: { value: '6000000', currency: 'USD' }, purpose: 'wc' },
      },
    ])
  }, 20_000)

  it('shows the server’s message under the field it names', async () => {
    const user = userEvent.setup()
    server.use(
      ...handlers(),
      http.post(`${API_ORIGIN}/api/v1/memos`, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Unprocessable',
            status: 422,
            errors: [{ field: 'metadata.amount', code: 'TOO_MANY_DECIMALS', message: 'At most 2 decimals.' }],
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    renderRoute('/memos/new')
    await user.click(await screen.findByRole('radio', { name: /Credit proposal/ }, { timeout: 5000 }))
    await user.type(screen.getByRole('textbox', { name: /Subject/ }), 'Line')
    await user.type(await screen.findByRole('textbox', { name: /Facility amount/ }), '1.234')
    await user.click(screen.getByRole('button', { name: 'Create draft' }))
    expect(await screen.findByText('At most 2 decimals.')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: /Facility amount/ })).toHaveAttribute('aria-invalid', 'true')
  }, 20_000)
})

describe('memo view details and links (Phase 3 E01 T7)', () => {
  it('shows the details formatted, when it gets its number, and the memos linking to it', async () => {
    server.use(...handlers())
    const { container } = renderRoute('/memos/m1')

    expect(await screen.findByRole('heading', { name: 'Details' }, { timeout: 5000 })).toBeInTheDocument()
    const details = screen.getByRole('heading', { name: 'Details' }).closest('section')!
    expect(within(details).getByText(/BDT\s6,000,000\.00/)).toBeInTheDocument()
    expect(within(details).getByText('Working capital')).toBeInTheDocument()
    expect(screen.getByText(/^Unnumbered — numbered when finally approved/)).toBeInTheDocument()
    expect(screen.getByText('Follows up this memo')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /CP\/0009/ })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('reads in Arabic, right to left, with no axe findings', async () => {
    server.use(...handlers())
    const { container } = renderRoute('/memos/m1', { locale: 'ar' })
    expect(await screen.findByRole('heading', { name: 'التفاصيل' }, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByText(/بلا رقم — تُرقَّم عند الاعتماد النهائي/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'المذكرات المرتبطة' })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  }, 20_000)

  it('a draft whose type dropped fields says so and lists those values apart', async () => {
    server.use(
      ...handlers({
        ...memo,
        staleDetails: [{ key: 'branch', label: 'Branch code', type: 'TEXT', value: 'HO-01' }],
      }),
    )
    renderRoute('/memos/m1')
    expect(
      await screen.findByText(/This memo type changed since the draft was written/, {}, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'No longer on this form' })).toBeInTheDocument()
    expect(screen.getByText('HO-01')).toBeInTheDocument()
  })

  it('edits a draft’s details in the edit dialog, starting from what it has', async () => {
    const user = userEvent.setup()
    const bodies: MemoRequest[] = []
    server.use(
      ...handlers(),
      http.put(`${API_ORIGIN}/api/v1/memos/m1`, async ({ request }) => {
        bodies.push((await request.json()) as MemoRequest)
        return HttpResponse.json<MemoView>({ ...memo, version: 4 })
      }),
    )
    renderRoute('/memos/m1')

    await user.click(await screen.findByRole('button', { name: 'Edit' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog')
    const amount = await within(dialog).findByRole('textbox', { name: /Facility amount/ }, { timeout: 3000 })
    expect(amount).toHaveValue('6000000.00')
    expect(within(dialog).getByRole('radio', { name: 'Working capital' })).toBeChecked()
    await user.click(within(dialog).getByRole('radio', { name: 'Term loan' }))
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => {
      expect(bodies).toHaveLength(1)
    })
    expect(bodies[0]?.metadata).toEqual({ amount: { value: '6000000.00', currency: 'BDT' }, purpose: 'term' })
  }, 20_000)

  it('links a memo by its number, and shows why a number cannot be linked', async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    server.use(
      ...handlers(),
      http.post(`${API_ORIGIN}/api/v1/memos/m1/links`, async ({ request }) => {
        const body = (await request.json()) as { targetReference: string }
        sent.push(body)
        if (body.targetReference === 'NOPE') {
          return HttpResponse.json(
            {
              type: 'about:blank',
              title: 'Unprocessable',
              status: 422,
              errors: [
                {
                  field: 'targetReference',
                  code: 'LINK_TARGET_UNREADABLE',
                  message: 'No memo you can open has this number.',
                },
              ],
            },
            { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
          )
        }
        return HttpResponse.json<MemoView>({
          ...memo,
          links: [
            {
              id: 'l1',
              kind: 'SUPERSEDES',
              memoId: 'm7',
              reference: 'CP/0007',
              subject: 'Old line',
              status: 'APPROVED',
              removable: true,
            },
          ],
        })
      }),
    )
    renderRoute('/memos/m1')

    const number = await screen.findByRole('textbox', { name: /Memo number/ }, { timeout: 5000 })
    await user.type(number, 'NOPE')
    await user.click(screen.getByRole('button', { name: 'Link' }))
    expect(await screen.findByText('No memo you can open has this number.')).toBeInTheDocument()

    await user.clear(number)
    await user.type(number, 'CP/0007')
    await user.selectOptions(screen.getByRole('combobox', { name: 'How it relates' }), 'Supersedes')
    await user.click(screen.getByRole('button', { name: 'Link' }))
    expect(await screen.findByRole('link', { name: /CP\/0007/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Remove the link to .?CP\/0007/ })).toBeInTheDocument()
    expect(sent).toEqual([
      { targetReference: 'NOPE', kind: 'REFERS_TO' },
      { targetReference: 'CP/0007', kind: 'SUPERSEDES' },
    ])
  }, 20_000)

  it('the initiator closes an approved memo', async () => {
    const user = userEvent.setup()
    const closed: unknown[] = []
    const approved: MemoView = { ...memo, status: 'APPROVED', reference: 'CP/0010', allowedActions: ['CLOSE'] }
    server.use(
      ...handlers(approved),
      http.post(`${API_ORIGIN}/api/v1/memos/m1/close`, async ({ request }) => {
        closed.push(await request.json())
        return HttpResponse.json<MemoView>({ ...approved, status: 'CLOSED', allowedActions: [], canLink: false })
      }),
    )
    renderRoute('/memos/m1')

    const toolbar = await screen.findByRole('toolbar', { name: 'Actions' }, { timeout: 5000 })
    await user.click(within(toolbar).getByRole('button', { name: 'Close' }))
    const dialog = await screen.findByRole('dialog', { name: /^Close .?CP\/0010.?[?]$/ })
    await user.type(within(dialog).getByRole('textbox', { name: /Remark/ }), 'Disbursed')
    await user.click(within(dialog).getAllByRole('button', { name: 'Close' }).at(-1)!)
    await screen.findByText('Done.')
    expect(closed).toEqual([{ remark: 'Disbursed' }])
  }, 20_000)
})
