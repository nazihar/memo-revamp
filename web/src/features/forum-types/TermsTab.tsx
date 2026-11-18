import { Plus, Trash2 } from 'lucide-react'
import { useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Select } from '@/components/ui/form-controls'
import { ft, ftv } from './messages'
import { NumberInput } from './NumberInput'
import { LATE_MODES, MAX_REMINDERS, type Papers, type TypeSettings } from './settings'

/**
 * The Terms tab (flow J6): the usual term of office, the most consecutive terms (a warning, never a block) and when the
 * secretary is reminded before a term ends; then papers for meetings (Phase 4 E02 T4, flow M, ADR-0059): when papers are
 * due, what happens to a late one, and when the secretaries are reminded of a paper waiting for triage. Read-only unless
 * the version is a draft ([onChange] set).
 */
export function TermsTab({
  settings,
  onChange,
  errors,
}: {
  settings: TypeSettings
  onChange?: (next: Partial<TypeSettings>) => void
  errors: Record<string, string>
}) {
  const intl = useIntl()
  const set = (next: Partial<TypeSettings>) => onChange?.(next)
  const days = settings.reminderDays
  const papers = settings.submissionPolicy
  const setPapers = (next: Partial<Papers>) => {
    set({ submissionPolicy: { ...papers, ...next } })
  }
  return (
    <fieldset disabled={!onChange} className="flex max-w-2xl flex-col gap-4">
      <legend className="sr-only">{intl.formatMessage(ft.tabTerms)}</legend>
      <Field
        label={intl.formatMessage(ft.termMonths)}
        hint={intl.formatMessage(ft.termMonthsHint)}
        error={errors['termPolicy.termMonths']}
      >
        {(p) => (
          <NumberInput
            {...p}
            min={1}
            max={120}
            value={settings.termMonths}
            onChange={(termMonths) => {
              set({ termMonths })
            }}
          />
        )}
      </Field>
      <Field
        label={intl.formatMessage(ft.maxTerms)}
        hint={intl.formatMessage(ft.maxTermsHint)}
        error={errors['termPolicy.maxTerms']}
      >
        {(p) => (
          <NumberInput
            {...p}
            min={1}
            max={20}
            value={settings.maxTerms}
            onChange={(maxTerms) => {
              set({ maxTerms })
            }}
          />
        )}
      </Field>
      <fieldset className="flex flex-col gap-2" aria-describedby="reminders-hint">
        <legend className="text-sm font-medium">{intl.formatMessage(ft.reminders)}</legend>
        <p id="reminders-hint" className="text-xs text-muted-foreground">
          {intl.formatMessage(ft.remindersHint)}
        </p>
        {days.length === 0 ? <p className="text-sm">{intl.formatMessage(ft.noReminders)}</p> : null}
        <ul className="flex flex-col gap-2">
          {days.map((d, i) => {
            const n = i + 1
            return (
              <li key={String(i)} className="flex flex-wrap items-center gap-2 text-sm">
                <NumberInput
                  aria-label={intl.formatMessage(ftv.reminder, { n })}
                  min={1}
                  max={365}
                  value={d}
                  onChange={(value) => {
                    set({ reminderDays: days.map((x, j) => (j === i ? value : x)) })
                  }}
                />
                <span>{intl.formatMessage(ft.daysBefore)}</span>
                {onChange ? (
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label={intl.formatMessage(ftv.reminderRemove, { n })}
                    onClick={() => {
                      set({ reminderDays: days.filter((_, j) => j !== i) })
                    }}
                  >
                    <Trash2 aria-hidden="true" />
                  </Button>
                ) : null}
              </li>
            )
          })}
        </ul>
        {errors['termPolicy.reminderDays'] ? (
          <p className="text-xs font-medium text-destructive">{errors['termPolicy.reminderDays']}</p>
        ) : null}
        {onChange && days.length < MAX_REMINDERS ? (
          <div>
            <Button
              variant="outline"
              onClick={() => {
                set({ reminderDays: [...days, null] })
              }}
            >
              <Plus aria-hidden="true" />
              {intl.formatMessage(ft.addReminder)}
            </Button>
          </div>
        ) : null}
      </fieldset>
      <fieldset className="flex flex-col gap-4 border-t pt-4" aria-describedby="papers-lead">
        <legend className="text-base font-semibold">{intl.formatMessage(ft.papers)}</legend>
        <p id="papers-lead" className="text-xs text-muted-foreground">
          {intl.formatMessage(ft.papersLead)}
        </p>
        <Field
          label={intl.formatMessage(ft.deadline)}
          hint={intl.formatMessage(ft.deadlineHint)}
          error={errors['submissionPolicy.deadlineWorkingDays']}
        >
          {(p) => (
            <NumberInput
              {...p}
              min={0}
              max={30}
              value={papers.deadlineWorkingDays}
              onChange={(deadlineWorkingDays) => {
                setPapers({ deadlineWorkingDays })
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(ft.lateMode)} hint={intl.formatMessage(ft.lateHint)}>
          {(p) => (
            <Select
              {...p}
              className="w-auto max-w-full"
              value={papers.lateMode}
              onChange={(e) => {
                setPapers({ lateMode: LATE_MODES.find((m) => m === e.target.value) ?? 'CHAIR_APPROVAL' })
              }}
            >
              {LATE_MODES.map((m) => (
                <option key={m} value={m}>
                  {intl.formatMessage(ft[m])}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field
          label={intl.formatMessage(ft.triage)}
          hint={intl.formatMessage(ft.triageHint)}
          error={errors['submissionPolicy.triageWorkingDays']}
        >
          {(p) => (
            <NumberInput
              {...p}
              min={1}
              max={30}
              value={papers.triageWorkingDays}
              onChange={(triageWorkingDays) => {
                setPapers({ triageWorkingDays })
              }}
            />
          )}
        </Field>
      </fieldset>
    </fieldset>
  )
}
