import { ArrowDown, ArrowUp, Plus, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useIntl } from 'react-intl'
import { previewNumbering } from '@/api/generated/sdk.gen'
import type { NumberPart, Numbering } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { tm, tmv } from './messages'

const KINDS: NumberPart['kind'][] = ['TYPE_CODE', 'UNIT_CODE', 'YEAR', 'FINANCIAL_YEAR', 'SEQUENCE']
/** How a year looks with 4 or 2 digits: numbers, the same in every language. */
const YEAR_SAMPLE = { long: '2026', short: '26' }
const MONTHS = Array.from({ length: 12 }, (_, i) => i + 1)

const PART_LABELS = {
  TEXT: tm.partText,
  TYPE_CODE: tm.partTYPE_CODE,
  UNIT_CODE: tm.partUNIT_CODE,
  YEAR: tm.partYEAR,
  FINANCIAL_YEAR: tm.partFINANCIAL_YEAR,
  SEQUENCE: tm.partSEQUENCE,
}

function partLabels(kind: NumberPart['kind']) {
  return PART_LABELS[kind]
}

/**
 * The Numbering tab (design E01 §10 flow A5, ADR-0044): the parts of the number in order, the series it counts in, when it
 * restarts and whether it counts per unit, when memos are numbered, and a sample from the server's own renderer. Read-only
 * unless the version is a draft ([onChange] set).
 */
export function NumberingTab({
  numbering,
  code,
  onChange,
  errors,
}: {
  numbering: Numbering
  code: string
  onChange?: (numbering: Numbering) => void
  errors: Record<string, string>
}) {
  const intl = useIntl()
  const [sample, setSample] = useState<{ samples: string[]; problems: string[] }>()
  const set = (next: Partial<Numbering>) => onChange?.({ ...numbering, ...next })
  const parts = numbering.parts
  const setPart = (i: number, part: NumberPart) => {
    set({ parts: parts.map((p, j) => (i === j ? part : p)) })
  }
  const move = (from: number, to: number) => {
    const next = [...parts]
    const [item] = next.splice(from, 1)
    if (item) next.splice(to, 0, item)
    set({ parts: next })
  }

  const body = JSON.stringify(numbering)
  useEffect(() => {
    const timer = setTimeout(() => {
      previewNumbering({ body: { numbering, typeCode: code || 'CODE' }, throwOnError: true })
        .then((r) => {
          setSample({ samples: r.data.samples, problems: r.data.problems.map((p) => p.message ?? p.code ?? '') })
        })
        .catch(() => {
          setSample(undefined)
        })
    }, 300)
    return () => {
      clearTimeout(timer)
    }
    // The body string stands for the numbering object, so equal numberings do not ask again.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [body, code])

  return (
    <fieldset disabled={!onChange} className="flex max-w-3xl flex-col gap-4">
      <p className="text-sm text-muted-foreground">{intl.formatMessage(tm.numberingLead)}</p>
      <section aria-labelledby="parts-title" className="flex flex-col gap-2">
        <h2 id="parts-title" className="text-sm font-medium">
          {intl.formatMessage(tm.parts)}
        </h2>
        <ol className="flex flex-col gap-2">
          {parts.map((part, i) => {
            const name = intl.formatMessage(partLabels(part.kind))
            return (
              <li key={`${String(i)}-${part.kind}`} className="flex flex-wrap items-center gap-2 rounded-md border p-2">
                <span className="w-32 text-sm font-medium">{name}</span>
                {part.kind === 'TEXT' ? (
                  <Input
                    aria-label={name}
                    className="w-40"
                    maxLength={20}
                    value={part.text ?? ''}
                    onChange={(e) => {
                      setPart(i, { ...part, text: e.target.value })
                    }}
                  />
                ) : null}
                {part.kind === 'YEAR' ? (
                  <Select
                    aria-label={intl.formatMessage(tm.digits)}
                    className="w-24"
                    value={String(part.digits ?? 4)}
                    onChange={(e) => {
                      setPart(i, { ...part, digits: Number(e.target.value) })
                    }}
                  >
                    <option value="4">{YEAR_SAMPLE.long}</option>
                    <option value="2">{YEAR_SAMPLE.short}</option>
                  </Select>
                ) : null}
                {part.kind === 'FINANCIAL_YEAR' ? (
                  <Select
                    aria-label={name}
                    className="w-28"
                    value={part.style ?? 'LONG'}
                    onChange={(e) => {
                      setPart(i, { ...part, style: e.target.value as 'LONG' | 'SHORT' })
                    }}
                  >
                    <option value="LONG">{intl.formatMessage(tm.styleLong)}</option>
                    <option value="SHORT">{intl.formatMessage(tm.styleShort)}</option>
                  </Select>
                ) : null}
                {part.kind === 'SEQUENCE' ? (
                  <label className="flex items-center gap-2 text-sm">
                    {intl.formatMessage(tm.digits)}
                    <Input
                      inputMode="numeric"
                      className="w-20"
                      value={String(part.digits ?? 4)}
                      onChange={(e) => {
                        setPart(i, { ...part, digits: Number.parseInt(e.target.value, 10) || 1 })
                      }}
                    />
                  </label>
                ) : null}
                {onChange ? (
                  <span className="ms-auto flex gap-1">
                    <Button
                      variant="outline"
                      size="icon"
                      disabled={i === 0}
                      aria-label={intl.formatMessage(tmv.moveUp, { name })}
                      onClick={() => {
                        move(i, i - 1)
                      }}
                    >
                      <ArrowUp aria-hidden="true" />
                    </Button>
                    <Button
                      variant="outline"
                      size="icon"
                      disabled={i === parts.length - 1}
                      aria-label={intl.formatMessage(tmv.moveDown, { name })}
                      onClick={() => {
                        move(i, i + 1)
                      }}
                    >
                      <ArrowDown aria-hidden="true" />
                    </Button>
                    <Button
                      variant="outline"
                      size="icon"
                      aria-label={intl.formatMessage(tmv.remove, { name })}
                      onClick={() => {
                        set({ parts: parts.filter((_, j) => j !== i) })
                      }}
                    >
                      <X aria-hidden="true" />
                    </Button>
                  </span>
                ) : null}
              </li>
            )
          })}
        </ol>
        {onChange ? (
          <div className="flex flex-wrap gap-2">
            {(['TEXT', ...KINDS] as NumberPart['kind'][]).map((kind) => (
              <Button
                key={kind}
                variant="outline"
                onClick={() => {
                  const part: NumberPart =
                    kind === 'TEXT'
                      ? { kind, text: '-' }
                      : kind === 'YEAR'
                        ? { kind, digits: 4 }
                        : kind === 'SEQUENCE'
                          ? { kind, digits: 4 }
                          : kind === 'FINANCIAL_YEAR'
                            ? { kind, style: 'LONG' }
                            : { kind }
                  set({ parts: [...parts, part] })
                }}
              >
                <Plus aria-hidden="true" />
                {intl.formatMessage(partLabels(kind))}
              </Button>
            ))}
          </div>
        ) : null}
      </section>

      <div className="grid gap-4 sm:grid-cols-2">
        <Field
          label={intl.formatMessage(tm.series)}
          hint={intl.formatMessage(tm.seriesHint)}
          error={errors['numbering/series']}
        >
          {(p) => (
            <Input
              {...p}
              maxLength={50}
              value={numbering.series}
              onChange={(e) => {
                set({ series: e.target.value.toLowerCase() })
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(tm.reset)} error={errors['numbering/reset']}>
          {(p) => (
            <Select
              {...p}
              value={numbering.reset}
              onChange={(e) => {
                set({ reset: e.target.value as Numbering['reset'] })
              }}
            >
              <option value="NEVER">{intl.formatMessage(tm.resetNever)}</option>
              <option value="YEARLY">{intl.formatMessage(tm.resetYearly)}</option>
              <option value="FINANCIAL_YEAR">{intl.formatMessage(tm.resetFinancial)}</option>
            </Select>
          )}
        </Field>
        <Field label={intl.formatMessage(tm.fyStart)}>
          {(p) => (
            <Select
              {...p}
              value={String(numbering.fyStartMonth)}
              onChange={(e) => {
                set({ fyStartMonth: Number(e.target.value) })
              }}
            >
              {MONTHS.map((month) => (
                <option key={month} value={month}>
                  {intl.formatDate(new Date(Date.UTC(2026, month - 1, 1)), { month: 'long', timeZone: 'UTC' })}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label={intl.formatMessage(tm.countPer)} error={errors['numbering/countPer']}>
          {(p) => (
            <Select
              {...p}
              value={numbering.countPer}
              onChange={(e) => {
                set({ countPer: e.target.value as Numbering['countPer'] })
              }}
            >
              <option value="SERIES">{intl.formatMessage(tm.countSeries)}</option>
              <option value="UNIT">{intl.formatMessage(tm.countUnit)}</option>
            </Select>
          )}
        </Field>
        <Field label={intl.formatMessage(tm.moment)}>
          {(p) => (
            <Select
              {...p}
              value={numbering.moment}
              onChange={(e) => {
                set({ moment: e.target.value as Numbering['moment'] })
              }}
            >
              <option value="ON_SUBMIT">{intl.formatMessage(tm.onSubmit)}</option>
              <option value="ON_APPROVAL">{intl.formatMessage(tm.onApproval)}</option>
            </Select>
          )}
        </Field>
      </div>

      <section aria-labelledby="sample-title" aria-live="polite" className="rounded-md border bg-muted/30 p-3">
        <h2 id="sample-title" className="text-sm font-medium">
          {intl.formatMessage(tm.sample)}
        </h2>
        {sample?.samples.length ? (
          <ul className="mt-1 flex flex-wrap gap-4 font-mono text-base" dir="ltr">
            {sample.samples.map((s) => (
              <li key={s}>{s}</li>
            ))}
          </ul>
        ) : null}
        {sample?.problems.length ? (
          <ul className="mt-1 list-disc ps-5 text-sm text-destructive">
            {sample.problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        ) : null}
        {errors['numbering/parts'] ? (
          <p className="mt-1 text-sm text-destructive">{errors['numbering/parts']}</p>
        ) : null}
      </section>
    </fieldset>
  )
}
