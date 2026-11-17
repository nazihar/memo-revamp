import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type {
  ClassificationLevel,
  DefinitionChoice,
  MemoType,
  MemoTypeVersionResponse,
  NumberRegisterView,
  NumberSeriesView,
  NumberingPreview,
} from '@/api/generated/types.gen'
import { partsForPath } from '@/i18n/catalogue-parts'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const type: MemoType = {
  id: 't1',
  name: 'Credit proposal',
  code: 'CP',
  active: true,
  publishedVersion: 1,
  definitionKey: 'sequential-approval',
  routePolicy: 'INITIATOR_DEFINED',
  stepUpOnApprove: true,
  doaCheck: false,
  allowedApprovers: {},
  allowedNames: {},
  version: 3,
  pending: { number: 2, state: 'IN_REVIEW', submittedBy: { id: 'u2', name: 'Maker Mo' } },
}

const published: MemoTypeVersionResponse = {
  number: 1,
  state: 'PUBLISHED',
  revision: 4,
  name: 'Credit proposal',
  code: 'CP',
  definitionKey: 'sequential-approval',
  routePolicy: 'INITIATOR_DEFINED',
  allowedApprovers: {},
  allowedNames: {},
  doaCheck: false,
  stepUpOnApprove: true,
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
  ],
  raiseScope: {},
  numbering: {
    series: 'credit',
    parts: [
      { kind: 'TEXT', text: 'CR/' },
      { kind: 'SEQUENCE', digits: 4 },
    ],
    reset: 'NEVER',
    countPer: 'SERIES',
    moment: 'ON_SUBMIT',
    fyStartMonth: 7,
  },
  attachmentRules: { required: false, min: 0 },
  createdAt: '2026-11-14T08:00:00Z',
}

const draft: MemoTypeVersionResponse = { ...published, number: 2, state: 'DRAFT', revision: 0 }
const inReview: MemoTypeVersionResponse = { ...draft, state: 'IN_REVIEW', submittedBy: { id: 'u2', name: 'Maker Mo' } }

function handlers(versions: MemoTypeVersionResponse[], permissions = ['memo.types.manage', 'memo.types.approve']) {
  return [
    sessionHandler(signedInSession(true)),
    accessHandler(permissions),
    csrfHandler(),
    http.get(`${API_ORIGIN}/api/v1/admin/memo-types`, () => HttpResponse.json<MemoType[]>([type])),
    http.get(`${API_ORIGIN}/api/v1/admin/memo-types/t1/versions`, () => HttpResponse.json(versions)),
    http.get(`${API_ORIGIN}/api/v1/admin/memo-types/t1/versions/:number`, ({ params }) =>
      HttpResponse.json(versions.find((v) => String(v.number) === params.number) ?? published),
    ),
    http.get(`${API_ORIGIN}/api/v1/authz/classification-levels`, () =>
      HttpResponse.json<ClassificationLevel[]>([{ id: 'l0', name: 'Internal', rank: 0 } as ClassificationLevel]),
    ),
    http.get(`${API_ORIGIN}/api/v1/admin/memo-types/definitions`, () =>
      HttpResponse.json<DefinitionChoice[]>([
        { key: 'sequential-approval', name: 'Sequential approval', routed: true },
      ]),
    ),
    http.post(`${API_ORIGIN}/api/v1/admin/memo-types/numbering/preview`, () =>
      HttpResponse.json<NumberingPreview>({ samples: ['CR/0001', 'CR/0001'], problems: [] }),
    ),
    http.get(`${API_ORIGIN}/api/v1/authz/roles`, () => HttpResponse.json([])),
  ]
}

describe('memo types (Phase 3 E01 T6)', () => {
  it('lists types with their code, published version and the version waiting for review', async () => {
    server.use(...handlers([published]))
    const { container } = renderRoute('/admin/memo-types')

    const row = (await screen.findByRole('link', { name: 'Credit proposal' }, { timeout: 5000 })).closest('tr')
    expect(row).not.toBeNull()
    const cells = within(row as HTMLElement)
    expect(cells.getByText('CP')).toBeInTheDocument()
    expect(cells.getByText('v1')).toBeInTheDocument()
    expect(cells.getByText('Version 2 in review, submitted by Maker Mo')).toBeInTheDocument()
    expect(partsForPath('/admin/memo-types')).toContain('memoAdmin')
    await expectNoA11yViolations(container)
  })

  it('a draft is edited, a field added in its dialog, the form previewed, and saved behind its revision', async () => {
    const user = userEvent.setup()
    const matches: (string | null)[] = []
    const bodies: { name: string; fields: { key: string; type: string }[] }[] = []
    server.use(
      ...handlers([draft, published]),
      http.put(`${API_ORIGIN}/api/v1/admin/memo-types/t1/versions/2`, async ({ request }) => {
        matches.push(request.headers.get('If-Match'))
        bodies.push((await request.json()) as (typeof bodies)[number])
        return HttpResponse.json<MemoTypeVersionResponse>({ ...draft, revision: 1 })
      }),
    )
    const { container } = renderRoute('/admin/memo-types/t1?version=2')

    const name = await screen.findByRole('textbox', { name: /Name/ }, { timeout: 5000 })
    await user.clear(name)
    await user.type(name, 'Credit proposals')
    await user.click(screen.getByRole('tab', { name: 'Form' }))
    // The preview shows the form as authors see it.
    expect(screen.getByRole('textbox', { name: /Facility amount/ })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add field' }))
    const dialog = await screen.findByRole('dialog', { name: 'Add field' })
    await user.type(within(dialog).getByRole('textbox', { name: /Label/ }), 'Customer name')
    expect(within(dialog).getByRole('textbox', { name: /Key/ })).toHaveValue('customerName')
    await user.click(within(dialog).getByRole('button', { name: 'Keep field' }))
    // In the field list, and as a label in the preview.
    expect(screen.getAllByText(/Customer name/).length).toBeGreaterThanOrEqual(2)

    await user.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByText('Saved.')
    expect(matches).toEqual(['"0"'])
    expect(bodies[0]?.name).toBe('Credit proposals')
    expect(bodies[0]?.fields.map((f) => f.key)).toEqual(['amount', 'customerName'])
    await expectNoA11yViolations(container)
  }, 20_000)

  it('the numbering tab shows the server’s sample', async () => {
    const user = userEvent.setup()
    server.use(...handlers([draft, published]))
    renderRoute('/admin/memo-types/t1?version=2')
    await user.click(await screen.findByRole('tab', { name: 'Numbering' }, { timeout: 5000 }))
    // Now and at the start of the next period (a series that never restarts reads the same).
    expect(await screen.findAllByText('CR/0001', {}, { timeout: 3000 })).toHaveLength(2)
  })

  it('a reviewer sees the changes first and approves with a remark; sending back needs one', async () => {
    const user = userEvent.setup()
    const approved: unknown[] = []
    server.use(
      ...handlers([{ ...inReview, name: 'Credit proposals' }, published]),
      http.post(`${API_ORIGIN}/api/v1/admin/memo-types/t1/versions/2/approve`, async ({ request }) => {
        approved.push(await request.json())
        return HttpResponse.json<MemoTypeVersionResponse>({ ...inReview, state: 'PUBLISHED' })
      }),
    )
    const { container } = renderRoute('/admin/memo-types/t1?version=2')

    expect(await screen.findByText('Changed', {}, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send back' })).toBeDisabled()
    await user.type(screen.getByRole('textbox', { name: /Remark/ }), 'Fine')
    await user.click(screen.getByRole('button', { name: 'Approve and publish' }))
    await screen.findByText('Saved.')
    expect(approved).toEqual([{ remark: 'Fine' }])
    await expectNoA11yViolations(container)
  }, 20_000)

  it('the numbering register shows void numbers and whether any number is missing', async () => {
    server.use(
      ...handlers([published], ['memo.types.approve']),
      http.get(`${API_ORIGIN}/api/v1/admin/memo-numbering`, () =>
        HttpResponse.json<NumberSeriesView[]>([
          {
            series: 'credit',
            types: [{ id: 't1', name: 'Credit proposal' }],
            counters: [{ period: 'ALL', scope: '', lastNumber: 2, startedByAdmin: false }],
          },
        ]),
      ),
      http.get(`${API_ORIGIN}/api/v1/admin/memo-numbering/credit/register`, () =>
        HttpResponse.json<NumberRegisterView>({
          series: 'credit',
          period: 'ALL',
          scope: '',
          lastNumber: 2,
          entries: [
            {
              number: 'CR/0001',
              sequence: 1,
              memoId: 'm1',
              status: 'APPROVED',
              void: false,
              subject: 'Working capital',
            },
            { number: 'CR/0002', sequence: 2, memoId: 'm2', status: 'WITHDRAWN', void: true },
          ],
          missing: [],
          missingCount: 0,
        }),
      ),
    )
    const user = userEvent.setup()
    const { container } = renderRoute('/admin/memo-numbering')
    expect(await screen.findByText('Used by: Credit proposal', {}, { timeout: 5000 })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Register' }))
    expect(await screen.findByText('Complete: every number up to the last has its memo.')).toBeInTheDocument()
    expect(screen.getByText('Void')).toBeInTheDocument()
    expect(screen.getByText('Not visible to you')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Set a starting number' })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  }, 20_000)
})
