import { ITEM_BUTTON, PANEL_TITLE_ID } from './context'

/** How long, and how often, focus is watched while the agenda reloads after a change (ms). */
const FOLLOW_FOR = 3_000
const FOLLOW_EVERY = 50

/** The watch of the last sheet that closed: a newer one replaces it, so two never pull focus against each other. */
let following: ReturnType<typeof setInterval> | undefined

/**
 * Gives focus back to [opener]; when it has gone (its line changed), to the Declarations panel or the main content.
 *
 * The agenda and the reader's declarations are read again after every change, and an item's line can be drawn anew
 * while they arrive, so its button is briefly replaced. An item's button carries [ITEM_BUTTON]: while the reload
 * settles, focus that fell to the page (or to the panel's heading in its place) moves to the item's new button, and to
 * the panel only when there is none. Focus the person moved elsewhere is left alone. (Phase 4 E04 T9: the journey found
 * focus falling to the page after declaring, which the unit tests' instant answers did not show.)
 */
export function restoreFocus(opener: HTMLElement | null): void {
  window.clearInterval(following)
  const heading = () => document.getElementById(PANEL_TITLE_ID) ?? document.querySelector<HTMLElement>('main')
  const key = opener?.getAttribute(ITEM_BUTTON) ?? null
  const button = () => (key ? document.querySelector<HTMLElement>(`[${ITEM_BUTTON}="${CSS.escape(key)}"]`) : null)
  let placed: HTMLElement | null = null
  const lost = () => {
    const active = document.activeElement
    return !active || active === document.body || !active.isConnected
  }
  const settle = () => {
    placed = button() ?? heading()
    placed?.focus()
  }
  if (opener?.isConnected) {
    opener.focus()
    placed = opener
  } else {
    settle()
  }
  let waited = 0
  following = window.setInterval(() => {
    waited += FOLLOW_EVERY
    if (lost()) settle()
    // Focus is still where this call put it in the button's place: the button is back.
    else if (key && document.activeElement === placed && placed === heading()) settle()
    else if (document.activeElement !== placed) window.clearInterval(following)
    if (waited >= FOLLOW_FOR) window.clearInterval(following)
  }, FOLLOW_EVERY)
}
