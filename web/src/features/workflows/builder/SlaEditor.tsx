import { Plus, Trash2 } from 'lucide-react'
import { useId } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select } from '@/components/ui/form-controls'
import { AssigneeEditor } from './AssigneeEditor'
import type { SlaJson, StepJson } from './definition'
import type { Labels } from './describe'
import type { Command } from './edit'

const sm = defineMessages({
  has: { id: 'workflows.builder.sla.has', defaultMessage: 'This step has a deadline' },
  due: { id: 'workflows.builder.sla.field.due', defaultMessage: 'Due after' },
  workingTime: {
    id: 'workflows.builder.sla.field.workingTime',
    defaultMessage: 'Count working time only (the business calendar)',
  },
  remindBefore: {
    id: 'workflows.builder.sla.field.remindBefore',
    defaultMessage: 'Remind the holder before it is due',
  },
  before: { id: 'workflows.builder.sla.field.before', defaultMessage: 'How long before' },
  remindersAfter: { id: 'workflows.builder.sla.field.after', defaultMessage: 'Reminders after it is due' },
  addReminder: { id: 'workflows.builder.sla.addReminder', defaultMessage: 'Add a reminder' },
  escalation: { id: 'workflows.builder.sla.field.escalation', defaultMessage: 'When it is late' },
  escalateNone: { id: 'workflows.builder.sla.escalate.none', defaultMessage: 'Nothing more' },
  escalateNotify: { id: 'workflows.builder.sla.escalate.notifyOption', defaultMessage: 'Tell the holder’s supervisor' },
  escalateReassign: { id: 'workflows.builder.sla.escalate.reassignOption', defaultMessage: 'Move the task to someone' },
  escalateNextLevel: {
    id: 'workflows.builder.sla.escalate.nextLevelOption',
    defaultMessage: 'Move the task one level up',
  },
  escalateAfter: { id: 'workflows.builder.sla.field.escalateAfter', defaultMessage: 'How long late' },
  escalateTo: { id: 'workflows.builder.sla.field.escalateTo', defaultMessage: 'Move it to' },
  amount: { id: 'workflows.builder.duration.amount', defaultMessage: 'Amount' },
  unit: { id: 'workflows.builder.duration.unit', defaultMessage: 'Unit' },
  unitDays: { id: 'workflows.builder.duration.unit.days', defaultMessage: 'days' },
  unitHours: { id: 'workflows.builder.duration.unit.hours', defaultMessage: 'hours' },
  unitMinutes: { id: 'workflows.builder.duration.unit.minutes', defaultMessage: 'minutes' },
  iso: { id: 'workflows.builder.duration.iso', defaultMessage: 'Duration (ISO 8601, such as P1DT4H)' },
})

const smv = defineMessages<{ reminderN: { n: number }; removeReminder: { n: number } }>({
  reminderN: { id: 'workflows.builder.sla.reminderN', defaultMessage: 'Reminder {n} after it is due' },
  removeReminder: { id: 'workflows.builder.sla.removeReminder', defaultMessage: 'Remove reminder {n}' },
})

type Unit = 'D' | 'H' | 'M'

/** `P2D`, `PT4H` or `PT30M` as an amount and a unit; anything else (`P1DT4H`) is shown as written. */
function split(iso: string): { amount: number; unit: Unit } | undefined {
  const days = /^P(\d+)D$/.exec(iso)
  if (days) return { amount: Number(days[1]), unit: 'D' }
  const time = /^PT(\d+)([HM])$/.exec(iso)
  return time ? { amount: Number(time[1]), unit: time[2] as Unit } : undefined
}

function join(amount: number, unit: Unit): string {
  return unit === 'D' ? `P${String(amount)}D` : `PT${String(amount)}${unit}`
}

/** A duration as an amount and a unit (days, hours, minutes), or the ISO text when it combines units. */
function DurationInput({ label, value, onChange }: { label: string; value: string; onChange: (iso: string) => void }) {
  const intl = useIntl()
  const id = useId()
  const parts = split(value)
  if (!parts) {
    return (
      <Field label={`${label} · ${intl.formatMessage(sm.iso)}`}>
        {(p) => (
          <Input
            {...p}
            dir="ltr"
            value={value}
            onChange={(e) => {
              onChange(e.target.value)
            }}
          />
        )}
      </Field>
    )
  }
  return (
    <fieldset className="flex flex-wrap items-end gap-2">
      <legend className="mb-1 text-sm font-medium">{label}</legend>
      <label htmlFor={`${id}-amount`} className="sr-only">
        {intl.formatMessage(sm.amount)}
      </label>
      <Input
        id={`${id}-amount`}
        type="number"
        min={1}
        className="w-24"
        value={parts.amount}
        onChange={(e) => {
          const n = Number.parseInt(e.target.value, 10)
          if (!Number.isNaN(n) && n > 0) onChange(join(n, parts.unit))
        }}
      />
      <label htmlFor={`${id}-unit`} className="sr-only">
        {intl.formatMessage(sm.unit)}
      </label>
      <Select
        id={`${id}-unit`}
        className="w-32"
        value={parts.unit}
        onChange={(e) => {
          onChange(join(parts.amount, e.target.value as Unit))
        }}
      >
        <option value="D">{intl.formatMessage(sm.unitDays)}</option>
        <option value="H">{intl.formatMessage(sm.unitHours)}</option>
        <option value="M">{intl.formatMessage(sm.unitMinutes)}</option>
      </Select>
    </fieldset>
  )
}

/**
 * A step's deadline (design E05 §2, E06 §3): due after a duration, in working time unless unticked; a reminder before,
 * up to three after; and what happens when it is late. The server checks the rules (a reminder before shorter than the
 * due time, a target when reassigning).
 */
export function SlaEditor({
  step,
  labels,
  onLabel,
  onCommand,
}: {
  step: StepJson
  labels: Labels
  onLabel: (id: string, label: string) => void
  onCommand: (command: Command) => void
}) {
  const intl = useIntl()
  const sla = step.sla
  const set = (next: SlaJson | undefined) => {
    onCommand({ kind: 'set', id: step.id, patch: { sla: next } })
  }
  const after = sla?.remind?.after ?? []
  const remind = (before: string | undefined, afterList: string[]) => {
    if (!sla) return
    const r = { ...(before ? { before } : {}), ...(afterList.length > 0 ? { after: afterList } : {}) }
    set({ ...sla, remind: Object.keys(r).length > 0 ? r : undefined })
  }
  return (
    <fieldset className="flex flex-col gap-2 rounded-md border p-2">
      <legend className="px-1 text-sm font-medium">{intl.formatMessage(sm.has)}</legend>
      <label className="flex min-h-6 items-center gap-2 text-sm">
        <Checkbox
          checked={sla !== undefined}
          onChange={(e) => {
            set(e.target.checked ? { due: 'P1D' } : undefined)
          }}
        />
        {intl.formatMessage(sm.has)}
      </label>
      {sla ? (
        <>
          <DurationInput
            label={intl.formatMessage(sm.due)}
            value={sla.due}
            onChange={(due) => {
              set({ ...sla, due })
            }}
          />
          <label className="flex min-h-6 items-center gap-2 text-sm">
            <Checkbox
              checked={sla.workingTime !== false}
              onChange={(e) => {
                set({ ...sla, workingTime: e.target.checked ? undefined : false })
              }}
            />
            {intl.formatMessage(sm.workingTime)}
          </label>
          <label className="flex min-h-6 items-center gap-2 text-sm">
            <Checkbox
              checked={sla.remind?.before !== undefined}
              onChange={(e) => {
                remind(e.target.checked ? 'PT4H' : undefined, after)
              }}
            />
            {intl.formatMessage(sm.remindBefore)}
          </label>
          {sla.remind?.before ? (
            <DurationInput
              label={intl.formatMessage(sm.before)}
              value={sla.remind.before}
              onChange={(before) => {
                remind(before, after)
              }}
            />
          ) : null}
          <p className="text-sm font-medium">{intl.formatMessage(sm.remindersAfter)}</p>
          {after.map((d, i) => (
            <div key={String(i)} className="flex items-end gap-2">
              <DurationInput
                label={intl.formatMessage(smv.reminderN, { n: i + 1 })}
                value={d}
                onChange={(iso) => {
                  remind(
                    sla.remind?.before,
                    after.map((x, j) => (j === i ? iso : x)),
                  )
                }}
              />
              <Button
                variant="ghost"
                size="icon"
                aria-label={intl.formatMessage(smv.removeReminder, { n: i + 1 })}
                onClick={() => {
                  remind(
                    sla.remind?.before,
                    after.filter((_, j) => j !== i),
                  )
                }}
              >
                <Trash2 aria-hidden="true" />
              </Button>
            </div>
          ))}
          <div>
            <Button
              variant="outline"
              disabled={after.length >= 3}
              onClick={() => {
                remind(sla.remind?.before, [...after, 'P1D'])
              }}
            >
              <Plus aria-hidden="true" />
              {intl.formatMessage(sm.addReminder)}
            </Button>
          </div>
          <Field label={intl.formatMessage(sm.escalation)}>
            {(p) => (
              <Select
                {...p}
                value={sla.escalate?.action ?? ''}
                onChange={(e) => {
                  const action = e.target.value as '' | 'notify' | 'reassign' | 'nextLevel'
                  set({
                    ...sla,
                    escalate: action ? { after: sla.escalate?.after ?? 'P1D', action } : undefined,
                  })
                }}
              >
                <option value="">{intl.formatMessage(sm.escalateNone)}</option>
                <option value="notify">{intl.formatMessage(sm.escalateNotify)}</option>
                <option value="reassign">{intl.formatMessage(sm.escalateReassign)}</option>
                <option value="nextLevel">{intl.formatMessage(sm.escalateNextLevel)}</option>
              </Select>
            )}
          </Field>
          {sla.escalate ? (
            <DurationInput
              label={intl.formatMessage(sm.escalateAfter)}
              value={sla.escalate.after}
              onChange={(iso) => {
                if (sla.escalate) set({ ...sla, escalate: { ...sla.escalate, after: iso } })
              }}
            />
          ) : null}
          {sla.escalate?.action === 'reassign' ? (
            <AssigneeEditor
              legend={intl.formatMessage(sm.escalateTo)}
              value={sla.escalate.to}
              labels={labels}
              onLabel={onLabel}
              onChange={(to) => {
                if (sla.escalate) set({ ...sla, escalate: { ...sla.escalate, to } })
              }}
            />
          ) : null}
        </>
      ) : null}
    </fieldset>
  )
}
