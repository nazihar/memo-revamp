import { ArrowDown, ArrowUp, Pencil, Plus, X } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import type { MemoField } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select, Textarea } from '@/components/ui/form-controls'
import { MetadataFields, type MetadataState } from '@/features/memo/MetadataFields'
import { tm, tmv } from './messages'

const TYPES: MemoField['type'][] = [
  'TEXT',
  'LONG_TEXT',
  'NUMBER',
  'AMOUNT',
  'DATE',
  'UNIT',
  'PERSON',
  'CHOICE',
  'MULTI_CHOICE',
]
const SEPARATOR = ' · '
const ATTRIBUTABLE = new Set<MemoField['type']>(['TEXT', 'NUMBER', 'AMOUNT', 'DATE', 'UNIT', 'PERSON', 'CHOICE'])

/**
 * The Form tab (design E01 §10 flow A4): the fields in order, moved with buttons (no dragging), each edited in a dialog,
 * beside a live preview of the form authors will fill in. Read-only unless the version is a draft ([onChange] set).
 */
export function FieldsTab({
  fields,
  onChange,
  errors,
}: {
  fields: MemoField[]
  onChange?: (fields: MemoField[]) => void
  errors: Record<string, string>
}) {
  const intl = useIntl()
  const [editing, setEditing] = useState<number | 'new'>()
  const [preview, setPreview] = useState<MetadataState>({ values: {}, labels: {} })
  const move = (from: number, to: number) => {
    const next = [...fields]
    const [item] = next.splice(from, 1)
    if (item) next.splice(to, 0, item)
    onChange?.(next)
  }
  const describe = (field: MemoField) =>
    [
      intl.formatMessage(tm[field.type]),
      field.key,
      field.required ? intl.formatMessage(tm.fieldRequired) : undefined,
      field.attribute ? intl.formatMessage(tm.fieldAttribute) : undefined,
    ]
      .filter(Boolean)
      .join(SEPARATOR)
  const fieldErrors = fields.map((_, i) =>
    Object.entries(errors)
      .filter(([k]) => k.startsWith(`fields/${String(i)}/`))
      .map(([, v]) => v),
  )

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <section aria-labelledby="fields-title" className="flex flex-col gap-3">
        <h2 id="fields-title" className="sr-only">
          {intl.formatMessage(tm.tabForm)}
        </h2>
        <p className="text-sm text-muted-foreground">{intl.formatMessage(tm.fieldsLead)}</p>
        {errors.fields ? <p className="text-sm text-destructive">{errors.fields}</p> : null}
        {fields.length === 0 ? <p className="text-sm">{intl.formatMessage(tm.noFields)}</p> : null}
        <ol className="flex flex-col gap-2">
          {fields.map((field, i) => (
            <li key={field.key} className="flex flex-wrap items-center gap-2 rounded-md border p-2">
              <span className="w-6 text-center text-sm text-muted-foreground">{i + 1}</span>
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="font-medium">{field.label}</span>
                <span className="text-xs text-muted-foreground">{describe(field)}</span>
                {fieldErrors[i]?.length ? (
                  <span className="text-xs text-destructive">{fieldErrors[i].join(' ')}</span>
                ) : null}
              </span>
              {onChange ? (
                <span className="flex gap-1">
                  <Button
                    variant="outline"
                    size="icon"
                    disabled={i === 0}
                    aria-label={intl.formatMessage(tmv.moveUp, { name: field.label })}
                    onClick={() => {
                      move(i, i - 1)
                    }}
                  >
                    <ArrowUp aria-hidden="true" />
                  </Button>
                  <Button
                    variant="outline"
                    size="icon"
                    disabled={i === fields.length - 1}
                    aria-label={intl.formatMessage(tmv.moveDown, { name: field.label })}
                    onClick={() => {
                      move(i, i + 1)
                    }}
                  >
                    <ArrowDown aria-hidden="true" />
                  </Button>
                  <Button
                    variant="outline"
                    size="icon"
                    aria-label={intl.formatMessage(tmv.edit, { name: field.label })}
                    onClick={() => {
                      setEditing(i)
                    }}
                  >
                    <Pencil aria-hidden="true" />
                  </Button>
                  <Button
                    variant="outline"
                    size="icon"
                    aria-label={intl.formatMessage(tmv.remove, { name: field.label })}
                    onClick={() => {
                      onChange(fields.filter((_, j) => j !== i))
                    }}
                  >
                    <X aria-hidden="true" />
                  </Button>
                </span>
              ) : null}
            </li>
          ))}
        </ol>
        {onChange ? (
          <Button
            variant="outline"
            className="self-start"
            onClick={() => {
              setEditing('new')
            }}
          >
            <Plus aria-hidden="true" />
            {intl.formatMessage(tm.addField)}
          </Button>
        ) : null}
      </section>
      <section aria-labelledby="preview-title" className="rounded-md border bg-muted/30 p-4">
        <h2 id="preview-title" className="mb-3 text-base font-semibold">
          {intl.formatMessage(tm.preview)}
        </h2>
        <MetadataFields
          fields={fields}
          state={preview}
          onChange={(key, value, label) => {
            setPreview((s) => ({
              values: { ...s.values, [key]: value },
              labels: label ? { ...s.labels, [key]: label } : s.labels,
            }))
          }}
        />
      </section>
      {editing !== undefined && onChange ? (
        <FieldDialog
          field={editing === 'new' ? undefined : fields[editing]}
          taken={fields.filter((_, j) => j !== editing).map((f) => f.key)}
          onClose={() => {
            setEditing(undefined)
          }}
          onSave={(field) => {
            onChange(editing === 'new' ? [...fields, field] : fields.map((f, j) => (j === editing ? field : f)))
            setEditing(undefined)
          }}
        />
      ) : null}
    </div>
  )
}

/** "key = label" lines to choices and back. */
function choicesText(field?: MemoField): string {
  return (field?.rules.choices ?? []).map((c) => `${c.key} = ${c.label}`).join('\n')
}

function parseChoices(text: string) {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const at = line.indexOf('=')
      return at < 0
        ? { key: line.toLowerCase(), label: line }
        : { key: line.slice(0, at).trim(), label: line.slice(at + 1).trim() }
    })
}

function decimalOrNull(text: string): number | null {
  const n = Number(text)
  return text.trim() === '' || !Number.isFinite(n) ? null : n
}

function FieldDialog({
  field,
  taken,
  onClose,
  onSave,
}: {
  field?: MemoField
  taken: string[]
  onClose: () => void
  onSave: (field: MemoField) => void
}) {
  const intl = useIntl()
  const [label, setLabel] = useState(field?.label ?? '')
  const [key, setKey] = useState(field?.key ?? '')
  const [help, setHelp] = useState(field?.help ?? '')
  const [type, setType] = useState<MemoField['type']>(field?.type ?? 'TEXT')
  const [required, setRequired] = useState(field?.required ?? false)
  const [attribute, setAttribute] = useState(field?.attribute ?? false)
  const [inbox, setInbox] = useState(field?.inbox ?? false)
  const [maxLength, setMaxLength] = useState(field?.rules.maxLength == null ? '' : String(field.rules.maxLength))
  const [min, setMin] = useState(field?.rules.min == null ? '' : String(field.rules.min))
  const [max, setMax] = useState(field?.rules.max == null ? '' : String(field.rules.max))
  const [decimals, setDecimals] = useState(field?.rules.decimals == null ? '' : String(field.rules.decimals))
  const [currencies, setCurrencies] = useState((field?.rules.currencies ?? ['BDT']).join(', '))
  const [choices, setChoices] = useState(choicesText(field))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const text = type === 'TEXT' || type === 'LONG_TEXT'
  const numeric = type === 'NUMBER' || type === 'AMOUNT'
  const chosen = type === 'CHOICE' || type === 'MULTI_CHOICE'

  const save = () => {
    const problems: Record<string, string> = {}
    if (!label.trim()) problems.label = intl.formatMessage(tm.required)
    if (!/^[a-z][a-zA-Z0-9]{0,39}$/.test(key)) problems.key = intl.formatMessage(tm.fieldKeyHint)
    else if (taken.includes(key)) problems.key = intl.formatMessage(tm.fieldKeyHint)
    setErrors(problems)
    if (Object.keys(problems).length) return
    onSave({
      key,
      label: label.trim(),
      help: help.trim() || null,
      type,
      required,
      attribute: attribute && ATTRIBUTABLE.has(type),
      inbox,
      rules: {
        maxLength: text ? decimalOrNull(maxLength) : null,
        min: numeric ? decimalOrNull(min) : null,
        max: numeric ? decimalOrNull(max) : null,
        decimals: numeric ? decimalOrNull(decimals) : null,
        currencies:
          type === 'AMOUNT'
            ? currencies
                .split(',')
                .map((c) => c.trim().toUpperCase())
                .filter(Boolean)
            : [],
        choices: chosen ? parseChoices(choices) : [],
      },
    })
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(field ? tm.editField : tm.addField)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(tm.cancel)}
          </Button>
          <Button onClick={save}>{intl.formatMessage(tm.saveField)}</Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={intl.formatMessage(tm.fieldLabel)} required error={errors.label}>
          {(p) => (
            <Input
              {...p}
              maxLength={100}
              value={label}
              onChange={(e) => {
                setLabel(e.target.value)
                // A new field's key follows its label until it is edited by hand.
                if (!field && (key === '' || key === keyOf(label))) setKey(keyOf(e.target.value))
              }}
            />
          )}
        </Field>
        <Field
          label={intl.formatMessage(tm.fieldKey)}
          hint={intl.formatMessage(tm.fieldKeyHint)}
          required
          error={errors.key}
        >
          {(p) => (
            <Input
              {...p}
              maxLength={40}
              value={key}
              onChange={(e) => {
                setKey(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(tm.fieldType)}>
          {(p) => (
            <Select
              {...p}
              value={type}
              onChange={(e) => {
                setType(e.target.value as MemoField['type'])
              }}
            >
              {TYPES.map((t) => (
                <option key={t} value={t}>
                  {intl.formatMessage(tm[t])}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label={intl.formatMessage(tm.fieldHelp)}>
          {(p) => (
            <Input
              {...p}
              maxLength={300}
              value={help}
              onChange={(e) => {
                setHelp(e.target.value)
              }}
            />
          )}
        </Field>
        {text ? (
          <Field label={intl.formatMessage(tm.maxLength)}>
            {(p) => (
              <Input
                {...p}
                inputMode="numeric"
                className="w-28"
                value={maxLength}
                onChange={(e) => {
                  setMaxLength(e.target.value)
                }}
              />
            )}
          </Field>
        ) : null}
        {numeric ? (
          <div className="flex flex-wrap gap-3">
            <NumberSetting label={intl.formatMessage(tm.min)} value={min} onChange={setMin} />
            <NumberSetting label={intl.formatMessage(tm.max)} value={max} onChange={setMax} />
            <NumberSetting label={intl.formatMessage(tm.decimals)} value={decimals} onChange={setDecimals} />
          </div>
        ) : null}
        {type === 'AMOUNT' ? (
          <Field label={intl.formatMessage(tm.currencies)} hint={intl.formatMessage(tm.currenciesHint)}>
            {(p) => (
              <Input
                {...p}
                value={currencies}
                onChange={(e) => {
                  setCurrencies(e.target.value)
                }}
              />
            )}
          </Field>
        ) : null}
        {chosen ? (
          <Field label={intl.formatMessage(tm.choices)} hint={intl.formatMessage(tm.choicesHint)}>
            {(p) => (
              <Textarea
                {...p}
                rows={5}
                value={choices}
                onChange={(e) => {
                  setChoices(e.target.value)
                }}
              />
            )}
          </Field>
        ) : null}
        <label className="flex min-h-10 items-center gap-2 text-sm">
          <Checkbox
            checked={required}
            onChange={(e) => {
              setRequired(e.target.checked)
            }}
          />
          {intl.formatMessage(tm.fieldRequired)}
        </label>
        {ATTRIBUTABLE.has(type) ? (
          <label className="flex min-h-10 items-center gap-2 text-sm">
            <Checkbox
              checked={attribute}
              onChange={(e) => {
                setAttribute(e.target.checked)
              }}
            />
            {intl.formatMessage(tm.fieldAttribute)}
          </label>
        ) : null}
        <label className="flex min-h-10 items-center gap-2 text-sm">
          <Checkbox
            checked={inbox}
            onChange={(e) => {
              setInbox(e.target.checked)
            }}
          />
          {intl.formatMessage(tm.fieldInbox)}
        </label>
      </div>
    </Dialog>
  )
}

/** A key from a label: its words in camel case, letters and digits only ("Facility amount" → facilityAmount). */
function keyOf(label: string): string {
  const words = label
    .normalize('NFKD')
    .replace(/[^A-Za-z0-9 ]/g, ' ')
    .trim()
    .split(/\s+/)
    .filter(Boolean)
  const key = words
    .map((w, i) => (i === 0 ? w.toLowerCase() : w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()))
    .join('')
  return /^[a-z]/.test(key) ? key.slice(0, 40) : ''
}

function NumberSetting({
  label,
  value,
  onChange,
}: {
  label: string
  value: string
  onChange: (value: string) => void
}) {
  return (
    <Field label={label}>
      {(p) => (
        <Input
          {...p}
          inputMode="decimal"
          className="w-32"
          value={value}
          onChange={(e) => {
            onChange(e.target.value)
          }}
        />
      )}
    </Field>
  )
}
