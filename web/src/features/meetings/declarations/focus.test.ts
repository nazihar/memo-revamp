import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ITEM_BUTTON, PANEL_TITLE_ID } from './context'
import { restoreFocus } from './focus'

/**
 * Focus after a declaration sheet closes (Phase 4 E04 T9): the journey found it falling to the page, because the agenda
 * reloads and draws the item's line anew after the sheet has given focus back.
 */
function button(item?: string): HTMLButtonElement {
  const b = document.createElement('button')
  if (item) b.setAttribute(ITEM_BUTTON, item)
  document.body.append(b)
  return b
}

describe('restoreFocus', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    const heading = document.createElement('h2')
    heading.id = PANEL_TITLE_ID
    heading.tabIndex = -1
    document.body.append(heading)
  })
  afterEach(() => {
    vi.useRealTimers()
    document.body.replaceChildren()
  })

  it('follows the item’s button when its line is drawn anew after the sheet closed', () => {
    const opener = button('i5')
    restoreFocus(opener)
    expect(document.activeElement).toBe(opener)
    // The agenda reloads: the old button leaves, focus falls to the page, and the new one arrives later.
    opener.remove()
    vi.advanceTimersByTime(100)
    expect(document.activeElement?.id).toBe(PANEL_TITLE_ID)
    const again = button('i5')
    vi.advanceTimersByTime(100)
    expect(document.activeElement).toBe(again)
  })

  it('goes to the panel when an opener without an item is gone, and leaves focus the person moved', () => {
    const opener = button()
    opener.remove()
    restoreFocus(opener)
    expect(document.activeElement?.id).toBe(PANEL_TITLE_ID)

    const item = button('i6')
    restoreFocus(item)
    const elsewhere = button()
    elsewhere.focus()
    item.remove()
    button('i6')
    vi.advanceTimersByTime(500)
    expect(document.activeElement).toBe(elsewhere)
  })
})
