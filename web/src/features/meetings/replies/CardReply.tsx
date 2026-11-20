import { useId, useState } from 'react'
import { useIntl } from 'react-intl'
import type { MeetingSummaryResponse, MyRsvpResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { useToast } from '@/components/ui/toast-context'
import { useTerms } from '@/features/terms/terms'
import { ApologiesDialog } from './ApologiesDialog'
import { rm, rmv } from './messages'
import { useAnswer, useMyAnswer } from './useAnswer'
import { answerRefusal, offeredFor, type Answer } from './words'

/**
 * The answer on a "My meetings" card (flow V6): "You said: Yes, in person", "You sent your apologies", "Please reply" or
 * "Reply again: the time changed"; while the meeting may be answered and has no current answer, the answers its mode
 * offers as buttons ([titleId] names the meeting for them). A yes is saved at once; apologies open their dialog with the
 * alternate (flow V3). Changing an answer is on the meeting page.
 */
export function CardReply({ meeting: m, titleId }: { meeting: MeetingSummaryResponse; titleId: string }) {
  const intl = useIntl()
  const terms = useTerms()
  const toast = useToast()
  const answer = useAnswer(m.id)
  const loadMine = useMyAnswer(m.id)
  const questionId = useId()
  const [busy, setBusy] = useState(false)
  const [refusal, setRefusal] = useState<string>()
  const [apologizing, setApologizing] = useState<MyRsvpResponse>()
  const r = m.rsvp
  if (!r || m.status === 'DRAFT' || m.status === 'CANCELLED') return null
  const asking = r.canAnswer && (!r.answer || r.stale)
  const line =
    r.answer && !r.stale
      ? r.answer === 'APOLOGIES'
        ? intl.formatMessage(rm.sentApologies)
        : intl.formatMessage(rmv.youSaid, { answer: intl.formatMessage(rm[r.answer]) })
      : !r.canAnswer
        ? null
        : r.stale
          ? intl.formatMessage(rm.replyAgain)
          : intl.formatMessage(rm.pleaseReply)
  if (!line) return null

  const choose = async (choice: Answer) => {
    setBusy(true)
    setRefusal(undefined)
    try {
      if (choice === 'APOLOGIES') {
        setApologizing(await loadMine())
        return
      }
      const result = await answer({ answer: choice })
      if (result.refusal !== undefined) setRefusal(result.refusal)
      else toast.show(intl.formatMessage(rm.saved), 'success')
    } catch (problem) {
      setRefusal(answerRefusal(intl, terms, problem))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <p className={asking ? 'font-medium' : undefined}>{line}</p>
      {asking ? (
        <>
          <span id={questionId} className="sr-only">
            {intl.formatMessage(rm.title)}
          </span>
          <div role="group" aria-labelledby={`${questionId} ${titleId}`} className="flex flex-wrap gap-2">
            {offeredFor(m.mode).map((choice) => (
              <Button
                key={choice}
                variant="outline"
                className="h-auto min-h-11 whitespace-normal text-base"
                disabled={busy}
                onClick={() => void choose(choice)}
              >
                {intl.formatMessage(rm[choice])}
              </Button>
            ))}
          </div>
        </>
      ) : null}
      {refusal ? (
        <p role="alert" className="text-sm text-destructive">
          {refusal}
        </p>
      ) : null}
      {apologizing ? (
        <ApologiesDialog
          mine={apologizing}
          onSend={async (alternateAttends, note) => {
            const result = await answer({ answer: 'APOLOGIES', alternateAttends, note }, apologizing)
            if (result.refusal !== undefined) return result.refusal
            const alternate = result.mine.alternateStandingIn ? result.mine.alternate?.name : undefined
            toast.show(
              alternate
                ? intl.formatMessage(rmv.alternateAsked, { name: alternate })
                : intl.formatMessage(rm.apologiesSaved),
              'success',
            )
            setApologizing(undefined)
            return undefined
          }}
          onClose={() => {
            setApologizing(undefined)
          }}
        />
      ) : null}
    </div>
  )
}
