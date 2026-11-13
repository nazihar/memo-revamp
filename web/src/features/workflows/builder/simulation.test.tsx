import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { DirectorySearchResult } from '@/api/generated/types.gen'
import { API_ORIGIN, server } from '@/test/msw'
import { expectNoA11yViolations, renderWithProviders } from '@/test/render'
import type { DefinitionBody } from './definition'
import { SimulationPanel } from './SimulationPanel'

const body: DefinitionBody = {
  schemaVersion: 2,
  start: 'route',
  steps: [{ id: 'route', type: 'route', name: 'Approvers', next: 'end' }],
}

function backend(status: number, reply: object) {
  server.use(
    http.get(`${API_ORIGIN}/api/v1/directory/search`, () =>
      HttpResponse.json<DirectorySearchResult>({
        users: [{ person: { id: 'u1', displayName: 'Amina Initiator', email: 'a@bank.example' } }],
        positions: [],
      }),
    ),
    http.post(`${API_ORIGIN}/api/v1/admin/workflow-definitions/purchase/versions/1/simulate`, () =>
      HttpResponse.json(reply, { status }),
    ),
  )
}

async function simulate() {
  const user = userEvent.setup()
  const result = renderWithProviders(
    <SimulationPanel
      definitionKey="purchase"
      number={1}
      body={body}
      labels={{}}
      result={undefined}
      onResult={() => undefined}
    />,
  )
  await user.type(screen.getByRole('combobox', { name: /Started by/ }), 'Am')
  await user.click(await screen.findByRole('option', { name: /Amina Initiator/ }))
  await user.click(screen.getByRole('button', { name: 'Simulate' }))
  return result
}

describe('the simulation panel', () => {
  it('asks for the route when the workflow has a route step, and says when the workflow has problems', async () => {
    backend(422, { status: 422, reason: 'DEFINITION_INVALID', errors: [] })
    const { container } = await simulate()
    expect(screen.getByRole('group', { name: 'Approvers the initiator chooses' })).toBeInTheDocument()
    expect(await screen.findByText('The workflow has problems. Fix them, then simulate again.')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('says when there were too many simulations', async () => {
    backend(429, { status: 429, title: 'Too many requests' })
    await simulate()
    expect(await screen.findByText('Too many simulations. Try again in a minute.')).toBeInTheDocument()
  })
})
