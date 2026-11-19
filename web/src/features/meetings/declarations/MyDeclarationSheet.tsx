import { useRef, useState } from 'react'
import { useIntl, type IntlShape } from 'react-intl'
import { changeDeclarationIntent, withdrawDeclaration } from '@/api/generated/sdk.gen'
import type { DeclarationResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { FormDialog } from '@/features/forums/FormDialog'
import { dm, dmv } from './messages'
import { fieldCode, isOpen, refusal } from './words'

const MAX_REASON = 500

const when = (intl: IntlShape, at: string, timeZone: string) =>
  intl.formatDate(at, { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit', timeZone })

/** How the declaration came to be: by the member in the app, recorded for them, or carried over. */
function origin(intl: IntlShape, d: DeclarationResponse, timeZone: string): string {
  const date = when(intl, d.declaredAt, timeZone)
  switch (d.source) {
    case 'ON_BEHALF':
      return intl.formatMessage(dmv.recordedFor, { name: d.recordedBy?.name ?? '', date, how: d.how })
    case 'MEMO':
      return intl.formatMessage(dm.carriedMemo)
    case 'EARLIER_MEETING':
      return intl.formatMessage(dm.carriedMeeting)
    default:
      return intl.formatMessage(dmv.declaredSelf, { date })
  }
}

/**
 * "Your declaration — item 6, Market risk limits" (flow S4): when and how it was made, the interest in the member's own
 * words, what they chose and what the chair decided; "Ask the chair to decide instead" (or "I will leave the room
 * instead") and "Withdraw this declaration…" with a reason. Changes carry the declaration's version as If-Match. Once
 * the meeting is held the sheet only reads.
 */
export function MyDeclarationSheet({
  declaration: d,
  name,
  open,
  timeZone,
  refresh,
  onClose,
}: {
  declaration: DeclarationResponse
  name: string
  open: boolean
  timeZone: string
  refresh: () => Promise<void>
  onClose: () => void
}) {
  const intl = useIntl()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string>()
  const [withdrawing, setWithdrawing] = useState(false)
  const withdrawButton = useRef<HTMLButtonElement>(null)
  const ifMatch = { 'If-Match': `"${String(d.version)}"` }
  const live = open && isOpen(d)
  // From a pending ruling or after a ruling to take part, the member may still choose to leave; from leaving, they may
  // ask the chair (E04 §1.2).
  const next = d.state === 'RECUSED' ? 'ASK_CHAIR' : 'LEAVE'

  const failed = async (refused: unknown) => {
    if (isProblem(refused) && (refused.status === 409 || refused.status === 412 || refused.status === 404)) {
      await refresh()
    }
    setProblem(refusal(intl, refused))
  }

  const changeIntent = async () => {
    setBusy(true)
    setProblem(undefined)
    try {
      await changeDeclarationIntent({
        path: { meetingId: d.meetingId, declarationId: d.id },
        headers: ifMatch,
        body: { intent: next },
        throwOnError: true,
      })
      toast.show(intl.formatMessage(next === 'ASK_CHAIR' ? dm.askedChair : dm.leaving), 'success')
      await refresh()
      onClose()
    } catch (refused) {
      await failed(refused)
    } finally {
      setBusy(false)
    }
  }

  const ruling = d.ruling
  return (
    <>
      <Dialog
        open
        onOpenChange={(o) => {
          if (!o) onClose()
        }}
        title={intl.formatMessage(dmv.mineTitle, { item: name })}
        className="fixed inset-y-0 end-0 start-auto m-0 h-dvh max-h-none w-full max-w-full overflow-y-auto rounded-none border-y-0 border-e-0 sm:w-[34rem]"
      >
        <div className="flex flex-col gap-4 text-base">
          <p>{origin(intl, d, timeZone)}</p>
          {d.source === 'ON_BEHALF' && d.howNote ? (
            <p className="text-muted-foreground">{intl.formatMessage(dmv.howNote, { note: d.howNote })}</p>
          ) : null}
          <blockquote dir="auto" className="whitespace-pre-line border-s-4 ps-3 text-lg">
            {intl.formatMessage(dmv.nature, { nature: d.nature })}
          </blockquote>
          <p className="font-medium">{intl.formatMessage(d.intent === 'LEAVE' ? dm.choseLeave : dm.choseAsk)}</p>
          {d.state === 'AWAITING_RULING' ? <p>{intl.formatMessage(dm.awaiting)}</p> : null}
          {ruling && d.state !== 'AWAITING_RULING' ? (
            <p>
              {intl.formatMessage(ruling.outcome === 'TAKE_PART' ? dmv.ruledTakePart : dmv.ruledLeave, {
                date: when(intl, ruling.at, timeZone),
              })}
              {ruling.note ? <> {intl.formatMessage(dmv.rulingNote, { note: ruling.note })}</> : null}
            </p>
          ) : null}
          {!live ? <p className="text-muted-foreground">{intl.formatMessage(dm.closed)}</p> : null}
          {problem ? (
            <p role="alert" className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-destructive">
              {problem}
            </p>
          ) : null}
          <div className="flex flex-wrap gap-2 border-t pt-4">
            {live ? (
              <>
                <Button
                  variant="outline"
                  className="h-auto min-h-11 whitespace-normal text-base"
                  disabled={busy}
                  onClick={() => void changeIntent()}
                >
                  {intl.formatMessage(next === 'ASK_CHAIR' ? dm.askInstead : dm.leaveInstead)}
                </Button>
                <Button
                  ref={withdrawButton}
                  variant="outline"
                  className="h-auto min-h-11 whitespace-normal text-base text-destructive"
                  disabled={busy}
                  onClick={() => {
                    setProblem(undefined)
                    setWithdrawing(true)
                  }}
                >
                  {intl.formatMessage(dm.withdrawOpen)}
                </Button>
              </>
            ) : null}
            <Button variant="outline" className="ms-auto min-h-11 text-base" onClick={onClose}>
              {intl.formatMessage(dm.close)}
            </Button>
          </div>
        </div>
      </Dialog>
      {withdrawing ? (
        <WithdrawDialog
          declaration={d}
          name={name}
          refresh={refresh}
          onDone={onClose}
          onClose={() => {
            setWithdrawing(false)
            withdrawButton.current?.focus()
          }}
        />
      ) : null}
    </>
  )
}

/** "Withdraw your declaration?" with a reason (flow S4): the secretary and the chair are told; it is in the minutes. */
function WithdrawDialog({
  declaration: d,
  name,
  refresh,
  onDone,
  onClose,
}: {
  declaration: DeclarationResponse
  name: string
  refresh: () => Promise<void>
  onDone: () => void
  onClose: () => void
}) {
  const intl = useIntl()
  const toast = useToast()
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string>()
  const reasonRef = useRef<HTMLTextAreaElement>(null)

  const refuseReason = (message: string) => {
    setReasonError(message)
    reasonRef.current?.focus()
  }

  const submit = async (): Promise<string | undefined> => {
    const text = reason.trim()
    if (!text) {
      refuseReason(intl.formatMessage(dm.reasonRequired))
      return undefined
    }
    if (text.length > MAX_REASON) {
      refuseReason(intl.formatMessage(dm.reasonLength))
      return undefined
    }
    setReasonError(undefined)
    try {
      await withdrawDeclaration({
        path: { meetingId: d.meetingId, declarationId: d.id },
        headers: { 'If-Match': `"${String(d.version)}"` },
        body: { reason: text },
        throwOnError: true,
      })
      toast.show(intl.formatMessage(dm.withdrawn), 'success')
      await refresh()
      onDone()
      return undefined
    } catch (refused) {
      const code = fieldCode(refused, 'reason')
      if (code) {
        refuseReason(intl.formatMessage(code === 'LENGTH' ? dm.reasonLength : dm.reasonRequired))
        return undefined
      }
      if (isProblem(refused) && (refused.status === 409 || refused.status === 412 || refused.status === 404)) {
        await refresh()
      }
      return refusal(intl, refused)
    }
  }

  return (
    <FormDialog
      large
      destructive
      title={intl.formatMessage(dmv.withdrawTitle, { item: name })}
      description={intl.formatMessage(dm.withdrawExplain)}
      submitLabel={intl.formatMessage(dm.withdrawSubmit)}
      cancelLabel={intl.formatMessage(dm.cancel)}
      onSubmit={submit}
      onClose={onClose}
    >
      <Field label={intl.formatMessage(dm.reasonLabel)} required error={reasonError}>
        {(p) => (
          <Textarea
            {...p}
            ref={reasonRef}
            dir="auto"
            rows={3}
            className="field-sizing-content min-h-20 text-base"
            value={reason}
            onChange={(e) => {
              setReason(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}
