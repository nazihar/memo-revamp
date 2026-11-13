import { useState } from 'react'
import { useIntl } from 'react-intl'
import type { FieldProblem } from '@/api/generated/types.gen'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { bm, bmv } from './builder-messages'
import type { DefinitionBody, StepJson } from './definition'
import { describeSla, describeType, describeWho, stepName, targetName, type Labels } from './describe'
import type { Command } from './edit'
import { START_NODE } from './model'
import { problemsByStep } from './problems'
import { AddStep, StepPanel } from './StepPanel'

/**
 * The Steps view (design E06 §7): the definition as an ordered list, one group per step with what the diagram shows,
 * in words. It is the keyboard and screen-reader path, so it never needs the diagram.
 */
export function StepsView({
  body,
  labels,
  problems,
  onCommand,
  onLabel,
}: {
  body: DefinitionBody
  labels: Labels
  problems: FieldProblem[]
  /** Set for a draft the user may change: each step gets its panel, and steps can be added at the start. */
  onCommand?: (command: Command) => void
  /** Names picked in a panel before the draft is saved. */
  onLabel?: (id: string, label: string) => void
}) {
  const intl = useIntl()
  const byStep = problemsByStep(problems)
  return (
    <div className="flex flex-col gap-3">
      {onCommand ? (
        <AddStep body={body} after={START_NODE} label={intl.formatMessage(bm.addFirst)} onCommand={onCommand} />
      ) : null}
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 text-sm">
        <dt className="font-medium">{intl.formatMessage(bm.start)}</dt>
        <dd dir="auto">{targetName(intl, body.steps, body.start)}</dd>
      </dl>
      <ol aria-label={intl.formatMessage(bm.stepsLabel)} className="flex flex-col gap-3">
        {body.steps.map((step, i) => (
          <StepItem
            key={`${step.id}-${String(i)}`}
            step={step}
            index={i}
            steps={body.steps}
            labels={labels}
            problems={byStep.get(i)?.length ?? 0}
            body={body}
            allProblems={problems}
            onCommand={onCommand}
            onLabel={onLabel}
          />
        ))}
      </ol>
    </div>
  )
}

function StepItem({
  step,
  index,
  steps,
  labels,
  problems,
  body,
  allProblems,
  onCommand,
  onLabel,
}: {
  step: StepJson
  index: number
  steps: StepJson[]
  labels: Labels
  problems: number
  body: DefinitionBody
  allProblems: FieldProblem[]
  onCommand?: (command: Command) => void
  onLabel?: (id: string, label: string) => void
}) {
  const intl = useIntl()
  const [open, setOpen] = useState(false)
  const headingId = `step-${String(index)}-heading`
  const panelId = `step-${String(index)}-panel`
  const who = describeWho(intl, labels, step)
  const target = (t: string | undefined) => targetName(intl, steps, t)
  return (
    <li aria-labelledby={headingId} className="rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <h4 id={headingId} className="font-semibold" dir="auto">
          {intl.formatMessage(bmv.stepHeading, { index: index + 1, name: stepName(step) })}
        </h4>
        <Badge tone="neutral">{describeType(intl, step)}</Badge>
        {problems > 0 ? <Badge tone="danger">{intl.formatMessage(bmv.problems, { count: problems })}</Badge> : null}
        <code dir="ltr" className="text-xs text-muted-foreground">
          {step.id}
        </code>
        {onCommand ? (
          <Button
            variant="outline"
            className="ms-auto"
            aria-expanded={open}
            aria-controls={panelId}
            aria-describedby={headingId}
            onClick={() => {
              setOpen(!open)
            }}
          >
            {intl.formatMessage(open ? bm.doneEditing : bm.edit)}
          </Button>
        ) : null}
      </div>
      <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        {who ? (
          <>
            <dt className="font-medium">{intl.formatMessage(step.type === 'notify' ? bm.tells : bm.goesTo)}</dt>
            <dd dir="auto">{who}</dd>
          </>
        ) : null}
        {step.sla ? (
          <>
            <dt className="font-medium">{intl.formatMessage(bm.deadline)}</dt>
            <dd>{describeSla(intl, labels, step.sla)}</dd>
          </>
        ) : null}
        {step.type === 'subworkflow' ? (
          <>
            <dt className="font-medium">{intl.formatMessage(bm.runs)}</dt>
            <dd dir="ltr">{step.definition ?? ''}</dd>
          </>
        ) : null}
        {step.type === 'escalate-to-forum' ? (
          <>
            <dt className="font-medium">{intl.formatMessage(bm.forum)}</dt>
            <dd dir="ltr">{step.forum ?? ''}</dd>
          </>
        ) : null}
        {step.type === 'branch' ? (
          <>
            {(step.branches ?? []).map((b, i) => (
              <BranchLine key={`${String(i)}-${b.when}`} condition={b.when} target={target(b.next)} />
            ))}
            <dt className="font-medium">{intl.formatMessage(bm.otherwise)}</dt>
            <dd dir="auto">{target(step.otherwise)}</dd>
          </>
        ) : (
          <>
            <dt className="font-medium">{intl.formatMessage(bm.leadsTo)}</dt>
            <dd dir="auto">{target(step.next)}</dd>
          </>
        )}
      </dl>
      {onCommand && open ? (
        <div id={panelId} className="mt-3 border-t pt-3">
          <StepPanel
            body={body}
            step={step}
            problems={allProblems}
            labels={labels}
            onLabel={onLabel ?? ignore}
            onCommand={onCommand}
          />
        </div>
      ) : null}
    </li>
  )
}

function ignore() {
  // names are only shown, not kept, without a handler
}

function BranchLine({ condition, target }: { condition: string; target: string }) {
  const intl = useIntl()
  return (
    <>
      <dt className="font-medium">
        <bdi>{intl.formatMessage(bmv.ifCondition, { condition })}</bdi>
      </dt>
      <dd dir="auto">{target}</dd>
    </>
  )
}
