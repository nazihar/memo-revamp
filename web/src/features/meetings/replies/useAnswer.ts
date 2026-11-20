import { useQueryClient } from '@tanstack/react-query'
import { useRef } from 'react'
import { useIntl } from 'react-intl'
import { getMyMeetingRsvpOptions, getMyMeetingRsvpQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { answerMeetingRsvp } from '@/api/generated/sdk.gen'
import type { MyRsvpRequest, MyRsvpResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { answerRefusal, ifMatchVersion, refreshReplies } from './words'

export type AnswerResult = { mine: MyRsvpResponse; refusal?: undefined } | { mine?: undefined; refusal: string }

/** Loads the viewer's own answer to [meetingId] afresh (for its version and alternate) before answering from a card. */
export function useMyAnswer(meetingId: string) {
  const queryClient = useQueryClient()
  return () => queryClient.query(getMyMeetingRsvpOptions({ path: { meetingId } }))
}

/**
 * Answers "Will you attend?" for the viewer (`PUT /meetings/{id}/rsvp/mine`): with an `Idempotency-Key`, kept while the
 * same request is retried after a lost answer, and once an answer exists its version as `If-Match`. A stale version
 * (412, or 428) or a refusal reloads the answer, the meeting and "My meetings", and comes back in words.
 */
export function useAnswer(meetingId: string) {
  const intl = useIntl()
  const terms = useTerms()
  const queryClient = useQueryClient()
  const sent = useRef<{ key: string; request: string } | null>(null)
  const current = useMyAnswer(meetingId)

  return async (body: MyRsvpRequest, known?: MyRsvpResponse): Promise<AnswerResult> => {
    let mine: MyRsvpResponse
    try {
      mine = known ?? (await current())
    } catch (problem) {
      return { refusal: answerRefusal(intl, terms, problem) }
    }
    const headers = ifMatchVersion(mine.version)
    const request = JSON.stringify({ body, headers })
    if (sent.current?.request !== request) sent.current = { key: newIdempotencyKey(), request }
    try {
      const { data } = await answerMeetingRsvp({
        path: { meetingId },
        headers: { 'Idempotency-Key': sent.current.key, ...headers },
        body,
        throwOnError: true,
      })
      sent.current = null
      queryClient.setQueryData(getMyMeetingRsvpQueryKey({ path: { meetingId } }), data)
      void refreshReplies(queryClient, false)
      return { mine: data }
    } catch (problem) {
      if (isProblem(problem) && problem.status !== undefined && problem.status >= 400 && problem.status < 500) {
        await refreshReplies(queryClient, true)
      }
      return {
        refusal: answerRefusal(intl, terms, problem, { ...(mine.alternate ? { alternate: mine.alternate.name } : {}) }),
      }
    }
  }
}
