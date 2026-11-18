import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { StrictMode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { AgendaDraftResponse, AgendaMoveRequest } from '@/api/generated/types.gen'
import { renderWithProviders } from '@/test/render'
import { AgendaDnd, DragRow, SortableList } from './AgendaDnd'
import { dropMove, dropPlace } from './dnd'

const row = (id: string, title: string, subItems: AgendaDraftResponse['sections'][number]['items'] = []) => ({
  id,
  title,
  kind: 'ITEM' as const,
  late: 'NONE' as const,
  presenters: [],
  attendees: [],
  titleHidden: false,
  classificationId: 'l1',
  papers: [],
  outOfPack: [],
  subItems,
})

const sections: AgendaDraftResponse['sections'] = [
  {
    id: 'a',
    title: 'Opening',
    kind: 'STANDARD',
    membersOnly: false,
    receivesSubmissions: false,
    minutes: 0,
    items: [row('i1', 'Welcome'), row('i2', 'Declarations'), row('i3', 'Minutes', [row('i4', 'Matters arising')])],
  },
  {
    id: 'b',
    title: 'For decision',
    kind: 'STANDARD',
    membersOnly: false,
    receivesSubmissions: true,
    minutes: 0,
    items: [],
  },
]
const draft = { sections } as AgendaDraftResponse

describe('the drag layer (dnd-kit 6.3.1, sortable 10.0.0; research R1 spike)', () => {
  it('maps drops to the same moves the menu sends', () => {
    expect(dropMove(draft, 'i1', 'i3')).toEqual({ itemId: 'i1', toSectionId: 'a', toParentId: null, toPosition: 2 })
    expect(dropMove(draft, 'i3', 'i1')).toEqual({ itemId: 'i3', toSectionId: 'a', toParentId: null, toPosition: 0 })
    expect(dropMove(draft, 'i2', 'section:b')).toEqual({
      itemId: 'i2',
      toSectionId: 'b',
      toParentId: null,
      toPosition: 0,
    })
    // A change of level is a menu command, never a drop.
    expect(dropMove(draft, 'i1', 'i4')).toBeUndefined()
    expect(dropMove(draft, 'i4', 'section:b')).toBeUndefined()
    expect(dropMove(draft, 'i2', 'i2')).toBeUndefined()
    expect(dropPlace(draft, 'i2')).toEqual({ sectionId: 'a', position: 1 })
    expect(dropPlace(draft, 'i4')).toEqual({ sectionId: 'a', position: 3 })
    expect(dropPlace(draft, 'section:b')).toEqual({ sectionId: 'b', position: null })
  })

  it('moves a row with the keyboard under React StrictMode and says each step', async () => {
    // jsdom lays nothing out: each row is 40 px high, 50 px below the one before.
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
      const rows = [...document.querySelectorAll('[data-dnd-row]')]
      const index = rows.indexOf(this.closest('[data-dnd-row]')!)
      return new DOMRect(0, Math.max(0, index) * 50, 600, 40)
    })
    const errors = vi.spyOn(console, 'error')
    const onMove = vi.fn<(body: AgendaMoveRequest) => void>()
    const user = userEvent.setup()
    const items = sections[0]?.items ?? []
    renderWithProviders(
      <StrictMode>
        <AgendaDnd draft={draft} onMove={onMove} onPlace={vi.fn()}>
          <SortableList ids={items.map((i) => i.id)}>
            <ol>
              {items.map((i) => (
                <DragRow key={i.id} id={i.id} title={i.title} enabled>
                  {(handle) => (
                    <>
                      {handle}
                      {i.title}
                    </>
                  )}
                </DragRow>
              ))}
            </ol>
          </SortableList>
        </AgendaDnd>
      </StrictMode>,
    )

    const handle = screen.getByRole('button', { name: 'Drag to move: Welcome' })
    expect(handle).toHaveAccessibleDescription(/press Space, then the arrow keys/)
    handle.focus()
    await user.keyboard(' ')
    await user.keyboard('{ArrowDown}')
    await user.keyboard('{ArrowDown}')
    await user.keyboard(' ')
    expect(onMove).toHaveBeenCalledWith({ itemId: 'i1', toSectionId: 'a', toParentId: null, toPosition: 2 })
    // dnd-kit's live region says the drop in the reader's words.
    expect(document.body).toHaveTextContent('Welcome dropped at position 3 in Opening.')
    expect(errors).not.toHaveBeenCalled()
  })
})
