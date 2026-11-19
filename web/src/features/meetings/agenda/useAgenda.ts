import { useQueryClient } from '@tanstack/react-query'
import { createContext, useContext } from 'react'
import { useIntl } from 'react-intl'
import { getAgendaDraftQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import type { ClassificationLevel, MeetingResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { useTerms } from '@/features/terms/terms'
import { draftIfMatch, type Draft } from './draft'
import { agendaFieldProblems, agendaRefusal } from './problems'

/** The answer of a change: the new draft, or the refusal in words with the field problems. */
export type Outcome =
  | { draft: Draft; refusal?: undefined; fields?: undefined }
  | { draft?: undefined; refusal: string; fields: Record<string, string>; problem: unknown }

/** Queries that read what a builder change can alter: the tray, the publish preview, the preview, the queue. */
const RELATED = new Set([
  'listReadySubmissions',
  'previewAgendaPublication',
  'previewAgendaAs',
  'listForumSubmissions',
  'getForumSubmission',
  'listMemoForumSubmissions',
])

/**
 * Changes of the draft agenda (design E03 §3): every call sends the agenda's current version as If-Match and answers
 * the whole draft, which replaces the one shown. A stale version (412, someone else changed it) reloads the draft and
 * says so; any other refusal comes back in words.
 */
export function useAgendaChange(meetingId: string) {
  const intl = useIntl()
  const terms = useTerms()
  const queryClient = useQueryClient()
  const key = getAgendaDraftQueryKey({ path: { meetingId } })

  const current = () => queryClient.getQueryData<Draft>(key)

  const change = async (call: (headers: { 'If-Match': string }) => Promise<{ data: Draft }>): Promise<Outcome> => {
    const draft = current()
    try {
      const { data } = await call(draftIfMatch(draft ?? { version: 0 }))
      queryClient.setQueryData(key, data)
      void queryClient.invalidateQueries({
        predicate: (q) => RELATED.has((q.queryKey[0] as { _id?: string } | undefined)?._id ?? ''),
      })
      return { draft: data }
    } catch (problem) {
      if (isProblem(problem) && (problem.status === 412 || problem.status === 409)) {
        await queryClient.invalidateQueries({ queryKey: key })
      }
      return {
        refusal: agendaRefusal(intl, terms, problem, draft),
        fields: agendaFieldProblems(intl, terms, problem),
        problem,
      }
    }
  }

  return { change, current }
}

/** A person who may be named in a restriction, a preview or a presenter list: someone in the meeting's audience. */
export interface Person {
  id: string
  name: string
}

/** What every part of the builder reads: the meeting, the draft, what may change, the audience and the levels. */
export interface AgendaContextValue {
  meeting: MeetingResponse
  draft: Draft
  /** Sections, items, moves, details: a secretary while the meeting has not started. */
  editable: boolean
  /** Items and papers may be added (also while the meeting is in progress, as tabled). */
  canAdd: boolean
  audience: Person[]
  levels: ClassificationLevel[]
  change: ReturnType<typeof useAgendaChange>['change']
  /** Says [text] in the page's live region (moves, additions). */
  announce: (text: string) => void
  /** Opens the item's side sheet on a tab. */
  openSheet: (itemId: string, tab?: SheetTab) => void
}

export type SheetTab = 'details' | 'papers' | 'access'

export const AgendaContext = createContext<AgendaContextValue | null>(null)

export function useAgenda(): AgendaContextValue {
  const value = useContext(AgendaContext)
  if (!value) throw new Error('useAgenda must be used inside the agenda builder')
  return value
}

/** The id of an item's Move-menu button, where focus returns after a move (the row may have been re-rendered elsewhere). */
export function menuId(itemId: string): string {
  return `agenda-menu-${itemId}`
}

/** Puts focus on the item's menu button once the moved row has rendered. */
export function focusRow(itemId: string): void {
  window.setTimeout(() => {
    document.getElementById(menuId(itemId))?.focus()
  }, 0)
}
