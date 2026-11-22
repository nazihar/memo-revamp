import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useCallback, useRef } from 'react'
import { getMeetingAgendaOptions, getMinutesOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { MinutesResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { refreshMeetings } from '@/features/meetings/shared'
import { newIdempotencyKey } from '@/lib/idempotency'
import { staleProblem, titlesOf, type Minutes, type MinutesItem } from './words'

/** The page is read again this often while the draft is being made or a saved version is being checked (design T5). */
export const POLL_MS = 2_000

/** Whether the server is still working on the draft: a request in progress, or the current version not checked yet. */
export function working(page: MinutesResponse | undefined): boolean {
  return page?.work?.state === 'PENDING' || page?.structure.pending === true
}

/** A meeting's minutes as the viewer reads them (404 to someone who does not read them: not retried). */
export function useMinutes(meetingId: string, enabled = true) {
  return useQuery({
    ...getMinutesOptions({ path: { meetingId } }),
    enabled,
    retry: false,
    refetchInterval: (query) => (working(query.state.data) ? POLL_MS : false),
  })
}

/** Whether a failed read means "not for you" (404, 403) rather than an outage. */
export function notForMe(error: unknown): boolean {
  return isProblem(error) && (error.status === 404 || error.status === 403)
}

const MINUTES_QUERIES = new Set([
  'getMinutes',
  'listMinutesComments',
  'listMinutesCorrections',
  'listMinutesRecords',
  'listMinutesApprovals',
  'getMeetingAgenda',
])

/** Reads the minutes, their comments, corrections, record and stamps again, and the meetings ("My meetings", status). */
export async function refreshMinutes(queryClient: QueryClient): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({
      predicate: (query) => MINUTES_QUERIES.has((query.queryKey[0] as { _id?: string } | undefined)?._id ?? ''),
    }),
    refreshMeetings(queryClient),
  ])
}

/**
 * Runs the minutes' writes (design E06 §3): each with an `Idempotency-Key` kept while the same request is retried (a
 * dialog sent again after a failure), then reads the minutes again; a refusal that means the page is out of date (409,
 * 412, 428) reads them again too. [refresh] false leaves the reading to the caller (the reviewer who sent the draft back
 * reads the page no more).
 */
export function useMinutesWrite() {
  const queryClient = useQueryClient()
  const sent = useRef<{ key: string; request: string } | null>(null)
  return useCallback(
    async <T>(request: unknown, call: (key: string) => Promise<T>, refresh = true): Promise<T> => {
      const text = JSON.stringify(request)
      if (sent.current?.request !== text) sent.current = { key: newIdempotencyKey(), request: text }
      try {
        const result = await call(sent.current.key)
        sent.current = null
        if (refresh) await refreshMinutes(queryClient)
        return result
      } catch (problem) {
        if (staleProblem(problem)) await refreshMinutes(queryClient)
        throw problem
      }
    },
    [queryClient],
  )
}

/** The minuted items with their titles where the agenda shows them. */
export function useMinutesItems(page: Minutes | undefined, meetingId: string): MinutesItem[] {
  const agenda = useQuery({
    ...getMeetingAgendaOptions({ path: { meetingId } }),
    enabled: Boolean(page),
    retry: false,
  })
  const titles = titlesOf(agenda.data)
  return (page?.items ?? []).map((i) => ({ ...i, title: titles.get(i.itemId) ?? null }))
}
