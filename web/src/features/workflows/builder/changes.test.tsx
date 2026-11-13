import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeAll, describe, expect, it } from 'vitest'
import type { VersionResponse } from '@/api/generated/types.gen'
import { installReactFlowStubs } from '@/test/react-flow'
import { API_ORIGIN, server } from '@/test/msw'
import { expectNoA11yViolations, renderWithProviders } from '@/test/render'
import { ChangesView } from './ChangesView'
import type { DefinitionBody } from './definition'

const v1: DefinitionBody = {
  schemaVersion: 2,
  start: 'review',
  steps: [
    {
      id: 'review',
      type: 'review',
      name: 'Credit review',
      assignee: { kind: 'person', userId: 'u1' },
      next: 'director',
    },
    { id: 'director', type: 'approve', name: 'Director', next: 'tell' },
    { id: 'tell', type: 'notify', name: 'Tell the author', next: 'end' },
  ],
}

const v2: DefinitionBody = {
  schemaVersion: 2,
  start: 'review',
  steps: [
    {
      id: 'review',
      type: 'review',
      name: 'Credit review',
      assignee: { kind: 'person', userId: 'u2' },
      next: 'director',
    },
    { id: 'director', type: 'approve', name: 'Director', sla: { due: 'P2D' }, next: 'board' },
    { id: 'board', type: 'approve', name: 'Board', next: 'end' },
  ],
}

function stored(number: number, body: DefinitionBody): VersionResponse {
  return {
    id: `v${String(number)}`,
    key: 'credit',
    version: { number, state: 'PUBLISHED' },
    revision: 1,
    body,
    problems: [],
    labels: { u1: 'Amina Rahman' },
  }
}

describe('the review’s Changes view', () => {
  beforeAll(installReactFlowStubs)

  it('compares with the published version: added, removed and changed steps, in words and on the diagram', async () => {
    const user = userEvent.setup()
    server.use(
      http.get(`${API_ORIGIN}/api/v1/admin/workflow-definitions/credit/versions/:number`, ({ params }) =>
        HttpResponse.json<VersionResponse>(
          params.number === '1' ? stored(1, v1) : stored(3, { ...v1, steps: v1.steps.slice(0, 1) }),
        ),
      ),
    )
    const { container } = renderWithProviders(
      <ChangesView
        definitionKey="credit"
        number={2}
        body={v2}
        labels={{ u2: 'Bilal Chowdhury' }}
        versions={[1, 2, 3]}
        compareWith={1}
      />,
    )

    expect(await screen.findByText('1 step added. 1 step removed. 2 steps changed.')).toBeInTheDocument()
    const list = screen.getByRole('list', { name: 'Changes, step by step' })
    const items = within(list).getAllByRole('listitem')
    expect(items.map((li) => within(li).getByRole('heading').textContent)).toEqual([
      'Credit review',
      'Director',
      'Board',
      'Tell the author',
    ])
    const review = within(items[0]!)
    expect(review.getByText('Changed')).toBeInTheDocument()
    expect(review.getByText('Goes to')).toBeInTheDocument()
    expect(review.getByText('Amina Rahman → Bilal Chowdhury')).toBeInTheDocument()
    const director = within(items[1]!)
    expect(director.getByText('Tell the author → Board')).toBeInTheDocument()
    expect(director.getByText('none → Due in 2 working days')).toBeInTheDocument()
    expect(within(items[2]!).getByText('Added')).toBeInTheDocument()
    expect(within(items[3]!).getByText('Removed')).toBeInTheDocument()

    const diagram = screen.getByRole('group', { name: 'Workflow diagram' })
    expect(
      within(diagram).getByLabelText(/^Step 3 of 3, Approval, Board\..* Added in this version\.$/),
    ).toBeInTheDocument()
    expect(
      within(diagram).getByLabelText(/^Step 2 of 3, Approval, Director\..* Changed in this version\.$/),
    ).toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.selectOptions(screen.getByLabelText('Compared with'), 'Version 3')
    expect(await screen.findByText('2 steps added. 1 step changed.')).toBeInTheDocument()
  })
})
