import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type {
  DirectorySearchResult,
  LegalEntity,
  OrgPosition,
  OrgPositionWithHolder,
  OrgUnit,
  OrgUnitDetail,
} from '@/api/generated/types.gen'
import { accessHandler, API_ORIGIN, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute, renderWithProviders } from '@/test/render'
import { PeoplePicker, type Picked } from './PeoplePicker'

const entity: LegalEntity = { id: 'e1', name: 'Bank of Example', validFrom: '2026-01-01', version: 0 }
const unit = (id: string, name: string, parentId?: string): OrgUnit => ({
  id,
  legalEntityId: 'e1',
  ...(parentId ? { parentId } : {}),
  code: id.toUpperCase(),
  name,
  validFrom: '2026-01-01',
  version: 2,
})
const head = unit('hq', 'Head Office')
const credit = unit('crd', 'Credit Division', 'hq')
const officer: OrgPosition = {
  id: 'p1',
  unitId: 'crd',
  title: 'Credit Officer',
  head: false,
  validFrom: '2026-01-01',
  version: 0,
}

function orgBackend(canManage: boolean) {
  server.use(
    sessionHandler(signedInSession()),
    accessHandler(canManage ? ['org.manage'] : []),
    http.get(`${API_ORIGIN}/api/v1/org/units`, () => HttpResponse.json<OrgUnit[]>([head, credit])),
    http.get(`${API_ORIGIN}/api/v1/org/legal-entities`, () => HttpResponse.json<LegalEntity[]>([entity])),
    http.get(`${API_ORIGIN}/api/v1/org/units/crd`, () =>
      HttpResponse.json<OrgUnitDetail>({ unit: credit, path: [head], canManage }),
    ),
    http.get(`${API_ORIGIN}/api/v1/org/units/crd/positions`, () =>
      HttpResponse.json<OrgPositionWithHolder[]>([
        {
          position: officer,
          holder: { id: 'u1', displayName: 'Leila Nasser', email: 'leila@bank.example' },
          holderSince: '2026-02-01',
        },
      ]),
    ),
  )
}

describe('org directory', () => {
  it('shows the tree, and for a unit its path and positions with holders, without changes for readers', async () => {
    orgBackend(false)
    const { container } = renderRoute('/org?unit=crd')

    // The route chunk is compiled on first use during the run, so the first query waits longer.
    expect(await screen.findByRole('tree', { name: 'Units' }, { timeout: 5000 })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: /Credit Division/ })).toBeInTheDocument()
    const table = await screen.findByRole('table', { name: 'Positions of the unit' })
    expect(within(table).getByRole('link', { name: 'Credit Officer' })).toHaveAttribute('href', '/org/positions/p1')
    expect(within(table).getByText('Leila Nasser')).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Unit path' })).toHaveTextContent('Head Office')
    expect(screen.queryByRole('button', { name: 'Add position' })).not.toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('lets a manager add a position once, with an idempotency key, and shows the new list', async () => {
    const user = userEvent.setup()
    orgBackend(true)
    const keys: (string | null)[] = []
    let body: unknown
    server.use(
      http.post(`${API_ORIGIN}/api/v1/org/positions`, async ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        body = await request.json()
        return HttpResponse.json<OrgPosition>(
          { ...officer, id: 'p2', title: 'Head of Credit', head: true },
          { status: 201 },
        )
      }),
    )
    renderRoute('/org?unit=crd')

    await user.click(await screen.findByRole('button', { name: 'Add position' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'New position in Credit Division' })
    await user.type(within(dialog).getByLabelText(/Title/), 'Head of Credit')
    await user.click(within(dialog).getByLabelText('Head of the unit'))
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Saved.')).toBeInTheDocument()
    expect(body).toEqual({ title: 'Head of Credit', head: true, unitId: 'crd' })
    expect(keys).toHaveLength(1)
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
  })
})

describe('people picker', () => {
  it('searches after two letters and picks with the keyboard, as an ARIA combobox', async () => {
    const user = userEvent.setup()
    const queries: string[] = []
    server.use(
      http.get(`${API_ORIGIN}/api/v1/directory/search`, ({ request }) => {
        queries.push(new URL(request.url).searchParams.get('q') ?? '')
        return HttpResponse.json<DirectorySearchResult>({
          users: [
            {
              person: { id: 'u1', displayName: 'Leila Nasser', email: 'leila@bank.example' },
              position: { id: 'p1', title: 'Credit Officer', unitName: 'Credit Division' },
            },
            { person: { id: 'u2', displayName: 'Leila Haddad', email: 'lh@bank.example' } },
          ],
          positions: [],
        })
      }),
    )
    let picked: Picked | undefined
    renderWithProviders(
      <PeoplePicker
        kind="user"
        onChange={(p) => {
          picked = p
        }}
      />,
    )

    const box = screen.getByRole('combobox')
    await user.type(box, 'L')
    expect(screen.getByText('Type at least two letters of a name or title.')).toBeInTheDocument()
    await user.type(box, 'ei')
    const list = await screen.findByRole('listbox')
    expect(within(list).getAllByRole('option')).toHaveLength(2)
    expect(within(list).getByText('Credit Officer · Credit Division')).toBeInTheDocument()
    expect(box).toHaveAttribute('aria-expanded', 'true')

    await user.keyboard('{ArrowDown}{Enter}')

    await waitFor(() => {
      expect(picked).toEqual({ id: 'u2', label: 'Leila Haddad', detail: 'lh@bank.example' })
    })
    expect(box).toHaveValue('Leila Haddad')
    expect(queries).toEqual(['Lei'])
  })
})
