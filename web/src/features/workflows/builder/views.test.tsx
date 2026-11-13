import { fireEvent, screen, within } from '@testing-library/react'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { installReactFlowStubs } from '@/test/react-flow'
import { expectNoA11yViolations, renderWithProviders } from '@/test/render'
import { DefinitionDiagram } from './DefinitionDiagram'
import type { DefinitionBody } from './definition'
import { StepsView } from './StepsView'

const body: DefinitionBody = {
  schemaVersion: 2,
  start: 'review',
  steps: [
    {
      id: 'review',
      type: 'review',
      name: 'Credit review',
      assignee: { kind: 'position', positionId: 'p1' },
      sla: { due: 'P1D', remind: { before: 'PT2H' } },
      next: 'size',
    },
    {
      id: 'size',
      type: 'branch',
      name: 'By classification',
      branches: [{ when: 'item.classificationRank >= 2', next: 'committee' }],
      otherwise: 'director',
    },
    {
      id: 'committee',
      type: 'parallel',
      name: 'Credit committee',
      mode: 'all',
      assignees: [
        { kind: 'person', userId: 'u1' },
        { kind: 'person', userId: 'u2' },
      ],
      next: 'tell',
    },
    { id: 'director', type: 'approve', name: 'Director', assignee: { kind: 'person', userId: 'u1' }, next: 'nowhere' },
    { id: 'tell', type: 'notify', name: 'Tell the author', recipients: [{ kind: 'initiator' }], next: 'end' },
  ],
}
const labels = { p1: 'Head of Credit', u1: 'Dina Director', u2: 'Farid Finance' }
const problems = [{ field: '/steps/3/next', code: 'UNKNOWN_STEP', message: 'No step "nowhere".' }]

describe('the builder views (read-only)', () => {
  beforeAll(installReactFlowStubs)

  it('the Steps view says in words what each step does, where it leads and what is wrong', async () => {
    const { container } = renderWithProviders(<StepsView body={body} labels={labels} problems={problems} />)
    const list = screen.getByRole('list', { name: 'Steps, in the order they are written' })
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(5)

    const review = within(items[0]!)
    expect(review.getByRole('heading', { name: '1. Credit review' })).toBeInTheDocument()
    expect(review.getByText('The holder of Head of Credit')).toBeInTheDocument()
    expect(review.getByText('Due in 1 working day; reminder 2 working hours before')).toBeInTheDocument()
    expect(review.getByText('By classification')).toBeInTheDocument()

    const branch = within(items[1]!)
    expect(branch.getByText('If item.classificationRank >= 2')).toBeInTheDocument()
    expect(branch.getByText('Credit committee')).toBeInTheDocument()
    expect(branch.getByText('Director')).toBeInTheDocument()

    expect(within(items[2]!).getByText('All of Dina Director and Farid Finance')).toBeInTheDocument()
    const director = within(items[3]!)
    expect(director.getByText('1 problem')).toBeInTheDocument()
    expect(director.getByText('Unknown (nowhere)')).toBeInTheDocument()
    expect(within(items[4]!).getByText('The initiator')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('the diagram names every step for a screen reader, with Start and End', async () => {
    const { container } = renderWithProviders(<DefinitionDiagram body={body} labels={labels} problems={problems} />)
    const diagram = screen.getByRole('group', { name: 'Workflow diagram' })
    expect(
      await within(diagram).findByLabelText(
        'Step 1 of 5, Review, Credit review. The holder of Head of Credit Leads to By classification.',
      ),
    ).toBeInTheDocument()
    expect(within(diagram).getByLabelText('Start')).toBeInTheDocument()
    expect(within(diagram).getByLabelText('End')).toBeInTheDocument()
    expect(within(diagram).getByText('Farid Finance', { exact: false })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('in a draft, selecting a step in the diagram opens its panel beside it', async () => {
    const onCommand = vi.fn()
    renderWithProviders(<DefinitionDiagram body={body} labels={labels} problems={problems} onCommand={onCommand} />)
    expect(screen.getByText('Select a step in the diagram to change it, or use the Steps view.')).toBeInTheDocument()
    fireEvent.click(await screen.findByLabelText(/^Step 4 of 5, Approval, Director./))
    const panel = screen.getByRole('region', { name: 'Director' })
    expect(within(panel).getByText('No step "nowhere".')).toBeInTheDocument()
    fireEvent.change(within(panel).getByLabelText('Name'), { target: { value: 'Managing director' } })
    expect(onCommand).toHaveBeenCalledWith({ kind: 'set', id: 'director', patch: { name: 'Managing director' } })
    fireEvent.change(within(panel).getByLabelText('Leads to'), { target: { value: 'tell' } })
    expect(onCommand).toHaveBeenCalledWith({ kind: 'connect', from: { kind: 'next', step: 'director' }, to: 'tell' })
  })

  it('arrow keys follow the connections, and Delete on a step of a draft asks before removing it', async () => {
    const onCommand = vi.fn()
    renderWithProviders(<DefinitionDiagram body={body} labels={labels} problems={[]} onCommand={onCommand} />)
    const review = await screen.findByLabelText(/^Step 1 of 5, Review, Credit review\./)
    review.focus()
    fireEvent.keyDown(review, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(screen.getByLabelText(/^Step 2 of 5, Branch, By classification\./))
    fireEvent.keyDown(document.activeElement!, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(screen.getByLabelText(/^Step 3 of 5, Parallel decision, Credit committee\./))
    fireEvent.keyDown(document.activeElement!, { key: 'ArrowRight' })
    expect(document.activeElement).toBe(screen.getByLabelText(/^Step 4 of 5, Approval, Director\./))
    fireEvent.keyDown(document.activeElement!, { key: 'ArrowUp' })
    expect(document.activeElement).toBe(screen.getByLabelText(/^Step 2 of 5, Branch, By classification\./))

    fireEvent.keyDown(document.activeElement!, { key: 'Delete' })
    const dialog = await screen.findByRole('alertdialog', { name: 'Remove “By classification”?' })
    expect(onCommand).not.toHaveBeenCalled()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Remove' }))
    expect(onCommand).toHaveBeenCalledWith({ kind: 'remove', id: 'size' })
  })
})
