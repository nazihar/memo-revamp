import { useState } from 'react'
import { useIntl } from 'react-intl'
import { Checkbox, Input } from '@/components/ui/form-controls'
import type { TermValues } from '@/features/terms/terms'
import { effects, meaningLabels } from './labels'
import { ft, ftt, ftv } from './messages'
import { OUTCOME_KINDS, type OutcomeKind, type TypeSettings } from './settings'

type Outcomes = TypeSettings['outcomes']

/** Kinds numbered as resolutions when switched on, as the presets do (ADR-0056). */
const NUMBERED_BY_DEFAULT: OutcomeKind[] = ['APPROVED', 'APPROVED_WITH_CONDITIONS', 'REJECTED']

/**
 * The Outcomes tab (flow J5): which decisions the committee records, the organisation's label for each and whether it
 * is numbered as a resolution. Meanings are fixed in code; labels are the organisation's. Read-only unless a draft.
 */
export function OutcomesTab({
  outcomes,
  onChange,
  errors,
  terms,
}: {
  outcomes: Outcomes
  onChange?: (outcomes: Outcomes) => void
  errors: Record<string, string>
  terms: TermValues
}) {
  const intl = useIntl()
  // A kind switched off and on again gets back the label it had.
  const [remembered, setRemembered] = useState(() => new Map(outcomes.map((o) => [o.kind, o])))
  const meaning = (kind: OutcomeKind) => intl.formatMessage(meaningLabels[kind], terms)
  const index = (kind: OutcomeKind) => outcomes.findIndex((o) => o.kind === kind)

  const toggle = (kind: OutcomeKind, on: boolean) => {
    if (!onChange) return
    if (!on) {
      onChange(outcomes.filter((o) => o.kind !== kind))
      return
    }
    const back = remembered.get(kind) ?? {
      kind,
      label: meaning(kind),
      resolution: NUMBERED_BY_DEFAULT.includes(kind),
    }
    // Kept in the fixed order of the kinds.
    onChange(OUTCOME_KINDS.flatMap((k) => (k === kind ? [back] : outcomes.filter((o) => o.kind === k))))
  }
  const update = (kind: OutcomeKind, next: Partial<Outcomes[number]>) => {
    if (!onChange) return
    const changed = outcomes.map((o) => (o.kind === kind ? { ...o, ...next } : o))
    setRemembered(new Map([...remembered, ...changed.map((o) => [o.kind, o] as const)]))
    onChange(changed)
  }

  return (
    <fieldset disabled={!onChange} className="flex max-w-4xl flex-col gap-3">
      <legend className="sr-only">{intl.formatMessage(ft.tabOutcomes)}</legend>
      <p className="text-sm text-muted-foreground">{intl.formatMessage(ftt.outcomesLead, terms)}</p>
      {errors.outcomes ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {errors.outcomes}
        </p>
      ) : null}
      <div className="overflow-x-auto">
        <table className="w-full min-w-[36rem] text-sm">
          <caption className="sr-only">{intl.formatMessage(ft.outcomesCaption)}</caption>
          <thead>
            <tr className="text-start text-muted-foreground">
              <th scope="col" className="w-16 px-2 py-2 text-start font-medium">
                {intl.formatMessage(ft.colOn)}
              </th>
              <th scope="col" className="px-2 py-2 text-start font-medium">
                {intl.formatMessage(ft.colMeaning)}
              </th>
              <th scope="col" className="px-2 py-2 text-start font-medium">
                {intl.formatMessage(ft.colLabel)}
              </th>
              <th scope="col" className="px-2 py-2 text-start font-medium">
                {intl.formatMessage(ft.colResolution)}
              </th>
            </tr>
          </thead>
          <tbody>
            {OUTCOME_KINDS.map((kind) => {
              const i = index(kind)
              const outcome = i >= 0 ? outcomes[i] : undefined
              const name = meaning(kind)
              const error =
                i >= 0 ? (errors[`outcomes[${String(i)}].label`] ?? errors[`outcomes[${String(i)}].kind`]) : undefined
              return (
                <tr key={kind} className="border-t align-top">
                  <td className="px-2 py-2">
                    <Checkbox
                      className="mt-2.5 size-5"
                      aria-label={intl.formatMessage(ftv.outcomeUse, { meaning: name })}
                      checked={Boolean(outcome)}
                      onChange={(e) => {
                        toggle(kind, e.target.checked)
                      }}
                    />
                  </td>
                  <th scope="row" className="px-2 py-2 pt-4 text-start font-normal">
                    {name}
                  </th>
                  <td className="px-2 py-2">
                    <Input
                      maxLength={60}
                      aria-label={intl.formatMessage(ftv.outcomeLabel, { meaning: name })}
                      aria-invalid={error ? true : undefined}
                      disabled={!outcome}
                      value={outcome?.label ?? remembered.get(kind)?.label ?? name}
                      onChange={(e) => {
                        update(kind, { label: e.target.value })
                      }}
                    />
                    {error ? <p className="mt-1 text-xs font-medium text-destructive">{error}</p> : null}
                  </td>
                  <td className="px-2 py-2">
                    <Checkbox
                      className="mt-2.5 size-5"
                      aria-label={intl.formatMessage(ftv.outcomeResolution, { meaning: name })}
                      disabled={!outcome}
                      checked={outcome?.resolution ?? false}
                      onChange={(e) => {
                        update(kind, { resolution: e.target.checked })
                      }}
                    />
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <details className="rounded-md border p-3 text-sm">
        <summary className="min-h-8 cursor-pointer font-medium">{intl.formatMessage(ft.outcomesHelp)}</summary>
        <dl className="mt-2 grid gap-x-4 gap-y-1 sm:grid-cols-[minmax(0,14rem)_minmax(0,1fr)]">
          {OUTCOME_KINDS.map((kind) => (
            <div key={kind} className="contents">
              <dt className="font-medium">{meaning(kind)}</dt>
              <dd className="text-muted-foreground">{intl.formatMessage(effects[kind], terms)}</dd>
            </div>
          ))}
        </dl>
      </details>
    </fieldset>
  )
}
