/** How long, and how often, the builder waits for a removed row to leave the page (ms). */
const WAIT_FOR = 3_000
const WAIT_EVERY = 50

/** The id of a paper's ⋯ button in the item sheet. */
export function paperMenuId(paperId: string): string {
  return `agenda-paper-menu-${paperId}`
}

/** The id of the line that heads an item's papers in the item sheet (focusable from script only). */
export function papersLeadId(itemId: string): string {
  return `agenda-papers-${itemId}`
}

/** The ids of the entries before and after [id] in [order]: the next one first, then the previous one. */
export function neighbours(order: string[], id: string): string[] {
  const at = order.indexOf(id)
  if (at < 0) return []
  return [order[at + 1], order[at - 1]].filter((x): x is string => x !== undefined)
}

/**
 * Once the element with [goneId] has left the page (the server answered a removal and the draft was drawn again), puts
 * focus on the first of [candidates] (element ids) still there, else on [fallbackId]. The control that opened the
 * confirmation was in the removed row, so focus would otherwise fall to the page (Phase 4 E03 T9). Focus the person has
 * moved elsewhere meanwhile is left alone; nothing happens when the row is still there after [WAIT_FOR] (a refusal).
 */
export function focusWhenGone(goneId: string, candidates: string[], fallbackId: string): void {
  let waited = 0
  const timer = window.setInterval(() => {
    waited += WAIT_EVERY
    if (!document.getElementById(goneId)) {
      window.clearInterval(timer)
      const active = document.activeElement
      const lost = !active || active === document.body || !active.isConnected
      if (!lost) return
      const target = [...candidates, fallbackId].map((id) => document.getElementById(id)).find((el) => el !== null)
      target?.focus()
    } else if (waited >= WAIT_FOR) {
      window.clearInterval(timer)
    }
  }, WAIT_EVERY)
}
