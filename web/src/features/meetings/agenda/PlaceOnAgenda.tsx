import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getAgendaDraftOptions,
  getAgendaDraftQueryKey,
  listForumMeetingsOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import { addAgendaItem } from '@/api/generated/sdk.gen'
import { Field } from '@/components/ui/field'
import { Select } from '@/components/ui/form-controls'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { meetingName } from '../shared'
import { notEnded } from '../time'
import { draftIfMatch } from './draft'
import { lateness, placeMessages, placeValues } from './place'
import { isProblem } from '@/api/http'
import { triageRefusal } from '@/features/forums/submissions'

/**
 * Accept and place (flow O6): the queue's Accept dialog gains "Place on the agenda of [meeting]". Choosing a meeting
 * reads its draft agenda and says, before anything is sent, what the committee's late rule means for the paper (to the
 * chair first, accepted as late, or not accepted); placing a queued submission accepts it in the same step.
 */
export function usePlaceOnAgenda(forumId: string, enabled: boolean) {
  const intl = useIntl()
  const terms = useTerms()
  const queryClient = useQueryClient()
  const meetings = useQuery({
    ...listForumMeetingsOptions({ path: { forumId }, query: { status: ['DRAFT', 'SCHEDULED', 'IN_PROGRESS'] } }),
    enabled,
  })
  const upcoming = (meetings.data?.items ?? [])
    .filter((m) => notEnded(m))
    .sort((a, b) => a.startsAt.localeCompare(b.startsAt))
  const [meetingId, setMeetingId] = useState('')
  const draft = useQuery({ ...getAgendaDraftOptions({ path: { meetingId } }), enabled: Boolean(meetingId) })
  const d = meetingId ? draft.data : undefined
  const late = d?.started ? lateness(intl, terms, d) : undefined
  const frozen = d?.started === true && d.changes === 'NONE'
  const blocked = Boolean(late?.blocked) || frozen
  /** Whether Accept goes through the agenda: a meeting is chosen and its agenda is started. */
  const chosen = Boolean(d?.started)

  const status = !meetingId
    ? undefined
    : draft.isPending
      ? intl.formatMessage(placeMessages.checking)
      : !d?.started
        ? intl.formatMessage(placeMessages.notStarted)
        : frozen
          ? intl.formatMessage(placeMessages.frozen)
          : late?.text

  const field = upcoming.length ? (
    <Field label={intl.formatMessage(placeMessages.field)} hint={intl.formatMessage(placeMessages.hint)}>
      {(p) => (
        <div className="flex flex-col gap-2">
          <Select
            {...p}
            value={meetingId}
            onChange={(e) => {
              setMeetingId(e.target.value)
            }}
          >
            <option value="">{intl.formatMessage(placeMessages.notNow)}</option>
            {upcoming.map((m, i) => {
              const date = intl.formatDate(m.startsAt, { dateStyle: 'medium', timeZone: m.timeZone })
              return (
                <option key={m.id} value={m.id}>
                  {i === 0
                    ? intl.formatMessage(placeValues.next, { date })
                    : intl.formatMessage(placeValues.other, { name: meetingName(m), date })}
                </option>
              )
            })}
          </Select>
          {status ? (
            <p role="status" className={blocked ? 'text-sm font-medium text-destructive' : 'text-sm'}>
              {status}
            </p>
          ) : null}
        </div>
      )}
    </Field>
  ) : null

  /** Places [submissionId] on the chosen meeting; answers a refusal in words, or nothing when it is placed. */
  const place = async (submissionId: string): Promise<string | undefined> => {
    if (!d) return undefined
    if (blocked) return late?.text ?? intl.formatMessage(placeMessages.frozen)
    try {
      const { data } = await addAgendaItem({
        path: { meetingId },
        headers: { ...draftIfMatch(d), 'Idempotency-Key': newIdempotencyKey() },
        body: { kind: 'SUBMISSION', submissionId },
        throwOnError: true,
      })
      queryClient.setQueryData(getAgendaDraftQueryKey({ path: { meetingId } }), data)
      return undefined
    } catch (problem) {
      await queryClient.invalidateQueries({ queryKey: getAgendaDraftQueryKey({ path: { meetingId } }) })
      if (isProblem(problem) && problem.reason === 'ONE_PLACEMENT') return intl.formatMessage(placeMessages.elsewhere)
      if (isProblem(problem) && problem.reason === 'PAPER_DEADLINE_PASSED') {
        return lateness(intl, terms, { ...d, lateMode: 'BLOCK', deadlinePassed: true })?.text
      }
      return triageRefusal(intl, terms, problem)
    }
  }

  return {
    field: enabled ? field : null,
    chosen,
    place,
    done: intl.formatMessage(placeMessages.done),
    submitLabel: intl.formatMessage(placeMessages.acceptPlace),
  }
}
