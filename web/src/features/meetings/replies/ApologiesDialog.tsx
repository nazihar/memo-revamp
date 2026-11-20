import { useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import type { MyRsvpResponse } from '@/api/generated/types.gen'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { FormDialog } from '@/features/forums/FormDialog'
import { rm, rmv } from './messages'

export const MAX_NOTE = 300

/**
 * "No, I send my apologies" (flow V3): when the member's seat has an alternate, "Your alternate is A. Rahman" with
 * "A. Rahman will attend in my place" (the default) or "Nobody will attend in my place"; a note for the secretary.
 * [onSend] answers a refusal in words (shown in the dialog), or nothing when the apologies are recorded.
 */
export function ApologiesDialog({
  mine,
  onSend,
  onClose,
}: {
  mine: MyRsvpResponse
  onSend: (alternateAttends: boolean, note: string | null) => Promise<string | undefined>
  onClose: () => void
}) {
  const intl = useIntl()
  const alternate = mine.capacity === 'MEMBER' ? mine.alternate : null
  const [withAlternate, setWithAlternate] = useState(
    alternate ? mine.answer !== 'APOLOGIES' || mine.alternateAttends : false,
  )
  const [note, setNote] = useState(mine.note ?? '')
  const [noteError, setNoteError] = useState<string>()
  const noteRef = useRef<HTMLTextAreaElement>(null)

  const submit = () => {
    const text = note.trim()
    if (text.length > MAX_NOTE) {
      setNoteError(intl.formatMessage(rm.noteHint))
      noteRef.current?.focus()
      return Promise.resolve(undefined)
    }
    setNoteError(undefined)
    return onSend(alternate != null && withAlternate, text || null)
  }

  return (
    <FormDialog
      large
      title={intl.formatMessage(rm.APOLOGIES)}
      description={alternate ? intl.formatMessage(rmv.yourAlternate, { name: alternate.name }) : undefined}
      submitLabel={intl.formatMessage(rm.sendApologies)}
      cancelLabel={intl.formatMessage(rm.cancel)}
      onSubmit={submit}
      onClose={onClose}
    >
      {alternate ? (
        <fieldset className="flex flex-col gap-2">
          <legend className="sr-only">{intl.formatMessage(rmv.yourAlternate, { name: alternate.name })}</legend>
          {[true, false].map((value) => (
            <label
              key={String(value)}
              className="flex min-h-11 cursor-pointer items-center gap-3 rounded-md p-1 text-base"
            >
              <input
                type="radio"
                name="alternate"
                className="size-5 shrink-0 accent-primary"
                checked={withAlternate === value}
                onChange={() => {
                  setWithAlternate(value)
                }}
              />
              {value
                ? intl.formatMessage(rmv.alternateChoice, { name: alternate.name })
                : intl.formatMessage(rm.nobodyAttends)}
            </label>
          ))}
        </fieldset>
      ) : null}
      <Field
        label={intl.formatMessage(rm.noteLabel)}
        optionalLabel={intl.formatMessage(rm.optional)}
        hint={noteError ? undefined : intl.formatMessage(rm.noteHint)}
        error={noteError}
      >
        {(p) => (
          <Textarea
            {...p}
            ref={noteRef}
            dir="auto"
            rows={2}
            className="field-sizing-content min-h-16 text-base"
            value={note}
            onChange={(e) => {
              setNote(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}
