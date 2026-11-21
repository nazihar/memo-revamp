import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useRef } from 'react'
import {
  recordMeetingArrivals,
  recordMeetingAttendance,
  setMeetingItemInProgress,
  voidMeetingAttendance,
} from '@/api/generated/sdk.gen'
import type { AttendanceEventRequest, RecordedAttendanceResponse } from '@/api/generated/types.gen'
import { newIdempotencyKey } from '@/lib/idempotency'
import { ifMatchRow, refreshAttendance, staleProblem, type Mode, type Person } from './words'

export type Entry = Omit<AttendanceEventRequest, 'userId'>

/**
 * The register's writes (design E05 §3), each with an `Idempotency-Key` kept while the same request is retried (a
 * dialog sent again after a network failure) and the person's row version as `If-Match`; after each, the register and
 * the live quorum are read again, and after a refusal that means the screen is out of date (409, 412, 428) too.
 */
export function useAttendanceWrites(meetingId: string) {
  const queryClient = useQueryClient()
  const sent = useRef<{ key: string; request: string } | null>(null)

  const keyFor = useCallback((request: unknown) => {
    const text = JSON.stringify(request)
    if (sent.current?.request !== text) sent.current = { key: newIdempotencyKey(), request: text }
    return sent.current.key
  }, [])

  const after = useCallback(
    async <T>(call: () => Promise<T>): Promise<T> => {
      try {
        const result = await call()
        sent.current = null
        await refreshAttendance(queryClient)
        return result
      } catch (problem) {
        if (staleProblem(problem)) await refreshAttendance(queryClient)
        throw problem
      }
    },
    [queryClient],
  )

  /** One entry for [person] at their row's [version] (default: the version on screen). */
  const record = useCallback(
    (person: Pick<Person, 'userId' | 'version'>, entry: Entry, version = person.version) =>
      after(async () => {
        const headers = ifMatchRow(version)
        const body = { ...entry, userId: person.userId }
        const { data } = await recordMeetingAttendance({
          path: { meetingId },
          headers: { 'Idempotency-Key': keyFor({ body, headers }), ...headers },
          body,
          throwOnError: true,
        })
        return data
      }),
    [after, keyFor, meetingId],
  )

  /** Voids [eventId] of [person] with [reason]; answers the person's row after it (for a correction recorded next). */
  const voidEntry = useCallback(
    (person: Pick<Person, 'version'>, eventId: string, reason: string): Promise<RecordedAttendanceResponse> =>
      after(async () => {
        const headers = ifMatchRow(person.version)
        const body = { reason }
        const { data } = await voidMeetingAttendance({
          path: { meetingId, eventId },
          headers: { 'Idempotency-Key': keyFor({ eventId, body, headers }), ...headers },
          body,
          throwOnError: true,
        })
        return data
      }),
    [after, keyFor, meetingId],
  )

  /** "Everyone who said yes is here": one request per way of attending, all or nothing each. */
  const arrivals = useCallback(
    (groups: { mode: Mode; userIds: string[] }[]) =>
      after(async () => {
        for (const group of groups) {
          if (!group.userIds.length) continue
          await recordMeetingArrivals({
            path: { meetingId },
            headers: { 'Idempotency-Key': keyFor(group) },
            body: group,
            throwOnError: true,
          })
          sent.current = null
        }
      }),
    [after, keyFor, meetingId],
  )

  /** "Now: item n". */
  const progress = useCallback(
    (itemId: string) =>
      after(async () => {
        const { data } = await setMeetingItemInProgress({
          path: { meetingId },
          headers: { 'Idempotency-Key': keyFor({ itemId }) },
          body: { itemId },
          throwOnError: true,
        })
        return data
      }),
    [after, keyFor, meetingId],
  )

  return { record, voidEntry, arrivals, progress }
}
