import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { expectNoA11yViolations, renderWithProviders } from '@/test/render'
import { TreePanel, type TreeNode } from './TreePanel'

const nodes: TreeNode[] = [
  {
    id: 'hq',
    label: 'Head Office',
    children: [
      { id: 'fin', label: 'Finance' },
      { id: 'credit', label: 'Credit Risk', children: [{ id: 'retail', label: 'Retail Credit' }] },
    ],
  },
  { id: 'branches', label: 'Branches' },
]

describe('TreePanel (WAI-ARIA tree pattern)', () => {
  it('has one tab stop and moves with the arrow keys, expanding and collapsing', async () => {
    const user = userEvent.setup()
    const { container } = renderWithProviders(<TreePanel label="Units" nodes={nodes} onSelect={vi.fn()} />)
    const tree = screen.getByRole('tree', { name: 'Units' })
    expect(tree.querySelectorAll('[tabindex="0"]')).toHaveLength(1)
    await expectNoA11yViolations(container)

    await user.tab()
    expect(screen.getByRole('treeitem', { name: 'Head Office' })).toHaveFocus()
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('treeitem', { name: 'Head Office' })).toHaveAttribute('aria-expanded', 'true')
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('treeitem', { name: 'Finance' })).toHaveFocus()
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('treeitem', { name: 'Credit Risk' })).toHaveFocus()
    await user.keyboard('{ArrowLeft}')
    expect(screen.getByRole('treeitem', { name: 'Head Office' })).toHaveFocus()
    await user.keyboard('{ArrowLeft}')
    expect(screen.getByRole('treeitem', { name: 'Head Office' })).toHaveAttribute('aria-expanded', 'false')
    await user.keyboard('{End}')
    expect(screen.getByRole('treeitem', { name: 'Branches' })).toHaveFocus()
  })

  it('selects with Enter and jumps by first letter', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    renderWithProviders(<TreePanel label="Units" nodes={nodes} onSelect={onSelect} defaultExpanded={['hq']} />)
    await user.tab()
    await user.keyboard('c')
    expect(screen.getByRole('treeitem', { name: 'Credit Risk' })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(onSelect).toHaveBeenCalledWith('credit')
  })
})
