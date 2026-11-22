import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { listActionDefinitionsOptions, listForumTypePresetsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { ActionsPolicy, ForumTypePresetResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Checkbox, Select } from '@/components/ui/form-controls'
import { useTerms } from '@/features/terms/terms'
import { fam } from './actions-messages'
import { ft } from './messages'
import { NumberInput } from './NumberInput'

const DUE_CHANGES: ActionsPolicy['dueChange'][] = ['SECRETARY_AGREES', 'CHAIR_AGREES', 'OWNER_WITH_REASON']
const VERIFIERS: ActionsPolicy['verifiedBy'][] = ['SECRETARY', 'CHAIR']
const DEFAULTS: ActionsPolicy['defaultDue'][] = ['NEXT_MEETING', 'WORKING_DAYS']

/**
 * The committee type's Actions tab (flow AQ, design §1.13): amber within so many working days, the "no recent update"
 * rule (0 is off), who agrees a new date, who verifies, evidence on "Mark as done", the default due date, asking owners
 * for an update before the papers (0 is off), and the workflow the reminders and escalation follow, with a link to the
 * builder. A preset's values can be copied in. Changed on a draft ([onChange] set) and reviewed by a second
 * administrator like the rest of the type; the Changes tab lists what differs from the published version.
 */
export function ActionsTab({
  actions,
  onChange,
  errors,
}: {
  actions: ActionsPolicy
  onChange?: ((actions: ActionsPolicy) => void) | undefined
  errors: Record<string, string>
}) {
  const intl = useIntl()
  const terms = useTerms()
  const set = (next: Partial<ActionsPolicy>) => onChange?.({ ...actions, ...next })
  // A rule switched off is 0 working days; switched on again, it comes back with what it had.
  const [lastStale, setLastStale] = useState(actions.staleWorkingDays || 20)
  const [lastUpdates, setLastUpdates] = useState(actions.updateRequestWorkingDays || 5)
  const definitions = useQuery({ ...listActionDefinitionsOptions(), staleTime: 60_000 })
  const presets = useQuery({ ...listForumTypePresetsOptions(), enabled: Boolean(onChange), staleTime: 5 * 60_000 })
  const [preset, setPreset] = useState<ForumTypePresetResponse['key'] | ''>('')
  const [said, setSaid] = useState('')
  const known = (definitions.data ?? []).some((d) => d.key === actions.definitionKey)
  const err = (field: string) => errors[`actions/${field}`]

  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <p className="text-sm">{intl.formatMessage(fam.lead, terms)}</p>
      {onChange ? (
        <div className="flex flex-wrap items-end gap-2 rounded-md bg-muted/30 p-3">
          <Field label={intl.formatMessage(fam.preset)} hint={intl.formatMessage(fam.presetHint)}>
            {(p) => (
              <Select
                {...p}
                className="min-h-11 w-64"
                value={preset}
                onChange={(e) => {
                  setPreset(e.target.value as ForumTypePresetResponse['key'] | '')
                }}
              >
                <option value="">{intl.formatMessage(fam.presetChoose)}</option>
                {(presets.data ?? []).map((p) => (
                  <option key={p.key} value={p.key}>
                    {p.key in ft ? intl.formatMessage(ft[p.key]) : p.name}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Button
            variant="outline"
            className="min-h-11"
            disabled={!preset}
            onClick={() => {
              const chosen = presets.data?.find((p) => p.key === preset)?.settings.actions
              if (chosen) {
                set(chosen)
                setSaid(intl.formatMessage(fam.presetUsed))
              }
            }}
          >
            {intl.formatMessage(fam.presetUse)}
          </Button>
          <p aria-live="polite" className="w-full text-sm empty:hidden">
            {said}
          </p>
        </div>
      ) : null}
      <fieldset disabled={!onChange} className="flex flex-col gap-5">
        <legend className="sr-only">{intl.formatMessage(fam.tab)}</legend>
        <Field
          label={intl.formatMessage(fam.amber)}
          hint={intl.formatMessage(fam.amberHint)}
          error={err('amberWorkingDays')}
        >
          {(p) => (
            <span className="flex items-center gap-2">
              <NumberInput
                {...p}
                min={1}
                max={60}
                className="min-h-11"
                value={actions.amberWorkingDays}
                onChange={(v) => {
                  set({ amberWorkingDays: v ?? 0 })
                }}
              />
              <span aria-hidden="true">{intl.formatMessage(fam.workingDays)}</span>
            </span>
          )}
        </Field>

        <fieldset className="flex flex-col gap-2 border-t pt-4">
          <legend className="text-base font-semibold">{intl.formatMessage(fam.stale)}</legend>
          <label className="flex min-h-11 cursor-pointer items-center gap-3 text-sm">
            <Checkbox
              className="size-5"
              checked={actions.staleWorkingDays > 0}
              onChange={(e) => {
                if (e.target.checked) set({ staleWorkingDays: lastStale })
                else {
                  setLastStale(actions.staleWorkingDays || lastStale)
                  set({ staleWorkingDays: 0 })
                }
              }}
            />
            {intl.formatMessage(fam.staleUse)}
          </label>
          {actions.staleWorkingDays > 0 ? (
            <Field
              label={intl.formatMessage(fam.stale)}
              hint={intl.formatMessage(fam.staleHint)}
              error={err('staleWorkingDays')}
            >
              {(p) => (
                <NumberInput
                  {...p}
                  min={5}
                  max={120}
                  className="min-h-11"
                  value={actions.staleWorkingDays}
                  onChange={(v) => {
                    set({ staleWorkingDays: v ?? -1 })
                  }}
                />
              )}
            </Field>
          ) : err('staleWorkingDays') ? (
            <p className="text-xs font-medium text-destructive">{err('staleWorkingDays')}</p>
          ) : null}
        </fieldset>

        <Choices
          name="actions-due-change"
          legend={intl.formatMessage(fam.dueChange)}
          hint={intl.formatMessage(fam.dueChangeHint)}
          choices={DUE_CHANGES.map((d) => ({ value: d, label: intl.formatMessage(fam[`due${d}`]) }))}
          value={actions.dueChange}
          onChange={(dueChange) => {
            set({ dueChange })
          }}
          error={err('dueChange')}
        />
        <Choices
          name="actions-verified-by"
          legend={intl.formatMessage(fam.verifiedBy)}
          hint={intl.formatMessage(fam.verifiedHint)}
          choices={VERIFIERS.map((v) => ({ value: v, label: intl.formatMessage(fam[`verified${v}`]) }))}
          value={actions.verifiedBy}
          onChange={(verifiedBy) => {
            set({ verifiedBy })
          }}
          error={err('verifiedBy')}
        />
        <label className="flex min-h-11 cursor-pointer items-center gap-3 border-t pt-4 text-sm">
          <Checkbox
            className="size-5"
            checked={actions.evidenceRequired}
            onChange={(e) => {
              set({ evidenceRequired: e.target.checked })
            }}
          />
          {intl.formatMessage(fam.evidence)}
        </label>

        <fieldset className="flex flex-col gap-2 border-t pt-4">
          <legend className="text-base font-semibold">{intl.formatMessage(fam.defaultDue)}</legend>
          {DEFAULTS.map((d) => (
            <Radio
              key={d}
              name="actions-default-due"
              checked={actions.defaultDue === d}
              label={intl.formatMessage(fam[`default${d}`])}
              onChange={() => {
                set({ defaultDue: d })
              }}
            />
          ))}
          <Field
            label={intl.formatMessage(fam.defaultDays)}
            hint={intl.formatMessage(fam.defaultDaysHint)}
            error={err('defaultDueWorkingDays')}
          >
            {(p) => (
              <NumberInput
                {...p}
                min={1}
                max={120}
                className="min-h-11"
                value={actions.defaultDueWorkingDays}
                onChange={(v) => {
                  set({ defaultDueWorkingDays: v ?? 0 })
                }}
              />
            )}
          </Field>
        </fieldset>

        <fieldset className="flex flex-col gap-2 border-t pt-4">
          <legend className="text-base font-semibold">{intl.formatMessage(fam.updates)}</legend>
          <label className="flex min-h-11 cursor-pointer items-center gap-3 text-sm">
            <Checkbox
              className="size-5"
              checked={actions.updateRequestWorkingDays > 0}
              onChange={(e) => {
                if (e.target.checked) set({ updateRequestWorkingDays: lastUpdates })
                else {
                  setLastUpdates(actions.updateRequestWorkingDays || lastUpdates)
                  set({ updateRequestWorkingDays: 0 })
                }
              }}
            />
            {intl.formatMessage(fam.updatesUse)}
          </label>
          {actions.updateRequestWorkingDays > 0 ? (
            <Field
              label={intl.formatMessage(fam.updatesDays)}
              hint={intl.formatMessage(fam.updatesHint)}
              error={err('updateRequestWorkingDays')}
            >
              {(p) => (
                <NumberInput
                  {...p}
                  min={1}
                  max={20}
                  className="min-h-11"
                  value={actions.updateRequestWorkingDays}
                  onChange={(v) => {
                    set({ updateRequestWorkingDays: v ?? -1 })
                  }}
                />
              )}
            </Field>
          ) : err('updateRequestWorkingDays') ? (
            <p className="text-xs font-medium text-destructive">{err('updateRequestWorkingDays')}</p>
          ) : null}
        </fieldset>

        <div className="flex flex-wrap items-end gap-3 border-t pt-4">
          <Field
            label={intl.formatMessage(fam.definition)}
            hint={intl.formatMessage(fam.definitionHint)}
            error={err('definitionKey')}
          >
            {(p) => (
              <Select
                {...p}
                className="min-h-11 w-72"
                value={actions.definitionKey}
                onChange={(e) => {
                  set({ definitionKey: e.target.value })
                }}
              >
                {known ? null : (
                  <option value={actions.definitionKey}>
                    {intl.formatMessage(fam.definitionMissing, { key: actions.definitionKey })}
                  </option>
                )}
                {(definitions.data ?? []).map((d) => (
                  <option key={d.key} value={d.key}>
                    {d.name}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </div>
      </fieldset>
      <p>
        <Link
          to="/admin/workflows/$key"
          params={{ key: actions.definitionKey }}
          className="inline-flex min-h-11 items-center text-primary underline underline-offset-4"
        >
          {intl.formatMessage(fam.openBuilder)}
        </Link>
      </p>
    </div>
  )
}

function Radio({
  name,
  checked,
  label,
  onChange,
}: {
  name: string
  checked: boolean
  label: string
  onChange: () => void
}) {
  return (
    <label className="flex min-h-11 cursor-pointer items-center gap-3 text-sm">
      <input
        type="radio"
        name={name}
        className="size-5 shrink-0 accent-primary"
        checked={checked}
        onChange={onChange}
      />
      {label}
    </label>
  )
}

function Choices<T extends string>({
  name,
  legend,
  hint,
  choices,
  value,
  onChange,
  error,
}: {
  name: string
  legend: string
  hint: string
  choices: { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
  error?: string | undefined
}) {
  return (
    <fieldset className="flex flex-col gap-1 border-t pt-4">
      <legend className="text-base font-semibold">{legend}</legend>
      {choices.map((c) => (
        <Radio
          key={c.value}
          name={name}
          checked={value === c.value}
          label={c.label}
          onChange={() => {
            onChange(c.value)
          }}
        />
      ))}
      <p className="text-xs text-muted-foreground">{hint}</p>
      {error ? <p className="text-xs font-medium text-destructive">{error}</p> : null}
    </fieldset>
  )
}
