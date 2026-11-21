import { useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { isProblem } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { FormDialog } from '@/features/forums/FormDialog'
import { useTerms } from '@/features/terms/terms'
import { am, amt, amv } from './messages'

/** The longest reason the server takes (design E05 §1.7). */
export const MAX_OVERRIDE_REASON = 500

/** The numbers an override is asked for: "3 of 4 needed". */
export interface Shortfall {
  present: number
  needed: number
}

/**
 * "No decision can be recorded: the item is not quorate (3 of 4 needed)." — what E06's decision screen says when the
 * committee type does not allow a decision without a quorum (flow Y3).
 */
export function NotQuorateNotice({ quorum }: { quorum: Shortfall }) {
  const intl = useIntl()
  return (
    <p role="status" className="rounded-md border border-destructive/40 bg-status-danger-bg p-3 text-base">
      {intl.formatMessage(amv.noDecisionNotQuorate, { present: quorum.present, needed: quorum.needed })}
    </p>
  )
}

/**
 * "Record without quorum" (flow Y3), the dialog E06's decision screen opens for an item that is not quorate when the
 * committee type allows it ([allowed]: the pinned version's `meetings.quorumOverride` is WITH_REASON). A reason is
 * required (1–500 characters); the decision is to be ratified at a later quorate meeting; the chair and the other
 * secretaries are told. [onRecord] sends the decision with the reason (E06's request, which takes the snapshot) and
 * throws the server's problem when refused; the dialog says it in words: `OVERRIDE_NOT_ALLOWED` (then, as when [allowed]
 * is false, it only says why no decision can be recorded), `REASON_REQUIRED` and `LENGTH` at the field, 403 `RECUSED`,
 * 409 `NOT_QUORATE`.
 */
export function QuorumOverrideDialog({
  quorum,
  allowed,
  onRecord,
  onClose,
}: {
  quorum: Shortfall
  allowed: boolean
  onRecord: (reason: string) => Promise<unknown>
  onClose: () => void
}) {
  const intl = useIntl()
  const terms = useTerms()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const [refused, setRefused] = useState(!allowed)
  const reasonRef = useRef<HTMLTextAreaElement>(null)
  const numbers = { present: quorum.present, needed: quorum.needed }

  const fieldError = (message: string) => {
    setError(message)
    reasonRef.current?.focus()
  }

  const submit = async (): Promise<string | undefined> => {
    const text = reason.trim()
    if (!text) {
      fieldError(intl.formatMessage(am.overrideReasonRequired))
      return undefined
    }
    if (text.length > MAX_OVERRIDE_REASON) {
      fieldError(intl.formatMessage(amv.overrideTooLong, { max: MAX_OVERRIDE_REASON }))
      return undefined
    }
    setError(undefined)
    try {
      await onRecord(text)
      onClose()
      return undefined
    } catch (problem) {
      if (!isProblem(problem)) return intl.formatMessage(am.failed)
      const code = problem.status === 422 ? problem.errors?.[0]?.code : undefined
      switch (problem.reason ?? code) {
        case 'OVERRIDE_NOT_ALLOWED':
          setRefused(true)
          return undefined
        case 'REASON_REQUIRED':
          fieldError(intl.formatMessage(am.overrideReasonRequired))
          return undefined
        case 'LENGTH':
          fieldError(intl.formatMessage(amv.overrideTooLong, { max: MAX_OVERRIDE_REASON }))
          return undefined
        case 'RECUSED':
          return intl.formatMessage(am.overrideRecused)
        case 'NOT_QUORATE':
          return intl.formatMessage(am.overrideNotQuorate)
      }
      if (problem.status === 403 || problem.status === 404) return intl.formatMessage(am.overrideForbidden)
      return intl.formatMessage(am.failed)
    }
  }

  if (refused) {
    return (
      <Dialog
        open
        onOpenChange={(open) => {
          if (!open) onClose()
        }}
        title={intl.formatMessage(am.overrideTitle)}
        footer={
          <Button variant="outline" className="min-h-11 text-base" onClick={onClose}>
            {intl.formatMessage(am.close)}
          </Button>
        }
      >
        <div role="alert" className="flex flex-col gap-2 text-base">
          <p>{intl.formatMessage(amt.OVERRIDE_NOT_ALLOWED, terms)}</p>
          <p>{intl.formatMessage(amv.noDecisionNotQuorate, numbers)}</p>
        </div>
      </Dialog>
    )
  }

  return (
    <FormDialog
      large
      title={intl.formatMessage(am.overrideTitle)}
      description={intl.formatMessage(amv.overrideNotQuorate, numbers)}
      submitLabel={intl.formatMessage(am.overrideSubmit)}
      cancelLabel={intl.formatMessage(am.cancel)}
      onSubmit={submit}
      onClose={onClose}
    >
      <p className="text-base">{intl.formatMessage(amt.overrideRules, terms)}</p>
      <Field
        label={intl.formatMessage(am.overrideReason)}
        hint={intl.formatMessage(amv.overrideReasonHint, { max: MAX_OVERRIDE_REASON })}
        error={error}
        required
      >
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
      <p className="text-base">{intl.formatMessage(am.overrideTold)}</p>
    </FormDialog>
  )
}
