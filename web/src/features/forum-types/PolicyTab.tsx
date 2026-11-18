import { Plus } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select, Textarea } from '@/components/ui/form-controls'
import { ft, ftv } from './messages'
import { NumberInput } from './NumberInput'
import { ATTRIBUTE_KEY, keyFromLabel, MAX_ATTRIBUTES, type Attribute, type TypeSettings } from './settings'

/** Attributes most charters use (research §3.1), offered as ticks; their labels are written in the admin's language. */
const SUGGESTED = [
  { key: 'independent', label: ft.attributeIndependent },
  { key: 'nonExecutive', label: ft.attributeNonExecutive },
  { key: 'management', label: ft.attributeManagement },
]

/**
 * The Policy tab (flow J3): name, description, default classification, how long records are kept, and the member
 * attributes quorum rules and the roster use. Read-only unless the version is a draft ([onChange] set).
 */
export function PolicyTab({
  settings,
  onChange,
  levels,
  errors,
}: {
  settings: TypeSettings
  onChange?: (next: Partial<TypeSettings>) => void
  levels: { id: string; name: string }[]
  errors: Record<string, string>
}) {
  const intl = useIntl()
  const set = (next: Partial<TypeSettings>) => onChange?.(next)
  return (
    <fieldset disabled={!onChange} className="flex max-w-2xl flex-col gap-4">
      <Field label={intl.formatMessage(ft.name)} required error={errors.name}>
        {(p) => (
          <Input
            {...p}
            maxLength={200}
            value={settings.name}
            onChange={(e) => {
              set({ name: e.target.value })
            }}
          />
        )}
      </Field>
      <Field label={intl.formatMessage(ft.description)} error={errors.description}>
        {(p) => (
          <Textarea
            {...p}
            rows={2}
            maxLength={2000}
            value={settings.description}
            onChange={(e) => {
              set({ description: e.target.value })
            }}
          />
        )}
      </Field>
      <Field label={intl.formatMessage(ft.classification)} error={errors.defaultClassificationId}>
        {(p) => (
          <Select
            {...p}
            className="max-w-xs"
            value={settings.defaultClassificationId ?? ''}
            onChange={(e) => {
              set({ defaultClassificationId: e.target.value || null })
            }}
          >
            <option value="">{intl.formatMessage(ft.lowest)}</option>
            {levels.map((l) => (
              <option key={l.id} value={l.id}>
                {l.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Field
        label={intl.formatMessage(ft.retention)}
        hint={intl.formatMessage(ft.retentionHint)}
        error={errors['retention.years']}
      >
        {(p) => (
          <NumberInput
            {...p}
            min={1}
            max={100}
            value={settings.retentionYears}
            onChange={(retentionYears) => {
              set({ retentionYears })
            }}
          />
        )}
      </Field>
      <Attributes
        attributes={settings.memberAttributes}
        onChange={
          onChange
            ? (memberAttributes) => {
                onChange({ memberAttributes })
              }
            : undefined
        }
        errors={errors}
      />
    </fieldset>
  )
}

function Attributes({
  attributes,
  onChange,
  errors,
}: {
  attributes: Attribute[]
  onChange?: (next: Attribute[]) => void
  errors: Record<string, string>
}) {
  const intl = useIntl()
  const [adding, setAdding] = useState(false)
  const offered = SUGGESTED.filter((s) => !attributes.some((a) => a.key === s.key))
  const full = attributes.length >= MAX_ATTRIBUTES
  const relabel = (index: number, label: string) => {
    onChange?.(attributes.map((a, i) => (i === index ? { ...a, label } : a)))
  }

  return (
    <fieldset className="flex flex-col gap-2" aria-describedby="attributes-hint">
      <legend className="text-sm font-medium">{intl.formatMessage(ft.attributes)}</legend>
      <p id="attributes-hint" className="text-xs text-muted-foreground">
        {intl.formatMessage(ft.attributesHint)}
      </p>
      {errors.memberAttributes ? (
        <p className="text-xs font-medium text-destructive">{errors.memberAttributes}</p>
      ) : null}
      <ul className="flex flex-col gap-2">
        {attributes.map((a, i) => {
          const error = errors[`memberAttributes[${String(i)}].key`] ?? errors[`memberAttributes[${String(i)}].label`]
          return (
            <li key={a.key} className="flex flex-col gap-1">
              <div className="flex flex-wrap items-center gap-3">
                <Checkbox
                  className="size-5"
                  checked
                  aria-label={intl.formatMessage(ftv.attributeUse, { label: a.label || a.key })}
                  onChange={() => onChange?.(attributes.filter((_, j) => j !== i))}
                />
                <Input
                  className="max-w-64"
                  maxLength={60}
                  aria-label={intl.formatMessage(ftv.attributeLabelOf, { key: a.key })}
                  aria-invalid={error ? true : undefined}
                  value={a.label}
                  onChange={(e) => {
                    relabel(i, e.target.value)
                  }}
                />
                <code className="text-xs text-muted-foreground">
                  {intl.formatMessage(ftv.attributeKeyShown, { key: a.key })}
                </code>
              </div>
              {error ? <p className="text-xs font-medium text-destructive">{error}</p> : null}
            </li>
          )
        })}
        {offered.map((s) => {
          const label = intl.formatMessage(s.label)
          return (
            <li key={s.key} className="flex flex-wrap items-center gap-3">
              <label className="flex min-h-10 items-center gap-3 text-sm">
                <Checkbox
                  className="size-5"
                  checked={false}
                  disabled={full}
                  onChange={() => onChange?.([...attributes, { key: s.key, label }])}
                />
                {label}
              </label>
              <code className="text-xs text-muted-foreground">
                {intl.formatMessage(ftv.attributeKeyShown, { key: s.key })}
              </code>
            </li>
          )
        })}
      </ul>
      {onChange ? (
        adding ? (
          <NewAttribute
            taken={attributes.map((a) => a.key)}
            onAdd={(attribute) => {
              onChange([...attributes, attribute])
              setAdding(false)
            }}
            onCancel={() => {
              setAdding(false)
            }}
          />
        ) : (
          <div className="flex flex-wrap items-center gap-3">
            <Button
              variant="outline"
              disabled={full}
              onClick={() => {
                setAdding(true)
              }}
            >
              <Plus aria-hidden="true" />
              {intl.formatMessage(ft.addAttribute)}
            </Button>
            {full ? (
              <span className="text-xs text-muted-foreground">{intl.formatMessage(ft.attributesFull)}</span>
            ) : null}
          </div>
        )
      ) : null}
    </fieldset>
  )
}

function NewAttribute({
  taken,
  onAdd,
  onCancel,
}: {
  taken: string[]
  onAdd: (attribute: Attribute) => void
  onCancel: () => void
}) {
  const intl = useIntl()
  const [label, setLabel] = useState('')
  const [key, setKey] = useState('')
  const [keyEdited, setKeyEdited] = useState(false)
  const [errors, setErrors] = useState<{ label?: string; key?: string }>({})

  const add = () => {
    const found: { label?: string; key?: string } = {}
    if (!label.trim()) found.label = intl.formatMessage(ft.required)
    if (!ATTRIBUTE_KEY.test(key)) found.key = intl.formatMessage(ft.attributeKeyFormat)
    else if (taken.includes(key)) found.key = intl.formatMessage(ft.attributeKeyTaken)
    setErrors(found)
    if (!found.label && !found.key) onAdd({ key, label: label.trim() })
  }

  return (
    <div className="flex flex-col gap-3 rounded-md border p-3">
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={intl.formatMessage(ft.attributeLabel)} required error={errors.label}>
          {(p) => (
            <Input
              {...p}
              maxLength={60}
              value={label}
              onChange={(e) => {
                setLabel(e.target.value)
                if (!keyEdited) setKey(keyFromLabel(e.target.value))
              }}
            />
          )}
        </Field>
        <Field
          label={intl.formatMessage(ft.attributeKey)}
          hint={intl.formatMessage(ft.attributeKeyHint)}
          required
          error={errors.key}
        >
          {(p) => (
            <Input
              {...p}
              maxLength={30}
              dir="ltr"
              value={key}
              onChange={(e) => {
                setKeyEdited(true)
                setKey(e.target.value)
              }}
            />
          )}
        </Field>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" onClick={onCancel}>
          {intl.formatMessage(ft.cancel)}
        </Button>
        <Button onClick={add}>{intl.formatMessage(ft.keepAttribute)}</Button>
      </div>
    </div>
  )
}
