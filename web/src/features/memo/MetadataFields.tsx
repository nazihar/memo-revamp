import { useId } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import type { MemoField } from '@/api/generated/types.gen'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select, Textarea } from '@/components/ui/form-controls'
import { PeoplePicker } from '@/features/org/PeoplePicker'

const m = defineMessages({
  currency: { id: 'memo.field.currency', defaultMessage: 'Currency' },
  choose: { id: 'memo.field.choose', defaultMessage: 'Choose…' },
  optional: { id: 'memo.field.optional', defaultMessage: '(optional)' },
})

/** An amount as the API takes it: a decimal string and an ISO 4217 currency. */
export interface AmountValue {
  value: string
  currency: string
}

/** A field's value as the form holds it (and the API takes it): text, a decimal string, an id, a key, keys or an amount. */
export type MetadataValue = string | string[] | AmountValue

/** Values by field key, with the names shown for picked units and people. */
export interface MetadataState {
  values: Record<string, MetadataValue | undefined>
  labels: Record<string, string>
}

/** The mark after a required field's label (not read out: the control says it is required). */
const REQUIRED_MARK = ' *'

/** Choices up to this many show as radio buttons; more as a list. */
const RADIO_LIMIT = 5

/**
 * The details of a memo type (ADR-0043): one control per field, in the type's order, built from the field's type and
 * rules. Used by the create form and the edit dialog, and as the live preview of the memo-type admin. Required fields are
 * marked but checked at submit; [errors] come from the server by `metadata.<key>`.
 */
export function MetadataFields({
  fields,
  state,
  onChange,
  errors = {},
}: {
  fields: MemoField[]
  state: MetadataState
  onChange: (key: string, value: MetadataValue | undefined, label?: string) => void
  errors?: Record<string, string | undefined>
}) {
  return (
    <div className="flex flex-col gap-4">
      {fields.map((field) => (
        <MetadataControl
          key={field.key}
          field={field}
          value={state.values[field.key]}
          label={state.labels[field.key]}
          error={errors[`metadata.${field.key}`]}
          onChange={(value, label) => {
            onChange(field.key, value, label)
          }}
        />
      ))}
    </div>
  )
}

function MetadataControl({
  field,
  value,
  label,
  error,
  onChange,
}: {
  field: MemoField
  value: MetadataValue | undefined
  label?: string
  error?: string
  onChange: (value: MetadataValue | undefined, label?: string) => void
}) {
  const intl = useIntl()
  const groupId = useId()
  const choices = field.rules.choices ?? []
  const text = typeof value === 'string' ? value : ''
  const optional = field.required ? undefined : intl.formatMessage(m.optional)

  if (field.type === 'MULTI_CHOICE' || (field.type === 'CHOICE' && choices.length <= RADIO_LIMIT)) {
    const multiple = field.type === 'MULTI_CHOICE'
    const picked = multiple ? (Array.isArray(value) ? value : []) : [text]
    return (
      <fieldset
        className="flex flex-col gap-1.5"
        aria-describedby={
          [field.help ? `${groupId}-hint` : '', error ? `${groupId}-error` : ''].join(' ').trim() || undefined
        }
      >
        <legend className="text-sm font-medium">
          {field.label}
          {field.required ? (
            <span aria-hidden="true">{REQUIRED_MARK}</span>
          ) : (
            <span className="text-muted-foreground"> {optional}</span>
          )}
        </legend>
        {field.help ? (
          <p id={`${groupId}-hint`} className="text-xs text-muted-foreground">
            {field.help}
          </p>
        ) : null}
        {choices.map((choice) => (
          <label key={choice.key} className="flex min-h-10 items-center gap-2 text-sm">
            {multiple ? (
              <Checkbox
                checked={picked.includes(choice.key)}
                onChange={(e) => {
                  const next = e.target.checked ? [...picked, choice.key] : picked.filter((k) => k !== choice.key)
                  onChange(next.length ? next : undefined)
                }}
              />
            ) : (
              <input
                type="radio"
                name={groupId}
                className="size-4"
                checked={text === choice.key}
                onChange={() => {
                  onChange(choice.key)
                }}
              />
            )}
            {choice.label}
          </label>
        ))}
        {error ? (
          <p id={`${groupId}-error`} className="text-sm text-destructive">
            {error}
          </p>
        ) : null}
      </fieldset>
    )
  }

  return (
    <Field
      label={field.label}
      hint={field.help ?? undefined}
      error={error}
      required={field.required}
      optionalLabel={optional}
    >
      {(p) => {
        switch (field.type) {
          case 'LONG_TEXT':
            return (
              <Textarea
                {...p}
                rows={4}
                maxLength={field.rules.maxLength ?? 4000}
                value={text}
                onChange={(e) => {
                  onChange(e.target.value || undefined)
                }}
              />
            )
          case 'NUMBER':
            return (
              <Input
                {...p}
                inputMode="decimal"
                value={text}
                onChange={(e) => {
                  onChange(e.target.value || undefined)
                }}
              />
            )
          case 'AMOUNT':
            return <AmountInput control={p} field={field} value={value} onChange={onChange} />
          case 'DATE':
            return (
              <Input
                {...p}
                type="date"
                value={text}
                onChange={(e) => {
                  onChange(e.target.value || undefined)
                }}
              />
            )
          case 'UNIT':
          case 'PERSON':
            return (
              <PeoplePicker
                id={p.id}
                kind={field.type === 'UNIT' ? 'unit' : 'user'}
                value={text ? { id: text, label: label ?? '' } : undefined}
                invalid={p['aria-invalid']}
                describedBy={p['aria-describedby']}
                onChange={(picked) => {
                  onChange(picked?.id, picked?.label)
                }}
              />
            )
          case 'CHOICE':
            return (
              <Select
                {...p}
                value={text}
                onChange={(e) => {
                  onChange(e.target.value || undefined)
                }}
              >
                <option value="">{intl.formatMessage(m.choose)}</option>
                {choices.map((choice) => (
                  <option key={choice.key} value={choice.key}>
                    {choice.label}
                  </option>
                ))}
              </Select>
            )
          default:
            return (
              <Input
                {...p}
                maxLength={field.rules.maxLength ?? 200}
                value={text}
                onChange={(e) => {
                  onChange(e.target.value || undefined)
                }}
              />
            )
        }
      }}
    </Field>
  )
}

function AmountInput({
  control,
  field,
  value,
  onChange,
}: {
  control: { id: string; 'aria-describedby'?: string; 'aria-invalid'?: true; 'aria-required'?: true }
  field: MemoField
  value: MetadataValue | undefined
  onChange: (value: MetadataValue | undefined) => void
}) {
  const intl = useIntl()
  const currencies = field.rules.currencies ?? []
  const amount: AmountValue =
    value && typeof value === 'object' && !Array.isArray(value) ? value : { value: '', currency: currencies[0] ?? '' }
  const set = (next: AmountValue) => {
    onChange(next.value ? next : undefined)
  }
  return (
    <div className="flex gap-2">
      <Input
        {...control}
        inputMode="decimal"
        className="flex-1"
        value={amount.value}
        onChange={(e) => {
          set({ ...amount, value: e.target.value })
        }}
      />
      {currencies.length > 1 ? (
        <Select
          aria-label={intl.formatMessage(m.currency)}
          className="w-28"
          value={amount.currency}
          onChange={(e) => {
            set({ ...amount, currency: e.target.value })
          }}
        >
          {currencies.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </Select>
      ) : (
        <span className="self-center text-sm text-muted-foreground">{amount.currency}</span>
      )}
    </div>
  )
}
