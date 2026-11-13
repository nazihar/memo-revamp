import { useQuery } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { getBuilderCatalogueOptions } from '@/api/generated/@tanstack/react-query.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import {
  comparisonsFor,
  parseCondition,
  printCondition,
  type AttributeKind,
  type Comparison,
  type ConditionRow,
  type SimpleCondition,
  type Value,
} from './conditions'

const cm = defineMessages({
  join: { id: 'workflows.builder.condition.join', defaultMessage: 'Holds when' },
  joinAll: { id: 'workflows.builder.condition.join.all', defaultMessage: 'all of these are true' },
  joinAny: { id: 'workflows.builder.condition.join.any', defaultMessage: 'any of these is true' },
  attribute: { id: 'workflows.builder.condition.attribute', defaultMessage: 'Attribute' },
  comparison: { id: 'workflows.builder.condition.comparison', defaultMessage: 'Comparison' },
  value: { id: 'workflows.builder.condition.value', defaultMessage: 'Value' },
  valueList: { id: 'workflows.builder.condition.valueList', defaultMessage: 'Values, separated by commas' },
  addRow: { id: 'workflows.builder.condition.addRow', defaultMessage: 'Add a comparison' },
  asCel: { id: 'workflows.builder.condition.asCel', defaultMessage: 'Write it as CEL' },
  asSimple: { id: 'workflows.builder.condition.asSimple', defaultMessage: 'Use the simple form' },
  cel: { id: 'workflows.builder.condition.cel', defaultMessage: 'Condition (CEL)' },
  celHint: {
    id: 'workflows.builder.condition.celHint',
    defaultMessage: 'The simple form cannot show this condition; it is kept exactly as written.',
  },
  noAttributes: {
    id: 'workflows.builder.condition.noAttributes',
    defaultMessage: 'Items of this kind have no attributes a condition can compare. Write the condition as CEL.',
  },
  yes: { id: 'workflows.builder.condition.true', defaultMessage: 'Yes' },
  no: { id: 'workflows.builder.condition.false', defaultMessage: 'No' },
})

const cmv = defineMessages<{ removeRow: { n: number }; row: { n: number } }>({
  removeRow: { id: 'workflows.builder.condition.removeRow', defaultMessage: 'Remove comparison {n}' },
  row: { id: 'workflows.builder.condition.row', defaultMessage: 'Comparison {n}' },
})

const opLabels = defineMessages({
  '==': { id: 'workflows.builder.op.eq', defaultMessage: 'is' },
  '!=': { id: 'workflows.builder.op.ne', defaultMessage: 'is not' },
  '<': { id: 'workflows.builder.op.lt', defaultMessage: 'is less than' },
  '<=': { id: 'workflows.builder.op.le', defaultMessage: 'is at most' },
  '>': { id: 'workflows.builder.op.gt', defaultMessage: 'is more than' },
  '>=': { id: 'workflows.builder.op.ge', defaultMessage: 'is at least' },
  in: { id: 'workflows.builder.op.in', defaultMessage: 'is one of' },
})

const attributeLabels = defineMessages({
  classificationRank: { id: 'workflows.builder.attribute.classificationRank', defaultMessage: 'Classification rank' },
  memoTypeId: { id: 'workflows.builder.attribute.memoTypeId', defaultMessage: 'Memo type (id)' },
})

/** The attributes conditions may compare, from every item type the catalogue lists (the validator uses all of them). */
function useAttributes(): Record<string, AttributeKind> {
  const catalogue = useQuery(getBuilderCatalogueOptions())
  const kinds: Record<string, AttributeKind> = {}
  for (const subject of catalogue.data?.subjectTypes ?? []) {
    for (const a of subject.attributes) kinds[a.name] = a.type as AttributeKind
  }
  return kinds
}

function defaultValue(kind: AttributeKind | undefined, op: Comparison): Value {
  if (op === 'in') return []
  if (kind === 'bool') return true
  if (kind === 'string') return ''
  return 0
}

/**
 * A branch condition (design E06 §3): attribute · comparison · value rows joined by "all of" or "any of", written as
 * CEL; or the CEL itself. A condition the simple form cannot read opens as CEL and is never rewritten.
 */
export function ConditionEditor({
  text,
  onChange,
}: {
  text: string
  onChange: (text: string, declare: { name: string; type: string }[]) => void
}) {
  const intl = useIntl()
  const kinds = useAttributes()
  const names = Object.keys(kinds).filter((n) => comparisonsFor(kinds[n] ?? 'timestamp').length > 0)
  const parsed = parseCondition(text)
  const [mode, setMode] = useState<'simple' | 'cel'>(parsed || text.trim() === '' ? 'simple' : 'cel')
  const label = (name: string) =>
    name in attributeLabels ? intl.formatMessage(attributeLabels[name as keyof typeof attributeLabels]) : name

  const write = (condition: SimpleCondition) => {
    const declare = [...new Set(condition.rows.map((r) => r.attribute))].map((name) => ({
      name,
      type: kinds[name] ?? 'string',
    }))
    onChange(printCondition(condition, kinds), declare)
  }

  if (mode === 'cel' || names.length === 0) {
    return (
      <div className="flex flex-col gap-2">
        <Field
          label={intl.formatMessage(cm.cel)}
          {...(parsed ? {} : { hint: intl.formatMessage(names.length === 0 ? cm.noAttributes : cm.celHint) })}
        >
          {(p) => (
            <Input
              {...p}
              dir="ltr"
              className="font-mono text-xs"
              value={text}
              onChange={(e) => {
                onChange(e.target.value, [])
              }}
            />
          )}
        </Field>
        {names.length > 0 ? (
          <div>
            <Button
              variant="outline"
              disabled={!parsed && text.trim() !== ''}
              onClick={() => {
                setMode('simple')
              }}
            >
              {intl.formatMessage(cm.asSimple)}
            </Button>
          </div>
        ) : null}
      </div>
    )
  }

  const first = names[0] ?? ''
  const condition: SimpleCondition = parsed ?? {
    join: 'all',
    rows: [
      {
        attribute: first,
        op: comparisonsFor(kinds[first] ?? 'int')[0] ?? '==',
        value: defaultValue(kinds[first], '=='),
      },
    ],
  }
  const setRow = (i: number, row: ConditionRow) => {
    write({ ...condition, rows: condition.rows.map((r, j) => (j === i ? row : r)) })
  }
  return (
    <div className="flex flex-col gap-2">
      {condition.rows.length > 1 ? (
        <Field label={intl.formatMessage(cm.join)}>
          {(p) => (
            <Select
              {...p}
              value={condition.join}
              onChange={(e) => {
                write({ ...condition, join: e.target.value as 'all' | 'any' })
              }}
            >
              <option value="all">{intl.formatMessage(cm.joinAll)}</option>
              <option value="any">{intl.formatMessage(cm.joinAny)}</option>
            </Select>
          )}
        </Field>
      ) : null}
      {condition.rows.map((row, i) => {
        const kind = kinds[row.attribute]
        const ops = comparisonsFor(kind ?? 'string')
        return (
          <fieldset key={String(i)} className="grid gap-2 sm:grid-cols-[1fr_1fr_1fr_auto] sm:items-end">
            <legend className="sr-only">{intl.formatMessage(cmv.row, { n: i + 1 })}</legend>
            <Field label={intl.formatMessage(cm.attribute)}>
              {(p) => (
                <Select
                  {...p}
                  value={row.attribute}
                  onChange={(e) => {
                    const attribute = e.target.value
                    const op = comparisonsFor(kinds[attribute] ?? 'string')[0] ?? '=='
                    setRow(i, { attribute, op, value: defaultValue(kinds[attribute], op) })
                  }}
                >
                  {names.includes(row.attribute) ? null : <option value={row.attribute}>{row.attribute}</option>}
                  {names.map((n) => (
                    <option key={n} value={n}>
                      {label(n)}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
            <Field label={intl.formatMessage(cm.comparison)}>
              {(p) => (
                <Select
                  {...p}
                  value={row.op}
                  onChange={(e) => {
                    const op = e.target.value as Comparison
                    setRow(i, {
                      ...row,
                      op,
                      value: op === 'in' || row.op === 'in' ? defaultValue(kind, op) : row.value,
                    })
                  }}
                >
                  {ops.map((op) => (
                    <option key={op} value={op}>
                      {intl.formatMessage(opLabels[op])}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
            <ValueInput
              key={`${row.attribute}:${row.op}`}
              kind={kind}
              row={row}
              onChange={(value) => {
                setRow(i, { ...row, value })
              }}
            />
            <Button
              variant="ghost"
              size="icon"
              aria-label={intl.formatMessage(cmv.removeRow, { n: i + 1 })}
              disabled={condition.rows.length === 1}
              onClick={() => {
                write({ ...condition, rows: condition.rows.filter((_, j) => j !== i) })
              }}
            >
              <Trash2 aria-hidden="true" />
            </Button>
          </fieldset>
        )
      })}
      <div className="flex flex-wrap gap-2">
        <Button
          variant="outline"
          onClick={() => {
            const op = comparisonsFor(kinds[first] ?? 'int')[0] ?? '=='
            write({
              ...condition,
              rows: [...condition.rows, { attribute: first, op, value: defaultValue(kinds[first], op) }],
            })
          }}
        >
          <Plus aria-hidden="true" />
          {intl.formatMessage(cm.addRow)}
        </Button>
        <Button
          variant="ghost"
          onClick={() => {
            if (!parsed) write(condition)
            setMode('cel')
          }}
        >
          {intl.formatMessage(cm.asCel)}
        </Button>
      </div>
    </div>
  )
}

function ValueInput({
  kind,
  row,
  onChange,
}: {
  kind: AttributeKind | undefined
  row: ConditionRow
  onChange: (value: Value) => void
}) {
  const intl = useIntl()
  if (row.op === 'in') {
    const list = Array.isArray(row.value) ? row.value : []
    return (
      <Field label={intl.formatMessage(cm.valueList)}>
        {(p) => (
          <Input
            {...p}
            dir="auto"
            value={list.join(', ')}
            onChange={(e) => {
              onChange(
                e.target.value
                  .split(',')
                  .map((v) => v.trim())
                  .filter((v) => v !== ''),
              )
            }}
          />
        )}
      </Field>
    )
  }
  if (kind === 'bool') {
    return (
      <Field label={intl.formatMessage(cm.value)}>
        {(p) => (
          <Select
            {...p}
            value={row.value === false ? 'false' : 'true'}
            onChange={(e) => {
              onChange(e.target.value === 'true')
            }}
          >
            <option value="true">{intl.formatMessage(cm.yes)}</option>
            <option value="false">{intl.formatMessage(cm.no)}</option>
          </Select>
        )}
      </Field>
    )
  }
  return <ScalarInput kind={kind} value={row.value} onChange={onChange} />
}

/**
 * A number or text value. What is typed is kept as typed, so a number can be cleared and retyped; it reaches the
 * condition once it reads as a number.
 */
function ScalarInput({
  kind,
  value,
  onChange,
}: {
  kind: AttributeKind | undefined
  value: Value
  onChange: (value: Value) => void
}) {
  const intl = useIntl()
  const [draft, setDraft] = useState(String(value))
  const numeric = kind === 'int' || kind === 'double'
  return (
    <Field label={intl.formatMessage(cm.value)}>
      {(p) => (
        <Input
          {...p}
          dir="auto"
          {...(numeric ? { type: 'number', step: kind === 'int' ? 1 : 'any' } : {})}
          value={draft}
          onChange={(e) => {
            const raw = e.target.value
            setDraft(raw)
            if (!numeric) {
              onChange(raw)
              return
            }
            const n = Number(raw)
            if (raw !== '' && !Number.isNaN(n)) onChange(kind === 'int' ? Math.trunc(n) : n)
          }}
        />
      )}
    </Field>
  )
}
