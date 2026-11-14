import { useState } from 'react'
import { useIntl } from 'react-intl'
import type { DefinitionChoice } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select, Textarea } from '@/components/ui/form-controls'
import { AllowedApproversEditor } from '@/features/memo/AllowedApproversEditor'
import { PeoplePicker } from '@/features/org/PeoplePicker'
import { tm, tmv } from './messages'
import type { TypeSettings } from './settings'

const policyLabels = { INITIATOR_DEFINED: tm.policyInitiator, BOTH: tm.policyBoth, FIXED: tm.policyFixed }

/**
 * The Settings tab (design E01 §10 flow A3): name, code, classification, workflow and who chooses approvers, step-up, who
 * may raise the type, retention, auto-close and attachments. Read-only unless the version is a draft ([onChange] set).
 */
export function SettingsTab({
  settings,
  onChange,
  levels,
  definitions,
  names,
  errors,
}: {
  settings: TypeSettings
  onChange?: (next: Partial<TypeSettings>) => void
  levels: { id: string; name: string }[]
  definitions: DefinitionChoice[]
  names: Record<string, string>
  errors: Record<string, string>
}) {
  const intl = useIntl()
  const readOnly = !onChange
  const set = (next: Partial<TypeSettings>) => onChange?.(next)
  const routed = definitions.find((d) => d.key === settings.definitionKey)?.routed ?? true
  const policies: TypeSettings['routePolicy'][] = routed ? ['INITIATOR_DEFINED', 'BOTH'] : ['FIXED']
  const [allowedNames, setAllowedNames] = useState(names)
  const [unitNames, setUnitNames] = useState<Record<string, string>>(names)
  const scopeUnits = settings.raiseScope.units ?? []
  const retention = settings.retention ?? null
  const rules = settings.attachmentRules

  return (
    <fieldset disabled={readOnly} className="flex max-w-2xl flex-col gap-4">
      <div className="grid gap-4 sm:grid-cols-[1fr_auto]">
        <Field label={intl.formatMessage(tm.name)} required error={errors.name}>
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
        <Field label={intl.formatMessage(tm.code)} hint={intl.formatMessage(tm.codeHint)} required error={errors.code}>
          {(p) => (
            <Input
              {...p}
              maxLength={10}
              className="w-40 uppercase"
              value={settings.code}
              onChange={(e) => {
                set({ code: e.target.value.toUpperCase() })
              }}
            />
          )}
        </Field>
      </div>
      <Field label={intl.formatMessage(tm.classification)} error={errors.defaultClassificationId}>
        {(p) => (
          <Select
            {...p}
            value={settings.defaultClassificationId ?? ''}
            onChange={(e) => {
              set({ defaultClassificationId: e.target.value || null })
            }}
          >
            <option value="">{intl.formatMessage(tm.lowest)}</option>
            {levels.map((l) => (
              <option key={l.id} value={l.id}>
                {l.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Field
        label={intl.formatMessage(tm.workflow)}
        hint={intl.formatMessage(tm.workflowHint)}
        error={errors.definitionKey}
      >
        {(p) => (
          <Select
            {...p}
            value={settings.definitionKey}
            onChange={(e) => {
              const next = e.target.value
              // The policy has to fit the definition: FIXED exactly when it has no route step.
              const nextRouted = definitions.find((d) => d.key === next)?.routed ?? true
              const policy = settings.routePolicy
              set({
                definitionKey: next,
                routePolicy: nextRouted ? (policy === 'FIXED' ? 'INITIATOR_DEFINED' : policy) : 'FIXED',
              })
            }}
          >
            {definitions.some((d) => d.key === settings.definitionKey) ? null : (
              <option value={settings.definitionKey}>{settings.definitionKey}</option>
            )}
            {definitions.map((d) => (
              <option key={d.key} value={d.key}>
                {d.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Field label={intl.formatMessage(tm.policy)} error={errors.routePolicy}>
        {(p) => (
          <Select
            {...p}
            value={settings.routePolicy}
            onChange={(e) => {
              set({ routePolicy: e.target.value as TypeSettings['routePolicy'] })
            }}
          >
            {policies.map((option) => (
              <option key={option} value={option}>
                {intl.formatMessage(policyLabels[option])}
              </option>
            ))}
          </Select>
        )}
      </Field>
      {settings.routePolicy !== 'FIXED' ? (
        <>
          <AllowedApproversEditor
            value={settings.allowedApprovers}
            names={allowedNames}
            onChange={(next, picked) => {
              setAllowedNames(picked)
              set({ allowedApprovers: next })
            }}
          />
          <label className="flex min-h-10 items-center gap-2 text-sm">
            <Checkbox
              checked={settings.doaCheck}
              onChange={(e) => {
                set({ doaCheck: e.target.checked })
              }}
            />
            {intl.formatMessage(tm.doaCheck)}
          </label>
        </>
      ) : null}
      <label className="flex min-h-10 items-center gap-2 text-sm">
        <Checkbox
          checked={settings.stepUpOnApprove}
          onChange={(e) => {
            set({ stepUpOnApprove: e.target.checked })
          }}
        />
        {intl.formatMessage(tm.stepUp)}
      </label>

      <fieldset className="flex flex-col gap-2">
        <legend className="text-sm font-medium">{intl.formatMessage(tm.scope)}</legend>
        <p className="text-sm text-muted-foreground">
          {intl.formatMessage(scopeUnits.length ? tm.scopeSome : tm.scopeEveryone)}
        </p>
        <ul className="flex flex-col gap-2">
          {scopeUnits.map((unit, i) => {
            const label = unitNames[unit.id] ?? unit.id
            return (
              <li key={unit.id} className="flex flex-wrap items-center gap-3 text-sm">
                <span className="font-medium">{label}</span>
                <label className="flex items-center gap-2">
                  <Checkbox
                    checked={unit.subtree}
                    onChange={(e) => {
                      set({
                        raiseScope: {
                          ...settings.raiseScope,
                          units: scopeUnits.map((u, j) => (i === j ? { ...u, subtree: e.target.checked } : u)),
                        },
                      })
                    }}
                  />
                  {intl.formatMessage(tm.scopeSubtree)}
                </label>
                {readOnly ? null : (
                  <Button
                    variant="outline"
                    aria-label={intl.formatMessage(tmv.remove, { name: label })}
                    onClick={() => {
                      set({ raiseScope: { ...settings.raiseScope, units: scopeUnits.filter((_, j) => j !== i) } })
                    }}
                  >
                    {intl.formatMessage(tm.remove)}
                  </Button>
                )}
              </li>
            )
          })}
        </ul>
        {readOnly ? null : (
          <Field label={intl.formatMessage(tm.scopeUnit)} error={errors.raiseScope}>
            {(p) => (
              <PeoplePicker
                id={p.id}
                kind="unit"
                describedBy={p['aria-describedby']}
                onChange={(picked) => {
                  if (!picked || scopeUnits.some((u) => u.id === picked.id)) return
                  setUnitNames({ ...unitNames, [picked.id]: picked.label })
                  set({
                    raiseScope: { ...settings.raiseScope, units: [...scopeUnits, { id: picked.id, subtree: true }] },
                  })
                }}
              />
            )}
          </Field>
        )}
      </fieldset>

      <fieldset className="flex flex-col gap-2">
        <legend className="text-sm font-medium">{intl.formatMessage(tm.retention)}</legend>
        <p className="text-xs text-muted-foreground">{intl.formatMessage(tm.retentionHint)}</p>
        <div className="flex flex-wrap items-end gap-3">
          <Field label={intl.formatMessage(tm.years)} error={errors['retention/years']}>
            {(p) => (
              <Input
                {...p}
                inputMode="numeric"
                className="w-24"
                placeholder={intl.formatMessage(tm.retentionNone)}
                value={retention ? String(retention.years) : ''}
                onChange={(e) => {
                  const years = Number.parseInt(e.target.value, 10)
                  set({ retention: Number.isFinite(years) ? { years, from: retention?.from ?? 'CLOSURE' } : null })
                }}
              />
            )}
          </Field>
          <Field label={intl.formatMessage(tm.retentionFrom)}>
            {(p) => (
              <Select
                {...p}
                disabled={readOnly || !retention}
                value={retention?.from ?? 'CLOSURE'}
                onChange={(e) => {
                  if (retention) set({ retention: { ...retention, from: e.target.value as 'APPROVAL' | 'CLOSURE' } })
                }}
              >
                <option value="APPROVAL">{intl.formatMessage(tm.fromApproval)}</option>
                <option value="CLOSURE">{intl.formatMessage(tm.fromClosure)}</option>
              </Select>
            )}
          </Field>
        </div>
      </fieldset>

      <Field
        label={intl.formatMessage(tm.autoClose)}
        hint={intl.formatMessage(tm.autoCloseHint)}
        error={errors.autoCloseDays}
      >
        {(p) => (
          <Input
            {...p}
            inputMode="numeric"
            className="w-24"
            value={settings.autoCloseDays == null ? '' : String(settings.autoCloseDays)}
            onChange={(e) => {
              const days = Number.parseInt(e.target.value, 10)
              set({ autoCloseDays: Number.isFinite(days) ? days : null })
            }}
          />
        )}
      </Field>

      <fieldset className="flex flex-col gap-2">
        <label className="flex min-h-10 items-center gap-2 text-sm">
          <Checkbox
            checked={rules.required}
            onChange={(e) => {
              set({
                attachmentRules: {
                  ...rules,
                  required: e.target.checked,
                  min: e.target.checked ? Math.max(rules.min, 1) : 0,
                },
              })
            }}
          />
          {intl.formatMessage(tm.attachments)}
        </label>
        {rules.required ? (
          <div className="flex flex-wrap items-end gap-3 ps-6">
            <Field label={intl.formatMessage(tm.attachmentsMin)} error={errors['attachmentRules/min']}>
              {(p) => (
                <Input
                  {...p}
                  inputMode="numeric"
                  className="w-20"
                  value={String(rules.min)}
                  onChange={(e) => {
                    set({ attachmentRules: { ...rules, min: Number.parseInt(e.target.value, 10) || 0 } })
                  }}
                />
              )}
            </Field>
            <Field label={intl.formatMessage(tm.attachmentsNote)} className="min-w-64 flex-1">
              {(p) => (
                <Textarea
                  {...p}
                  rows={2}
                  maxLength={300}
                  value={rules.note ?? ''}
                  onChange={(e) => {
                    set({ attachmentRules: { ...rules, note: e.target.value || null } })
                  }}
                />
              )}
            </Field>
          </div>
        ) : null}
      </fieldset>
    </fieldset>
  )
}
