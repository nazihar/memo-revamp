import { useQuery } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import { useId } from 'react'
import { defineMessages, useIntl, type MessageDescriptor } from 'react-intl'
import { getBuilderCatalogueOptions, listEscalationTargetsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { FieldProblem } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select } from '@/components/ui/form-controls'
import { useTerms, type TermValues } from '@/features/terms/terms'
import { AssigneeEditor } from './AssigneeEditor'
import { HUMAN_STEPS, type AssigneeJson, type StepJson } from './definition'
import { isUnknownForum } from './problems'
import { actionLabels } from './builder-messages'
import type { Labels } from './describe'
import type { Command } from './edit'
import { SlaEditor } from './SlaEditor'

const dm = defineMessages({
  actions: { id: 'builder.actions', defaultMessage: 'What the holder can do' },
  actionsHint: {
    id: 'builder.actions.hint',
    defaultMessage: 'Tick “needs a remark” for actions the holder must explain.',
  },
  needsRemark: { id: 'builder.actions.remark', defaultMessage: 'needs a remark' },
  forward: { id: 'builder.forward', defaultMessage: 'Forwarding is allowed to' },
  forwardAnyone: { id: 'builder.forward.anyone', defaultMessage: 'Anyone' },
  forwardUnit: { id: 'builder.forward.unit', defaultMessage: 'The holder’s unit and below' },
  forwardLegalEntity: { id: 'builder.forward.legalEntity', defaultMessage: 'The holder’s legal entity' },
  forwardNoLimit: { id: 'builder.forward.none', defaultMessage: 'No limit set' },
  mode: { id: 'builder.parallel.mode', defaultMessage: 'Decided when' },
  modeAll: { id: 'builder.parallel.mode.all', defaultMessage: 'Everyone has approved' },
  modeAny: { id: 'builder.parallel.mode.any', defaultMessage: 'Anyone has approved' },
  modeN: { id: 'builder.parallel.mode.n', defaultMessage: 'A number of them have approved' },
  n: { id: 'builder.parallel.field.n', defaultMessage: 'How many' },
  addAssignee: { id: 'builder.parallel.add', defaultMessage: 'Add someone' },
  fragment: { id: 'builder.subworkflow.field', defaultMessage: 'Sub-workflow to run' },
  doaHint: {
    id: 'builder.doa.hint',
    defaultMessage: 'Goes to the approval groups of the delegation of authority in force when the memo starts.',
  },
  routeHint: {
    id: 'builder.route.hint',
    defaultMessage: 'Goes to the approvers the initiator chooses, one after the other.',
  },
  choose: { id: 'builder.choose', defaultMessage: 'Choose…' },
  addRecipient: { id: 'builder.notify.add', defaultMessage: 'Add someone to tell' },
  message: { id: 'builder.notify.message', defaultMessage: 'Message' },
  messageHint: {
    id: 'builder.notify.messageHint',
    defaultMessage: 'The key of the notice to send, such as creditDecided. The notice names the memo only.',
  },
})

/** The committee picker (flow L2) names the committee in the organisation's words (ADR-0032). */
const dmt = defineMessages<{
  forum: TermValues
  forumHint: TermValues
  unknownForum: TermValues
  wait: TermValues
  waitOn: TermValues
  waitOff: TermValues
}>({
  forum: { id: 'builder.forum.pick', defaultMessage: '{Forum}' },
  forumHint: {
    id: 'builder.forum.pick.hint',
    defaultMessage: 'Only active {forums} are listed.',
  },
  wait: { id: 'builder.forum.wait', defaultMessage: 'Wait for the {forum}’s decision' },
  waitOn: {
    id: 'builder.forum.wait.on',
    defaultMessage: 'On: the memo waits here until the {forum} decides; nobody else acts on it meanwhile.',
  },
  waitOff: {
    id: 'builder.forum.wait.off',
    defaultMessage: 'Off: the memo is sent to the {forum} for information and its route continues.',
  },
  unknownForum: {
    id: 'builder.forum.unknown',
    defaultMessage: '{forumGender, select, other {This {forum} does not exist or is dissolved.}}',
  },
})

const dmv = defineMessages<{
  assigneeN: { n: number }
  removeAssignee: { n: number }
  remarkFor: { action: string }
  recipientN: { n: number }
  forumOption: { name: string; code: string }
  forumUnknownKey: { key: string }
  removeRecipient: { n: number }
}>({
  recipientN: { id: 'builder.notify.recipientN', defaultMessage: 'Tell {n}' },
  forumOption: { id: 'builder.forum.option', defaultMessage: '{name} ({code})' },
  forumUnknownKey: { id: 'builder.forum.unknownKey', defaultMessage: '{key} (not found)' },
  removeRecipient: { id: 'builder.notify.remove', defaultMessage: 'Remove person or group {n} to tell' },
  assigneeN: { id: 'builder.parallel.assigneeN', defaultMessage: 'Person or group {n}' },
  removeAssignee: { id: 'builder.parallel.remove', defaultMessage: 'Remove person or group {n}' },
  remarkFor: { id: 'builder.actions.remarkFor', defaultMessage: '{action} needs a remark' },
})

type Action = keyof typeof actionLabels

const HAND_ONS: Action[] = ['RETURN', 'REJECT', 'SEND_BACK', 'FORWARD', 'DELEGATE', 'ASK', 'REFER']

/** The actions a step of this type offers when it names none (`StepDef.defaultActions`). */
function defaults(step: StepJson): Action[] {
  return step.type === 'review' ? ['RECOMMEND', 'RETURN'] : ['APPROVE', 'RETURN', 'REJECT']
}

/** What the holder can do (design E03 §4.4): the positive action is fixed by the type; review steps cannot reject. */
function ActionsEditor({ step, onCommand }: { step: StepJson; onCommand: (command: Command) => void }) {
  const intl = useIntl()
  const positive: Action = step.type === 'review' ? 'RECOMMEND' : 'APPROVE'
  const offered = (step.actions ?? defaults(step)) as Action[]
  const remarks = (step.remarks ?? []) as Action[]
  const choices = [positive, ...HAND_ONS.filter((a) => !(step.type === 'review' && a === 'REJECT'))]
  const setActions = (actions: Action[]) => {
    const kept = remarks.filter((r) => actions.includes(r))
    onCommand({
      kind: 'set',
      id: step.id,
      patch: {
        actions: choices.filter((a) => actions.includes(a)),
        remarks: kept.length > 0 ? kept : undefined,
        ...(actions.includes('FORWARD') ? {} : { forward: undefined }),
      },
    })
  }
  return (
    <fieldset className="flex flex-col gap-1">
      <legend className="text-sm font-medium">{intl.formatMessage(dm.actions)}</legend>
      <p className="text-xs text-muted-foreground">{intl.formatMessage(dm.actionsHint)}</p>
      <ul className="flex flex-col gap-1">
        {choices.map((action) => {
          const on = offered.includes(action)
          const name = intl.formatMessage(actionLabels[action])
          return (
            <li key={action} className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
              <label className="flex min-h-6 items-center gap-2">
                <Checkbox
                  checked={on}
                  disabled={action === positive}
                  onChange={(e) => {
                    setActions(e.target.checked ? [...offered, action] : offered.filter((a) => a !== action))
                  }}
                />
                {name}
              </label>
              {on ? (
                <label className="flex min-h-6 items-center gap-2 text-muted-foreground">
                  <Checkbox
                    aria-label={intl.formatMessage(dmv.remarkFor, { action: name })}
                    checked={remarks.includes(action)}
                    onChange={(e) => {
                      const next = e.target.checked ? [...remarks, action] : remarks.filter((r) => r !== action)
                      onCommand({
                        kind: 'set',
                        id: step.id,
                        patch: { actions: offered, remarks: next.length > 0 ? next : undefined },
                      })
                    }}
                  />
                  <span aria-hidden="true">{intl.formatMessage(dm.needsRemark)}</span>
                </label>
              ) : null}
            </li>
          )
        })}
      </ul>
      {offered.includes('FORWARD') ? (
        <Field label={intl.formatMessage(dm.forward)}>
          {(p) => (
            <Select
              {...p}
              value={step.forward?.within ?? ''}
              onChange={(e) => {
                const within = e.target.value as 'unit' | 'legalEntity' | 'anyone' | ''
                onCommand({ kind: 'set', id: step.id, patch: { forward: within ? { within } : undefined } })
              }}
            >
              <option value="">{intl.formatMessage(dm.forwardNoLimit)}</option>
              <option value="unit">{intl.formatMessage(dm.forwardUnit)}</option>
              <option value="legalEntity">{intl.formatMessage(dm.forwardLegalEntity)}</option>
              <option value="anyone">{intl.formatMessage(dm.forwardAnyone)}</option>
            </Select>
          )}
        </Field>
      ) : null}
    </fieldset>
  )
}

function ParallelEditor({
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
  const assignees = step.assignees ?? []
  const setAssignees = (next: AssigneeJson[]) => {
    onCommand({ kind: 'set', id: step.id, patch: { assignees: next } })
  }
  const modes: [NonNullable<StepJson['mode']>, MessageDescriptor][] = [
    ['all', dm.modeAll],
    ['any', dm.modeAny],
    ['n-of-m', dm.modeN],
  ]
  return (
    <div className="flex flex-col gap-2">
      <Field label={intl.formatMessage(dm.mode)}>
        {(p) => (
          <Select
            {...p}
            value={step.mode ?? 'all'}
            onChange={(e) => {
              const mode = e.target.value as NonNullable<StepJson['mode']>
              onCommand({
                kind: 'set',
                id: step.id,
                patch: {
                  mode,
                  n: mode === 'n-of-m' ? (step.n ?? Math.min(2, Math.max(assignees.length, 1))) : undefined,
                },
              })
            }}
          >
            {modes.map(([mode, label]) => (
              <option key={mode} value={mode}>
                {intl.formatMessage(label)}
              </option>
            ))}
          </Select>
        )}
      </Field>
      {step.mode === 'n-of-m' ? (
        <Field label={intl.formatMessage(dm.n)}>
          {(p) => (
            <Input
              {...p}
              type="number"
              min={1}
              max={20}
              value={step.n ?? 1}
              onChange={(e) => {
                const n = Number.parseInt(e.target.value, 10)
                onCommand({ kind: 'set', id: step.id, patch: { n: Number.isNaN(n) ? 1 : n } })
              }}
            />
          )}
        </Field>
      ) : null}
      {assignees.map((a, i) => (
        <div key={String(i)} className="flex items-start gap-2">
          <div className="flex-1">
            <AssigneeEditor
              legend={intl.formatMessage(dmv.assigneeN, { n: i + 1 })}
              value={a}
              labels={labels}
              onLabel={onLabel}
              onChange={(next) => {
                setAssignees(assignees.map((x, j) => (j === i ? next : x)))
              }}
            />
          </div>
          <Button
            variant="ghost"
            size="icon"
            aria-label={intl.formatMessage(dmv.removeAssignee, { n: i + 1 })}
            onClick={() => {
              setAssignees(assignees.filter((_, j) => j !== i))
            }}
          >
            <Trash2 aria-hidden="true" />
          </Button>
        </div>
      ))}
      <div>
        <Button
          variant="outline"
          disabled={assignees.length >= 20}
          onClick={() => {
            setAssignees([...assignees, { kind: 'person' }])
          }}
        >
          <Plus aria-hidden="true" />
          {intl.formatMessage(dm.addAssignee)}
        </Button>
      </div>
    </div>
  )
}

/** Whom a notify step tells, and the message key the notice uses (design E03 §4.2). */
function NotifyEditor({
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
  const recipients = step.recipients ?? []
  const setRecipients = (next: AssigneeJson[]) => {
    onCommand({ kind: 'set', id: step.id, patch: { recipients: next } })
  }
  return (
    <div className="flex flex-col gap-2">
      {recipients.map((a, i) => (
        <div key={String(i)} className="flex items-start gap-2">
          <div className="flex-1">
            <AssigneeEditor
              legend={intl.formatMessage(dmv.recipientN, { n: i + 1 })}
              value={a}
              labels={labels}
              onLabel={onLabel}
              allowInitiator
              onChange={(next) => {
                setRecipients(recipients.map((x, j) => (j === i ? next : x)))
              }}
            />
          </div>
          <Button
            variant="ghost"
            size="icon"
            aria-label={intl.formatMessage(dmv.removeRecipient, { n: i + 1 })}
            disabled={recipients.length === 1}
            onClick={() => {
              setRecipients(recipients.filter((_, j) => j !== i))
            }}
          >
            <Trash2 aria-hidden="true" />
          </Button>
        </div>
      ))}
      <div>
        <Button
          variant="outline"
          onClick={() => {
            setRecipients([...recipients, { kind: 'initiator' }])
          }}
        >
          <Plus aria-hidden="true" />
          {intl.formatMessage(dm.addRecipient)}
        </Button>
      </div>
      <Field label={intl.formatMessage(dm.message)} hint={intl.formatMessage(dm.messageHint)}>
        {(p) => (
          <Input
            {...p}
            dir="ltr"
            value={step.message ?? ''}
            onChange={(e) => {
              onCommand({ kind: 'set', id: step.id, patch: { message: e.target.value } })
            }}
          />
        )}
      </Field>
    </div>
  )
}

/**
 * The type-specific part of a step's panel (design E06 §3, T3): who it goes to and what the holder can do; a parallel
 * step's mode and people; its deadline; whom a notice tells; the sub-workflow or forum it runs. Conditions are in the
 * branch editor.
 */
export function StepDetails({
  step,
  labels,
  problems = [],
  onLabel,
  onCommand,
}: {
  step: StepJson
  labels: Labels
  /** The step's own problems, for the fields that show them in place. */
  problems?: FieldProblem[]
  onLabel: (id: string, label: string) => void
  onCommand: (command: Command) => void
}) {
  const intl = useIntl()
  const catalogue = useQuery(getBuilderCatalogueOptions())
  return (
    <div className="flex flex-col gap-3">
      {step.type === 'review' || step.type === 'approve' ? (
        <AssigneeEditor
          value={step.assignee}
          labels={labels}
          onLabel={onLabel}
          onChange={(assignee) => {
            onCommand({ kind: 'set', id: step.id, patch: { assignee } })
          }}
        />
      ) : null}
      {step.type === 'parallel' ? (
        <ParallelEditor step={step} labels={labels} onLabel={onLabel} onCommand={onCommand} />
      ) : null}
      {step.type === 'doa' ? <p className="text-sm text-muted-foreground">{intl.formatMessage(dm.doaHint)}</p> : null}
      {step.type === 'route' ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(dm.routeHint)}</p>
      ) : null}
      {HUMAN_STEPS.has(step.type) && step.type !== 'sign' ? <ActionsEditor step={step} onCommand={onCommand} /> : null}
      {HUMAN_STEPS.has(step.type) && step.type !== 'sign' ? (
        <SlaEditor step={step} labels={labels} onLabel={onLabel} onCommand={onCommand} />
      ) : null}
      {step.type === 'notify' ? (
        <NotifyEditor step={step} labels={labels} onLabel={onLabel} onCommand={onCommand} />
      ) : null}
      {step.type === 'subworkflow' ? (
        <Field label={intl.formatMessage(dm.fragment)}>
          {(p) => (
            <Select
              {...p}
              value={step.definition ?? ''}
              onChange={(e) => {
                onCommand({ kind: 'set', id: step.id, patch: { definition: e.target.value } })
              }}
            >
              <option value="">{intl.formatMessage(dm.choose)}</option>
              {(catalogue.data?.fragments ?? []).map((f) => (
                <option key={f.key} value={f.key}>
                  {f.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
      ) : null}
      {step.type === 'escalate-to-forum' ? (
        <>
          <ForumPicker step={step} problems={problems} onCommand={onCommand} />
          <WaitSwitch step={step} onCommand={onCommand} />
        </>
      ) : null}
    </div>
  )
}

/**
 * The committee an "Escalate to committee" step waits for (flow L2): a list of the active committees (name and code)
 * from `GET /admin/workflow/escalation-targets`. A key that names no active committee, as an imported definition may,
 * stays selected and is flagged "This committee does not exist or is dissolved".
 */
function ForumPicker({
  step,
  problems,
  onCommand,
}: {
  step: StepJson
  problems: FieldProblem[]
  onCommand: (command: Command) => void
}) {
  const intl = useIntl()
  const terms = useTerms()
  const targets = useQuery(listEscalationTargetsOptions())
  const list = (targets.data ?? []).flatMap((t) =>
    t.key ? [{ key: t.key, name: t.name ?? t.key, code: t.code ?? '' }] : [],
  )
  const value = step.forum ?? ''
  const listed = value === '' || list.some((t) => t.key === value)
  const unknown = problems.some(isUnknownForum) || (targets.isSuccess && !listed)
  return (
    <Field
      label={intl.formatMessage(dmt.forum, terms)}
      hint={intl.formatMessage(dmt.forumHint, terms)}
      error={unknown ? intl.formatMessage(dmt.unknownForum, terms) : undefined}
    >
      {(p) => (
        <Select
          {...p}
          value={value}
          onChange={(e) => {
            onCommand({ kind: 'set', id: step.id, patch: { forum: e.target.value } })
          }}
        >
          <option value="">{intl.formatMessage(dm.choose)}</option>
          {listed ? null : <option value={value}>{intl.formatMessage(dmv.forumUnknownKey, { key: value })}</option>}
          {list.map((t) => (
            <option key={t.key} value={t.key}>
              {intl.formatMessage(dmv.forumOption, { name: t.name, code: t.code })}
            </option>
          ))}
        </Select>
      )}
    </Field>
  )
}

/**
 * "Wait for the committee's decision" (flow M4, ADR-0058): on, the memo waits at this step for the decision; off, it is
 * sent for information and the route continues. New steps start on; a step saved before E02 has no `wait` and is off.
 */
function WaitSwitch({ step, onCommand }: { step: StepJson; onCommand: (command: Command) => void }) {
  const intl = useIntl()
  const terms = useTerms()
  const id = useId()
  const on = step.wait === true
  return (
    <div className="flex flex-col gap-1">
      <label className="flex min-h-11 items-center gap-3 text-sm font-medium">
        <Checkbox
          role="switch"
          className="size-5"
          checked={on}
          aria-describedby={id}
          onChange={(e) => {
            onCommand({ kind: 'set', id: step.id, patch: { wait: e.target.checked } })
          }}
        />
        {intl.formatMessage(dmt.wait, terms)}
      </label>
      <p id={id} className="text-xs text-muted-foreground">
        {intl.formatMessage(on ? dmt.waitOn : dmt.waitOff, terms)}
      </p>
    </div>
  )
}
