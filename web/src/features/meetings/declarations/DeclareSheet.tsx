import { useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { declareInterest } from '@/api/generated/sdk.gen'
import type { DeclarationResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { FormDialog } from '@/features/forums/FormDialog'
import { newIdempotencyKey } from '@/lib/idempotency'
import { dm, dmv } from './messages'
import { fieldCode, refusal } from './words'

const MAX_NATURE = 1_000

type Intent = DeclarationResponse['intent']

/**
 * "Declare an interest — item 5, Term loan, Delta Textiles" (flow S2): what the interest is, and what the member will do
 * — leave the room (the default, recommended) or ask the chair to decide, with the papers withheld either way until then
 * (ADR-0062). A side sheet, full screen on a phone. The same request keeps its Idempotency-Key, so a retry after a lost
 * answer does not declare twice; a changed request gets a new one.
 */
export function DeclareSheet({
  meetingId,
  itemId,
  name,
  refresh,
  onDone,
  onClose,
}: {
  meetingId: string
  itemId: string
  name: string
  refresh: () => Promise<void>
  onDone: () => void
  onClose: () => void
}) {
  const intl = useIntl()
  const toast = useToast()
  const [nature, setNature] = useState('')
  const [intent, setIntent] = useState<Intent>('LEAVE')
  const [natureError, setNatureError] = useState<string>()
  const natureRef = useRef<HTMLTextAreaElement>(null)
  const sent = useRef<{ key: string; body: string } | null>(null)

  const refuseNature = (message: string) => {
    setNatureError(message)
    natureRef.current?.focus()
  }

  const submit = async (): Promise<string | undefined> => {
    const text = nature.trim()
    if (!text) {
      refuseNature(intl.formatMessage(dm.natureRequired))
      return undefined
    }
    if (text.length > MAX_NATURE) {
      refuseNature(intl.formatMessage(dm.natureLength))
      return undefined
    }
    setNatureError(undefined)
    const body = { nature: text, intent }
    const json = JSON.stringify(body)
    if (sent.current?.body !== json) sent.current = { key: newIdempotencyKey(), body: json }
    try {
      await declareInterest({
        path: { meetingId, itemId },
        headers: { 'Idempotency-Key': sent.current.key },
        body,
        throwOnError: true,
      })
      toast.show(intl.formatMessage(intent === 'LEAVE' ? dm.declaredLeave : dm.declaredAsk), 'success')
      await refresh()
      onDone()
      return undefined
    } catch (problem) {
      const code = fieldCode(problem, 'nature')
      if (code) {
        refuseNature(intl.formatMessage(code === 'LENGTH' ? dm.natureLength : dm.natureRequired))
        return undefined
      }
      if (isProblem(problem) && (problem.status === 409 || problem.status === 404)) await refresh()
      return refusal(intl, problem)
    }
  }

  return (
    <FormDialog
      side
      large
      title={intl.formatMessage(dmv.declareTitle, { item: name })}
      description={intl.formatMessage(dm.declareExplain)}
      submitLabel={intl.formatMessage(dm.submit)}
      cancelLabel={intl.formatMessage(dm.cancel)}
      onSubmit={submit}
      onClose={onClose}
    >
      <Field
        label={intl.formatMessage(dm.natureLabel)}
        hint={intl.formatMessage(dm.natureHint)}
        required
        error={natureError}
      >
        {(p) => (
          <Textarea
            {...p}
            ref={natureRef}
            dir="auto"
            rows={4}
            className="field-sizing-content min-h-24 text-base"
            value={nature}
            onChange={(e) => {
              setNature(e.target.value)
            }}
          />
        )}
      </Field>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">{intl.formatMessage(dm.intentLegend)}</legend>
        {(['LEAVE', 'ASK_CHAIR'] as const).map((value) => (
          <label key={value} className="flex min-h-11 cursor-pointer items-start gap-3 rounded-md p-1 text-base">
            <input
              type="radio"
              name="intent"
              value={value}
              className="mt-1.5 size-5 shrink-0 accent-primary"
              checked={intent === value}
              onChange={() => {
                setIntent(value)
              }}
            />
            <span className="flex flex-col">
              <span className="font-medium">
                {value === 'LEAVE' ? (
                  <>
                    {intl.formatMessage(dm.leave)} {intl.formatMessage(dm.recommended)}
                  </>
                ) : (
                  intl.formatMessage(dm.askChair)
                )}
              </span>
              <span className="text-muted-foreground">
                {intl.formatMessage(value === 'LEAVE' ? dm.leaveHint : dm.askChairHint)}
              </span>
            </span>
          </label>
        ))}
      </fieldset>
    </FormDialog>
  )
}
