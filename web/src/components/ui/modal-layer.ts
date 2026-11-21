import { createContext } from 'react'

/**
 * The open modal `<dialog>` a menu is opened inside (ADR-0021). The browser makes everything outside a modal dialog
 * inert, so a menu portalled to the document body would be shown under the dialog and could be neither clicked nor
 * reached with the keyboard; menus portal into this element instead (found in Phase 4 E03 T9: the paper actions of the
 * agenda builder's item sheet).
 */
export const ModalLayer = createContext<HTMLElement | null>(null)
