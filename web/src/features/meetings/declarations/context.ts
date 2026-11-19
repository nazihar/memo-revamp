import { createContext, useContext } from 'react'
import type { DeclarationResponse, MyDeclarationsResponse } from '@/api/generated/types.gen'

/** The id of the Declarations panel's heading: focus goes there when the control that opened a sheet is gone. */
export const PANEL_TITLE_ID = 'declarations-title'

export interface DeclarationsValue {
  meetingId: string
  timeZone: string
  startsAt: string
  /** The meeting is in the window where members declare and confirm (SCHEDULED, IN_PROGRESS). */
  open: boolean
  /** The reader's declarations and the items to review; undefined while loading or when it could not be read. */
  mine: MyDeclarationsResponse | undefined
  declare: (item: { itemId: string; name: string }, opener: HTMLElement | null) => void
  show: (declaration: DeclarationResponse, name: string, opener: HTMLElement | null) => void
  review: (opener: HTMLElement | null) => void
}

export const DeclarationsContext = createContext<DeclarationsValue | null>(null)

/** Declaring on this meeting page; null outside it, in a preview, or for a `forum.read` holder outside the audience. */
export function useDeclarations(): DeclarationsValue | null {
  return useContext(DeclarationsContext)
}
