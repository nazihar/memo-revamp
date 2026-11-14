import { defineMessages, useIntl } from 'react-intl'
import type { MemoTypeOption } from '@/api/generated/types.gen'
import { Field } from '@/components/ui/field'
import { Select } from '@/components/ui/form-controls'
import { mm } from './memo-messages'

const m = defineMessages({
  question: { id: 'memo.type.question', defaultMessage: 'What kind of memo?' },
  onSubmit: { id: 'memo.type.numberedOnSubmit', defaultMessage: 'Numbered when submitted' },
  onApproval: { id: 'memo.type.numberedOnApproval', defaultMessage: 'Numbered when finally approved' },
  hasFields: { id: 'memo.type.hasFields', defaultMessage: 'Asks for details' },
})

/** Up to this many types show as choices with a line each; more as a list. */
const CARD_LIMIT = 6

/**
 * The first question of a new memo (ux-review B1): its type. A few types are radio choices that say how each is
 * numbered and whether it asks for details; many are a list, labelled "Memo type" either way.
 */
export function MemoTypeChooser({
  types,
  value,
  onChange,
  error,
}: {
  types: MemoTypeOption[]
  value: string
  onChange: (typeId: string) => void
  error?: string
}) {
  const intl = useIntl()
  if (types.length > CARD_LIMIT) {
    return (
      <Field label={intl.formatMessage(mm.type)} required error={error}>
        {(p) => (
          <Select
            {...p}
            name="typeId"
            value={value}
            onChange={(e) => {
              onChange(e.target.value)
            }}
          >
            {types.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
    )
  }
  return (
    <fieldset className="flex flex-col gap-2" aria-describedby={error ? 'memo-type-error' : undefined}>
      <legend className="mb-1 text-sm font-medium">
        {intl.formatMessage(mm.type)}
        <span className="block font-normal text-muted-foreground">{intl.formatMessage(m.question)}</span>
      </legend>
      <div className="grid gap-2 sm:grid-cols-2">
        {types.map((t) => {
          const lines = [
            intl.formatMessage(t.numberingMoment === 'ON_APPROVAL' ? m.onApproval : m.onSubmit),
            t.hasFields ? intl.formatMessage(m.hasFields) : null,
          ].filter((line) => line !== null)
          return (
            <label
              key={t.id}
              className="flex min-h-11 cursor-pointer items-start gap-3 rounded-md border p-3 has-[:checked]:border-primary has-[:checked]:bg-accent"
            >
              <input
                type="radio"
                name="typeId"
                className="mt-0.5 size-4"
                value={t.id}
                checked={value === t.id}
                onChange={() => {
                  onChange(t.id)
                }}
              />
              <span className="flex flex-col">
                <span className="text-sm font-medium">{t.name}</span>
                <span className="text-xs text-muted-foreground">{lines.join(' · ')}</span>
              </span>
            </label>
          )
        })}
      </div>
      {error ? (
        <p id="memo-type-error" className="text-sm text-destructive">
          {error}
        </p>
      ) : null}
    </fieldset>
  )
}
