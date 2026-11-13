import { useQuery } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import { defineMessages, useIntl, type MessageDescriptor } from 'react-intl'
import { getBuilderCatalogueOptions } from '@/api/generated/@tanstack/react-query.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select } from '@/components/ui/form-controls'
import { AssigneeEditor } from './AssigneeEditor'
import { HUMAN_STEPS, type AssigneeJson, type StepJson } from './definition'
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
  forum: { id: 'builder.forum.field', defaultMessage: 'Forum key' },
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

const dmv = defineMessages<{
  assigneeN: { n: number }
  removeAssignee: { n: number }
  remarkFor: { action: string }
  recipientN: { n: number }
  removeRecipient: { n: number }
}>({
  recipientN: { id: 'builder.notify.recipientN', defaultMessage: 'Tell {n}' },
  removeRecipient: { id: 'builder.notify.remove', defaultMessage: 'Remove person or group {n} to tell' },
  assigneeN: { id: 'builder.parallel.assigneeN', defaultMessage: 'Person or group {n}' },
  removeAssignee: { id: 'builder.parallel.remove', defaultMessage: 'Remove person or group {n}' },
  remarkFor: { id: 'builder.actions.remarkFor', defaultMessage: '{action} needs a remark' },
})

const actionLabels = defineMessages({
  APPROVE: { id: 'builder.action.approve', defaultMessage: 'Approve' },
  RECOMMEND: { id: 'builder.action.recommend', defaultMessage: 'Recommend' },
  RETURN: { id: 'builder.action.return', defaultMessage: 'Return to the initiator' },
  REJECT: { id: 'builder.action.reject', defaultMessage: 'Reject' },
  SEND_BACK: { id: 'builder.action.sendBack', defaultMessage: 'Send back to an earlier step' },
  FORWARD: { id: 'builder.action.forward', defaultMessage: 'Forward' },
  DELEGATE: { id: 'builder.action.delegate', defaultMessage: 'Delegate' },
  ASK: { id: 'builder.action.ask', defaultMessage: 'Ask for opinions' },
  REFER: { id: 'builder.action.refer', defaultMessage: 'Refer' },
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
  onLabel,
  onCommand,
}: {
  step: StepJson
  labels: Labels
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
        <Field label={intl.formatMessage(dm.forum)}>
          {(p) => (
            <Input
              {...p}
              dir="ltr"
              value={step.forum ?? ''}
              onChange={(e) => {
                onCommand({ kind: 'set', id: step.id, patch: { forum: e.target.value } })
              }}
            />
          )}
        </Field>
      ) : null}
    </div>
  )
}
