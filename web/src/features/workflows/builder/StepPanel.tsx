import { Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import type { FieldProblem } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { AlertDialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { bm, bmv, stepTypeLabels } from './builder-messages'
import { END, type DefinitionBody, type StepJson, type StepType } from './definition'
import { stepName } from './describe'
import { newStep, PALETTE, type Command, type Slot } from './edit'
import { problemsByStep } from './problems'

const pm = defineMessages({
  removeDescription: {
    id: 'workflows.builder.remove.description',
    defaultMessage:
      'What led to this step will lead where it led, when that is one place. You can undo this until you save.',
  },
})

/** A select of where a connection leads: any other step, or End; an unknown target is kept as an option. */
function TargetSelect({
  body,
  self,
  value,
  label,
  onChange,
}: {
  body: DefinitionBody
  self?: string
  value: string | undefined
  label: string
  onChange: (target: string) => void
}) {
  const intl = useIntl()
  const known = value === undefined || value === END || body.steps.some((s) => s.id === value)
  return (
    <Field label={label}>
      {(p) => (
        <Select
          {...p}
          value={value ?? ''}
          onChange={(e) => {
            onChange(e.target.value)
          }}
        >
          {value === undefined ? <option value="">{intl.formatMessage(bm.nowhere)}</option> : null}
          {known ? null : <option value={value}>{intl.formatMessage(bmv.unknown, { id: value })}</option>}
          {body.steps
            .filter((s) => s.id !== self)
            .map((s) => (
              <option key={s.id} value={s.id}>
                {stepName(s)}
              </option>
            ))}
          <option value={END}>{intl.formatMessage(bm.end)}</option>
        </Select>
      )}
    </Field>
  )
}

/** Adds a step of a chosen type after [after] (a step, or the start). */
export function AddStep({
  body,
  after,
  label,
  onCommand,
}: {
  body: DefinitionBody
  after: string
  label: string
  onCommand: (command: Command) => void
}) {
  const intl = useIntl()
  const [type, setType] = useState<StepType>('approve')
  return (
    <fieldset className="flex flex-wrap items-end gap-2">
      <legend className="sr-only">{label}</legend>
      <Field label={label}>
        {(p) => (
          <Select
            {...p}
            value={type}
            onChange={(e) => {
              setType(e.target.value as StepType)
            }}
          >
            {PALETTE.map((t) => (
              <option key={t} value={t}>
                {intl.formatMessage(stepTypeLabels[t])}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Button
        variant="outline"
        onClick={() => {
          onCommand({ kind: 'add', after, step: newStep(body, type, intl.formatMessage(stepTypeLabels[type])) })
        }}
      >
        <Plus aria-hidden="true" />
        {intl.formatMessage(bm.add)}
      </Button>
    </fieldset>
  )
}

/**
 * What can be changed on a step in T2 (design E06 §3): its name, where it leads (a branch's conditions and otherwise),
 * a step after it, and removing it. The same panel opens from the Steps view and from the diagram.
 */
export function StepPanel({
  body,
  step,
  problems,
  onCommand,
}: {
  body: DefinitionBody
  step: StepJson
  problems: FieldProblem[]
  onCommand: (command: Command) => void
}) {
  const intl = useIntl()
  const [confirming, setConfirming] = useState(false)
  const index = body.steps.findIndex((s) => s.id === step.id)
  const own = problemsByStep(problems).get(index) ?? []
  const connect = (from: Slot) => (to: string) => {
    onCommand({ kind: 'connect', from, to })
  }
  return (
    <div className="flex flex-col gap-3">
      <Field label={intl.formatMessage(bm.stepName)}>
        {(p) => (
          <Input
            {...p}
            dir="auto"
            maxLength={200}
            value={step.name ?? ''}
            onChange={(e) => {
              onCommand({ kind: 'set', id: step.id, patch: { name: e.target.value } })
            }}
          />
        )}
      </Field>
      {step.type === 'branch' ? (
        <BranchEditor body={body} step={step} onCommand={onCommand} />
      ) : (
        <TargetSelect
          body={body}
          self={step.id}
          value={step.next}
          label={intl.formatMessage(bm.leadsTo)}
          onChange={connect({ kind: 'next', step: step.id })}
        />
      )}
      {own.length > 0 ? (
        <section aria-label={intl.formatMessage(bmv.stepProblems, { name: stepName(step) })}>
          <ul className="flex flex-col gap-1 text-sm text-destructive">
            {own.map((problem) => (
              <li key={`${problem.field}:${problem.code}`}>{problem.message}</li>
            ))}
          </ul>
        </section>
      ) : null}
      <div className="flex flex-wrap items-end justify-between gap-3 border-t pt-3">
        <AddStep body={body} after={step.id} label={intl.formatMessage(bm.addAfter)} onCommand={onCommand} />
        <Button
          variant="outline"
          onClick={() => {
            setConfirming(true)
          }}
        >
          <Trash2 aria-hidden="true" />
          {intl.formatMessage(bm.remove)}
        </Button>
      </div>
      <AlertDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={intl.formatMessage(bmv.removeTitle, { name: stepName(step) })}
        description={intl.formatMessage(pm.removeDescription)}
        confirmLabel={intl.formatMessage(bm.removeConfirm)}
        cancelLabel={intl.formatMessage(bm.cancel)}
        destructive
        onConfirm={() => {
          onCommand({ kind: 'remove', id: step.id })
        }}
      />
    </div>
  )
}

/** A branch's conditions in order, each with where it leads, then `otherwise` (raw CEL here; T4 adds the simple form). */
function BranchEditor({
  body,
  step,
  onCommand,
}: {
  body: DefinitionBody
  step: StepJson
  onCommand: (command: Command) => void
}) {
  const intl = useIntl()
  const branches = step.branches ?? []
  return (
    <div className="flex flex-col gap-3">
      {branches.map((b, i) => (
        <fieldset key={String(i)} className="grid gap-2 rounded-md border p-2 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
          <legend className="px-1 text-sm font-medium">{intl.formatMessage(bmv.conditionN, { n: i + 1 })}</legend>
          <Field label={intl.formatMessage(bm.condition)}>
            {(p) => (
              <Input
                {...p}
                dir="ltr"
                className="font-mono text-xs"
                value={b.when}
                onChange={(e) => {
                  const next = branches.map((x, j) => (j === i ? { ...x, when: e.target.value } : x))
                  onCommand({ kind: 'set', id: step.id, patch: { branches: next } })
                }}
              />
            )}
          </Field>
          <TargetSelect
            body={body}
            self={step.id}
            value={b.next}
            label={intl.formatMessage(bmv.leadsToN, { n: i + 1 })}
            onChange={(to) => {
              onCommand({ kind: 'connect', from: { kind: 'branch', step: step.id, index: i }, to })
            }}
          />
          <Button
            variant="ghost"
            size="icon"
            aria-label={intl.formatMessage(bmv.removeCondition, { n: i + 1 })}
            disabled={branches.length === 1}
            onClick={() => {
              onCommand({ kind: 'removeBranch', id: step.id, index: i })
            }}
          >
            <Trash2 aria-hidden="true" />
          </Button>
        </fieldset>
      ))}
      <div>
        <Button
          variant="outline"
          onClick={() => {
            onCommand({ kind: 'addBranch', id: step.id, branch: { when: '', next: END } })
          }}
        >
          <Plus aria-hidden="true" />
          {intl.formatMessage(bm.addCondition)}
        </Button>
      </div>
      <TargetSelect
        body={body}
        self={step.id}
        value={step.otherwise}
        label={intl.formatMessage(bm.otherwise)}
        onChange={(to) => {
          onCommand({ kind: 'connect', from: { kind: 'otherwise', step: step.id }, to })
        }}
      />
    </div>
  )
}
