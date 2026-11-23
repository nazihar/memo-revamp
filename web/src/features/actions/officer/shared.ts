import { useQuery, type QueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { listForumMeetingsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { ActionResponse, ActionSummary, MeetingResponse } from '@/api/generated/types.gen'

/** The meeting statuses in which actions are raised from it (design §1.1 RAISE_WINDOW). */
export const RAISING: MeetingResponse['status'][] = ['IN_PROGRESS', 'HELD', 'MINUTES_APPROVED']

/** The meeting statuses with actions to show (from the start of the meeting). */
export const WITH_ACTIONS: MeetingResponse['status'][] = ['IN_PROGRESS', 'HELD', 'MINUTES_APPROVED', 'CLOSED']

/** If-Match on the action's version (its ETag). */
export function ifMatch(a: Pick<ActionResponse, 'version'>): { 'If-Match': string } {
  return { 'If-Match': `"${String(a.version)}"` }
}

/** Today in the reader's zone as `2026-11-23`. */
export function todayIso(): string {
  return isoOf(new Date())
}

export function isoOf(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${String(d.getFullYear())}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** A day [days] or [months] after today, as a date: the due date's quick picks. */
export function later({ days = 0, months = 0 }: { days?: number; months?: number }): string {
  const d = new Date()
  d.setMonth(d.getMonth() + months)
  d.setDate(d.getDate() + days)
  return isoOf(d)
}

/**
 * The committee's next meeting after [after] (drafted or scheduled), for "By the next meeting": its date on its own
 * clock, or undefined when none is planned.
 */
export function useNextMeeting(forumId: string, after?: string): { id: string; date: string } | undefined {
  const [now] = useState(() => Date.now())
  const meetings = useQuery({
    ...listForumMeetingsOptions({
      path: { forumId },
      query: { from: todayIso(), status: ['DRAFT', 'SCHEDULED'] },
    }),
    staleTime: 60_000,
  })
  const next = (meetings.data?.items ?? [])
    .filter((m) => m.id !== after && Date.parse(m.startsAt) > now)
    .sort((a, b) => a.startsAt.localeCompare(b.startsAt))[0]
  return next ? { id: next.id, date: next.localStartsAt.slice(0, 10) } : undefined
}

/** After a change of actions: the meeting's list, the tracker, My actions and the action itself are read again. */
export async function refreshActions(queryClient: QueryClient, actionId?: string): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: [{ _id: 'getMeetingActions' }] }),
    queryClient.invalidateQueries({ queryKey: [{ _id: 'getForumActions' }] }),
    queryClient.invalidateQueries({ queryKey: [{ _id: 'getMyActions' }] }),
    ...(actionId
      ? [
          queryClient.invalidateQueries({ queryKey: [{ _id: 'getAction', path: { actionId } }] }),
          queryClient.invalidateQueries({ queryKey: [{ _id: 'getActionEntries', path: { actionId } }] }),
        ]
      : []),
  ])
}

/** The person to write an update for: whoever holds the owner's task, else the person owner. */
export function ownerUserId(a: Pick<ActionResponse, 'owner'>): string | undefined {
  return a.owner?.holder?.id ?? a.owner?.person?.id ?? undefined
}

/** A summary is a draft not yet sent. */
export const isDraft = (a: Pick<ActionSummary, 'status'>) => a.status === 'DRAFT'
