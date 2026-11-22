import { useQuery } from '@tanstack/react-query'
import { createContext, useContext, type ReactNode } from 'react'
import { getMeetingDecisionsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { DecisionResponse, MemberAgendaResponse } from '@/api/generated/types.gen'
import { DecisionText } from './DecisionText'
import { WITH_DECISIONS } from './shared'

interface Known {
  byItem: Map<string, DecisionResponse>
  timeZone: string
}

const DecisionsContext = createContext<Known | null>(null)

/**
 * The meeting's decisions as the reader may see them (flow AB, design §1.9), for the agenda on the meeting page: read once
 * the meeting has started. The server leaves out what the reader may not see (a hidden item's decision; the vote and
 * names for a reader recused from the item).
 */
export function DecisionsProvider({
  agenda,
  children,
}: {
  agenda: MemberAgendaResponse | undefined
  children: ReactNode
}) {
  const enabled = agenda != null && !agenda.preview && WITH_DECISIONS.includes(agenda.status)
  const decisions = useQuery({
    ...getMeetingDecisionsOptions({ path: { meetingId: agenda?.meetingId ?? '' } }),
    enabled,
    retry: false,
  })
  const value: Known | null =
    enabled && decisions.data
      ? {
          byItem: new Map(decisions.data.decisions.map((d) => [d.itemId, d])),
          timeZone: agenda.timeZone,
        }
      : null
  return <DecisionsContext.Provider value={value}>{children}</DecisionsContext.Provider>
}

/** The item's decision under its line on the agenda (flow AB1); nothing before the meeting or without one. */
export function ItemDecisionLine({ itemId }: { itemId: string }) {
  const known = useContext(DecisionsContext)
  const decision = known?.byItem.get(itemId)
  if (!known || !decision) return null
  return (
    <div className="rounded-md border-s-4 border-status-ok-fg/60 bg-muted/30 py-2 ps-3 pe-2">
      <DecisionText decision={decision} timeZone={known.timeZone} />
    </div>
  )
}
