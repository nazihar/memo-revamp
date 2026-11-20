import { useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { useIntl, type IntlShape } from 'react-intl'
import { getMeetingInvitationsQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { resendMeetingInvitations } from '@/api/generated/sdk.gen'
import type { InvitationsResponse, InviteeResponse } from '@/api/generated/types.gen'
import { isProblem, PROBLEM } from '@/api/http'
import { useToast } from '@/components/ui/toast-context'
import { FormDialog } from '@/features/forums/FormDialog'
import { newIdempotencyKey } from '@/lib/idempotency'
import { rm, rmv } from './messages'

type Who = 'notReplied' | 'everyone' | 'only'

/** Who holds the invitation now (only they can get it again). */
const holding = (people: InviteeResponse[]) => people.filter((p) => p.state === 'INVITED')

/** Those holding it who have not answered for the meeting's current time. */
const notReplied = (people: InviteeResponse[]) => holding(people).filter((p) => !p.reply?.answer || p.reply.stale)

/**
 * "The invitation was sent to M. Hossain 4 minutes ago. You can resend it after 10:46." from the panel as it is now:
 * the people of [targets] whose 10 minutes have not passed, and the latest time one of them can get it again.
 */
function tooSoon(intl: IntlShape, panel: InvitationsResponse | undefined, targets: string[]): string {
  const now = Date.now()
  const waiting = (panel?.people ?? []).filter(
    (p) => targets.includes(p.userId) && p.resendFrom && Date.parse(p.resendFrom) > now,
  )
  const last = waiting
    .map((p) => p.resendFrom ?? '')
    .sort()
    .at(-1)
  if (!last) return intl.formatMessage(rm.RESEND_TOO_SOON)
  const at = intl.formatTime(last, { hour: 'numeric', minute: '2-digit' })
  const only = waiting.length === 1 ? waiting[0] : undefined
  return only
    ? intl.formatMessage(rmv.tooSoonOne, { name: only.name, at })
    : intl.formatMessage(rmv.tooSoonMany, { count: waiting.length, at })
}

/**
 * "Resend the current invitation to: (•) everyone who has not replied (2) ( ) everyone (8) ( ) M. Hossain only" (flow
 * W3). `POST /meetings/{id}/invitations/resend` with an `Idempotency-Key`; refused for anyone sent a message in the last
 * 10 minutes, said with their name and the time from which it can be sent again.
 */
export function ResendDialog({
  meetingId,
  people,
  only,
  onClose,
}: {
  meetingId: string
  people: InviteeResponse[]
  only?: InviteeResponse
  onClose: () => void
}) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const pending = notReplied(people)
  const everyone = holding(people)
  const [who, setWho] = useState<Who>(only ? 'only' : pending.length ? 'notReplied' : 'everyone')
  const sent = useRef<{ key: string; request: string } | null>(null)
  const panelKey = getMeetingInvitationsQueryKey({ path: { meetingId } })

  const targets = (): string[] =>
    who === 'only' && only ? [only.userId] : (who === 'notReplied' ? pending : everyone).map((p) => p.userId)

  const submit = async (): Promise<string | undefined> => {
    const userIds = targets()
    if (!userIds.length) return intl.formatMessage(rm.resendNobody)
    const body = who === 'everyone' ? {} : { userIds }
    const request = JSON.stringify(body)
    if (sent.current?.request !== request) sent.current = { key: newIdempotencyKey(), request }
    try {
      const { data } = await resendMeetingInvitations({
        path: { meetingId },
        headers: { 'Idempotency-Key': sent.current.key },
        body,
        throwOnError: true,
      })
      sent.current = null
      queryClient.setQueryData(panelKey, data)
      toast.show(intl.formatMessage(rmv.resent, { count: userIds.length }), 'success')
      onClose()
      return undefined
    } catch (problem) {
      if (!isProblem(problem)) return intl.formatMessage(rm.failed)
      if (problem.reason === 'RESEND_TOO_SOON') {
        await queryClient.invalidateQueries({ queryKey: panelKey })
        return tooSoon(intl, queryClient.getQueryData<InvitationsResponse>(panelKey), userIds)
      }
      if (problem.type === PROBLEM.rateLimited || problem.status === 429) return intl.formatMessage(rm.rateLimited)
      if (problem.status === 409 || problem.status === 422) {
        await queryClient.invalidateQueries({ queryKey: panelKey })
        return intl.formatMessage(rm.resendWrongState)
      }
      if (problem.status === 403) return intl.formatMessage(rm.forbidden)
      return intl.formatMessage(rm.failed)
    }
  }

  const choices: { value: Who; label: string; count: number }[] = [
    ...(only
      ? [{ value: 'only' as const, label: intl.formatMessage(rmv.resendOnly, { name: only.name }), count: 1 }]
      : []),
    {
      value: 'notReplied',
      label: intl.formatMessage(rmv.resendNotReplied, { count: pending.length }),
      count: pending.length,
    },
    {
      value: 'everyone',
      label: intl.formatMessage(rmv.resendEveryone, { count: everyone.length }),
      count: everyone.length,
    },
  ]

  return (
    <FormDialog
      title={only ? intl.formatMessage(rmv.resendTitle, { name: only.name }) : intl.formatMessage(rm.resendSubmit)}
      submitLabel={intl.formatMessage(rm.resendSubmit)}
      cancelLabel={intl.formatMessage(rm.cancel)}
      onSubmit={submit}
      onClose={onClose}
    >
      <fieldset className="flex flex-col gap-1">
        <legend className="mb-1 text-sm font-medium">{intl.formatMessage(rm.resendWho)}</legend>
        {choices.map((c) => (
          <label key={c.value} className="flex min-h-11 cursor-pointer items-center gap-3 text-base">
            <input
              type="radio"
              name="resend-who"
              className="size-5 shrink-0 accent-primary"
              checked={who === c.value}
              disabled={c.count === 0}
              onChange={() => {
                setWho(c.value)
              }}
            />
            {c.label}
          </label>
        ))}
      </fieldset>
    </FormDialog>
  )
}
