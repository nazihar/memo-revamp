import { useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { decideConsentSection } from '@/api/generated/sdk.gen'
import type { DecisionResponse, MeetingResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { FormDialog } from '@/features/forums/FormDialog'
import { instantOf, joinLocal } from '@/features/meetings/time'
import { newIdempotencyKey } from '@/lib/idempotency'
import { dm, dmv } from './messages'
import {
  consentOutcome,
  decisionRefusal,
  numbers,
  refreshDecisions,
  type OutcomeChoice,
  type PanelItem,
} from './shared'

/**
 * "Decide the consent items together" (flow AA1): what each remaining item of the section will be ("Item 7 will be
 * Noted"), which were lifted for discussion and are decided on their own, which already have a decision; once the
 * meeting is held, when the section was taken. One action, one decision per item (design §1.1). Refusals in words
 * (`NOT_QUORATE` for the whole section, `NOTHING_TO_DECIDE`, `RECUSED`); a section with minutes under review asks to send
 * them back to the draft first.
 */
export function ConsentDialog({
  meeting,
  sectionId,
  items,
  decisions,
  outcomes,
  minutesInReview,
  onClose,
}: {
  meeting: MeetingResponse
  sectionId: string
  items: PanelItem[]
  decisions: Map<string, DecisionResponse>
  outcomes: OutcomeChoice[]
  minutesInReview: boolean
  onClose: () => void
}) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const key = useRef(newIdempotencyKey())
  const [time, setTime] = useState('')
  const [returnToDraft, setReturnToDraft] = useState(minutesInReview)
  const held = meeting.status !== 'IN_PROGRESS'
  const section = items.filter((i) => i.sectionId === sectionId)
  const lifted = section.filter((i) => i.lifted)
  const decided = section.filter((i) => !i.lifted && decisions.has(i.id))
  const remaining = section.filter((i) => !i.lifted && !decisions.has(i.id))
  // Grouped by the outcome each will get: "Items 7 and 9 will be Noted."
  const groups = new Map<string, PanelItem[]>()
  for (const item of remaining) {
    const label = consentOutcome(outcomes, item)?.label ?? ''
    groups.set(label, [...(groups.get(label) ?? []), item])
  }

  const submit = async (): Promise<string | undefined> => {
    if (!remaining.length) return intl.formatMessage(dm.consentNothing)
    const decidedAt =
      held && time ? instantOf(joinLocal(meeting.localStartsAt.slice(0, 10), time), meeting.timeZone) : undefined
    try {
      await decideConsentSection({
        path: { meetingId: meeting.id, sectionId },
        body: { ...(decidedAt ? { decidedAt } : {}), ...(returnToDraft ? { returnMinutesToDraft: true } : {}) },
        headers: { 'Idempotency-Key': key.current },
        throwOnError: true,
      })
      toast.show(intl.formatMessage(dm.consentDone), 'success')
      await refreshDecisions(queryClient, meeting.id)
      onClose()
      return undefined
    } catch (problem) {
      key.current = newIdempotencyKey()
      await refreshDecisions(queryClient, meeting.id)
      if (isProblem(problem) && problem.reason === 'MINUTES_IN_REVIEW') {
        setReturnToDraft(true)
        return intl.formatMessage(dm.minutesInReview)
      }
      if (isProblem(problem) && problem.status === 422) {
        const code = problem.errors?.[0]?.code
        return code === 'TIME_REQUIRED' || code === 'TIME_OUT_OF_RANGE'
          ? intl.formatMessage(dm[code])
          : intl.formatMessage(dm.invalid)
      }
      return decisionRefusal(intl, problem)
    }
  }

  return (
    <FormDialog
      large
      title={intl.formatMessage(dm.decideConsentTitle)}
      submitLabel={intl.formatMessage(dm.decideConsentSubmit)}
      cancelLabel={intl.formatMessage(dm.cancel)}
      onSubmit={submit}
      onClose={onClose}
    >
      <div className="flex flex-col gap-2 text-base">
        {remaining.length ? (
          [...groups].map(([outcome, group]) => (
            <p key={outcome}>
              {intl.formatMessage(dmv.consentWill, { items: numbers(intl, group), outcome, count: group.length })}
            </p>
          ))
        ) : (
          <p>{intl.formatMessage(dm.consentNothing)}</p>
        )}
        {lifted.length ? (
          <p>{intl.formatMessage(dmv.consentLifted, { items: numbers(intl, lifted), count: lifted.length })}</p>
        ) : null}
        {decided.length ? (
          <p>{intl.formatMessage(dmv.consentDecided, { items: numbers(intl, decided), count: decided.length })}</p>
        ) : null}
        {returnToDraft ? <p>{intl.formatMessage(dm.minutesInReview)}</p> : null}
      </div>
      {held && remaining.length ? (
        <Field
          label={intl.formatMessage(dm.whenTaken)}
          hint={intl.formatMessage(dm.whenTakenHint)}
          className="max-w-xs"
        >
          {(p) => (
            <Input
              {...p}
              type="time"
              className="min-h-11"
              value={time}
              onChange={(e) => {
                setTime(e.target.value)
              }}
            />
          )}
        </Field>
      ) : null}
    </FormDialog>
  )
}
