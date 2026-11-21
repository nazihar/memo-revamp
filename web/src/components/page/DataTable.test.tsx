import type { SortingState } from '@tanstack/react-table'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoA11yViolations, renderWithProviders } from '@/test/render'
import { DataTable } from './DataTable'
import type { DataTableColumn } from './data-table-features'

interface Row {
  id: string
  name: string
  role: string
}

const rows: Row[] = [
  { id: '1', name: 'Amina', role: 'Admin' },
  { id: '2', name: 'Daniel', role: 'User' },
]
const columns: DataTableColumn<Row>[] = [
  { id: 'name', accessorKey: 'name', header: 'Name' },
  { id: 'role', accessorKey: 'role', header: 'Role', enableSorting: false },
]

function SortableHarness({ onSort }: { onSort: (s: SortingState) => void }) {
  const [sorting, setSorting] = useState<SortingState>([])
  return (
    <DataTable
      caption="Users"
      columns={columns}
      rows={rows}
      getRowId={(r) => r.id}
      rowLabel={(r) => r.name}
      sorting={sorting}
      onSortingChange={(s) => {
        setSorting(s)
        onSort(s)
      }}
      paging={{ hasPrevious: false, hasNext: true, onPrevious: vi.fn(), onNext: vi.fn() }}
    />
  )
}

describe('DataTable', () => {
  it('renders rows, announces sorting with aria-sort and reports the sort to the caller', async () => {
    const user = userEvent.setup()
    const onSort = vi.fn()
    const { container } = renderWithProviders(<SortableHarness onSort={onSort} />)
    const table = screen.getByRole('table', { name: 'Users' })
    expect(within(table).getAllByRole('row')).toHaveLength(3)
    const nameHeader = within(table).getByRole('columnheader', { name: /Name/ })
    expect(nameHeader).toHaveAttribute('aria-sort', 'none')
    expect(within(table).getByRole('columnheader', { name: 'Role' })).not.toHaveAttribute('aria-sort')

    await user.click(within(nameHeader).getByRole('button', { name: 'Sort by Name' }))
    expect(onSort).toHaveBeenLastCalledWith([{ id: 'name', desc: false }])
    expect(nameHeader).toHaveAttribute('aria-sort', 'ascending')
    await expectNoA11yViolations(container)
  })

  it('selects all rows with one accessible checkbox', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SortableHarness onSort={vi.fn()} />)
    await user.click(screen.getByRole('checkbox', { name: 'Select all rows' }))
    expect(screen.getByRole('checkbox', { name: 'Select row Amina' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Select row Daniel' })).toBeChecked()
  })

  it('cursor pagination has Previous and Next only, Previous disabled on the first page', () => {
    renderWithProviders(<SortableHarness onSort={vi.fn()} />)
    const nav = screen.getByRole('navigation', { name: 'Pagination' })
    expect(
      within(nav)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Previous', 'Next'])
    expect(within(nav).getByRole('button', { name: 'Previous' })).toBeDisabled()
  })

  it('shows empty, loading and error states instead of rows', () => {
    const empty = renderWithProviders(
      <DataTable caption="Users" columns={columns} rows={[]} getRowId={(r) => r.id} empty={<p>nothing here</p>} />,
    )
    expect(screen.getByText('nothing here')).toBeInTheDocument()
    empty.unmount()
    const loading = renderWithProviders(
      <DataTable caption="Users" columns={columns} rows={rows} getRowId={(r) => r.id} state="loading" />,
    )
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument()
    loading.unmount()
    renderWithProviders(
      <DataTable
        caption="Users"
        columns={columns}
        rows={rows}
        getRowId={(r) => r.id}
        state="error"
        error={{ title: 'Could not load', correlationId: 'abc-123' }}
      />,
    )
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('Could not load')
    expect(alert).toHaveTextContent('Reference: abc-123')
  })
})

describe('Tables and narrow screens (WCAG 1.4.10)', () => {
  it('a DataTable scrolls in its own positioned box, which holds its visually hidden caption and headers', () => {
    const hidden: DataTableColumn<Row>[] = [
      ...columns,
      { id: 'actions', header: () => <span className="sr-only">Actions</span>, cell: () => null },
    ]
    renderWithProviders(<DataTable caption="Users" columns={hidden} rows={rows} getRowId={(r) => r.id} />)
    const table = screen.getByRole('table', { name: 'Users' })
    const box = table.parentElement
    expect(box).toHaveAttribute('data-table-scroll')
    expect(box).toHaveClass('relative', 'overflow-x-auto')
    expect(within(table).getByText('Actions').closest('[data-table-scroll]')).toBe(box)
  })

  it('every table in the application sits in such a box', () => {
    // A table outside a positioned scroll box lets an `sr-only` header (absolutely positioned) be placed against the
    // page, past its edge at 320 px, and the page scrolls sideways (found on the meeting page, Phase 4 E05 T10).
    const sources = import.meta.glob<string>(['/src/**/*.tsx', '!/src/**/*.test.tsx'], {
      query: '?raw',
      import: 'default',
      eager: true,
    })
    const unboxed = Object.entries(sources)
      .filter(([, source]) => /<table\b/.test(source))
      .filter(([, source]) => !/<TableScroll\b/.test(source) && !source.includes("'relative overflow-x-auto'"))
      .map(([path]) => path)
    expect(Object.keys(sources).length).toBeGreaterThan(100)
    expect(unboxed).toEqual([])
  })
})
