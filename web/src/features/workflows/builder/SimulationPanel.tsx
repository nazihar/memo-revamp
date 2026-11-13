import { useQuery } from '@tanstack/react-query'
import { Play, Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { defineMessages, useIntl, type IntlShape } from 'react-intl'
import { listMemoTypesOptions } from '@/api/generated/@tanstack/react-query.gen'
import { simulateWorkflowDefinition } from '@/api/generated/sdk.gen'
import type { SimulatedStepResponse, SimulationResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import type { DefinitionBody, SlaJson } from './definition'
import { describeSla, type Labels } from './describe'

const sm = defineMessages({
  title: { id: 'builder.sim.title', defaultMessage: 'Simulate' },
  lead: {
    id: 'builder.sim.lead',
    defaultMessage:
      'Who this workflow would reach for an item like this, started now, if everyone approves. Nothing is saved or sent.',
  },
  initiator: { id: 'builder.sim.initiator', defaultMessage: 'Started by' },
  route: { id: 'builder.sim.route', defaultMessage: 'Approvers the initiator chooses' },
  addApprover: { id: 'builder.sim.addApprover', defaultMessage: 'Add an approver' },
  run: { id: 'builder.sim.run', defaultMessage: 'Simulate' },
  running: { id: 'builder.sim.running', defaultMessage: 'Simulating…' },
  needInitiator: { id: 'builder.sim.needInitiator', defaultMessage: 'Choose who starts it.' },
  invalid: {
    id: 'builder.sim.invalid',
    defaultMessage: 'The workflow has problems. Fix them, then simulate again.',
  },
  limited: { id: 'builder.sim.limited', defaultMessage: 'Too many simulations. Try again in a minute.' },
  failed: { id: 'builder.sim.failed', defaultMessage: 'The simulation could not run.' },
  approved: { id: 'builder.sim.approved', defaultMessage: 'It would be approved.' },
  blocked: {
    id: 'builder.sim.blocked',
    defaultMessage: 'It would stop and wait for an administrator: someone it reaches cannot take it.',
  },
  limit: { id: 'builder.sim.limit', defaultMessage: 'It took too many steps; the simulation stopped.' },
  path: { id: 'builder.sim.path', defaultMessage: 'Steps it would reach, in order' },
  decided: { id: 'builder.sim.outcome.decided', defaultMessage: 'Decided' },
  stopped: { id: 'builder.sim.outcome.blocked', defaultMessage: 'Stops here' },
  notified: { id: 'builder.sim.outcome.notified', defaultMessage: 'Told' },
  branched: { id: 'builder.sim.outcome.branched', defaultMessage: 'Branch' },
  passed: { id: 'builder.sim.outcome.passed', defaultMessage: 'Passed' },
  otherwiseTaken: { id: 'builder.sim.otherwise', defaultMessage: 'No condition held: otherwise.' },
  noClearance: { id: 'builder.sim.noClearance', defaultMessage: 'Not cleared for this classification.' },
  inactive: { id: 'builder.sim.inactive', defaultMessage: 'The account is not active.' },
  unresolved: { id: 'builder.sim.unresolved', defaultMessage: 'Nobody could be found for this step.' },
  yes: { id: 'builder.sim.true', defaultMessage: 'Yes' },
  no: { id: 'builder.sim.false', defaultMessage: 'No' },
  memoType: { id: 'builder.sim.memoType', defaultMessage: 'Memo type' },
  classificationRank: { id: 'builder.sim.classificationRank', defaultMessage: 'Classification rank' },
})

const smv = defineMessages<{
  conditionHeld: { n: number; condition: string }
  actingFor: { name: string }
  approverN: { n: number }
  removeApprover: { n: number }
  group: { n: number }
}>({
  conditionHeld: { id: 'builder.sim.conditionHeld', defaultMessage: 'Condition {n} held: {condition}' },
  actingFor: { id: 'builder.sim.actingFor', defaultMessage: 'for {name}' },
  approverN: { id: 'builder.sim.approverN', defaultMessage: 'Approver {n}' },
  removeApprover: { id: 'builder.sim.removeApprover', defaultMessage: 'Remove approver {n}' },
  group: { id: 'builder.sim.group', defaultMessage: 'Approval group {n}' },
})

type Outcome = SimulatedStepResponse['outcome']

const outcomeLabels = {
  DECIDED: sm.decided,
  BLOCKED: sm.stopped,
  NOTIFIED: sm.notified,
  BRANCHED: sm.branched,
  PASSED: sm.passed,
}
const outcomeTones: Record<Outcome, 'ok' | 'warn' | 'danger' | 'neutral'> = {
  DECIDED: 'ok',
  BLOCKED: 'danger',
  NOTIFIED: 'neutral',
  BRANCHED: 'neutral',
  PASSED: 'neutral',
}

function blockedText(intl: IntlShape, reason: SimulatedStepResponse['blockedReason']): string {
  if (reason === 'NO_CLEARANCE') return intl.formatMessage(sm.noClearance)
  if (reason === 'INACTIVE') return intl.formatMessage(sm.inactive)
  return intl.formatMessage(sm.unresolved)
}

/** One sample value per attribute the definition declares, by its type. */
function AttributeInput({
  name,
  type,
  value,
  onChange,
}: {
  name: string
  type: string
  value: unknown
  onChange: (value: unknown) => void
}) {
  const intl = useIntl()
  const types = useQuery({ ...listMemoTypesOptions(), enabled: name === 'memoTypeId' })
  const label =
    name === 'classificationRank'
      ? intl.formatMessage(sm.classificationRank)
      : name === 'memoTypeId'
        ? intl.formatMessage(sm.memoType)
        : name
  if (name === 'memoTypeId') {
    return (
      <Field label={label}>
        {(p) => (
          <Select
            {...p}
            value={typeof value === 'string' ? value : ''}
            onChange={(e) => {
              onChange(e.target.value || undefined)
            }}
          >
            <option value="" />
            {(types.data ?? []).map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
    )
  }
  if (type === 'bool') {
    return (
      <Field label={label}>
        {(p) => (
          <Select
            {...p}
            value={value === true ? 'true' : value === false ? 'false' : ''}
            onChange={(e) => {
              onChange(e.target.value === '' ? undefined : e.target.value === 'true')
            }}
          >
            <option value="" />
            <option value="true">{intl.formatMessage(sm.yes)}</option>
            <option value="false">{intl.formatMessage(sm.no)}</option>
          </Select>
        )}
      </Field>
    )
  }
  const numeric = type === 'int' || type === 'double'
  return (
    <Field label={label}>
      {(p) => (
        <Input
          {...p}
          dir="auto"
          {...(numeric ? { type: 'number', step: type === 'int' ? 1 : 'any' } : {})}
          value={typeof value === 'number' || typeof value === 'string' ? String(value) : ''}
          onChange={(e) => {
            const raw = e.target.value
            onChange(raw === '' ? undefined : numeric ? Number(raw) : raw)
          }}
        />
      )}
    </Field>
  )
}

/**
 * The simulation (design E06 §4, T6): sample values for the attributes the definition declares, who starts it and, for a
 * route step, the approvers; the server runs the body in memory and answers the steps it would reach. [onResult]
 * passes the reached step ids to the diagram and the Steps view.
 */
export function SimulationPanel({
  definitionKey,
  number,
  body,
  labels,
  result,
  onResult,
}: {
  definitionKey: string
  number: number
  body: DefinitionBody
  labels: Labels
  result: SimulationResponse | undefined
  onResult: (result: SimulationResponse | undefined) => void
}) {
  const intl = useIntl()
  const [values, setValues] = useState<Record<string, unknown>>({})
  const [initiator, setInitiator] = useState<Picked>()
  const [route, setRoute] = useState<(Picked | undefined)[]>([undefined])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const routed = body.steps.some((s) => s.type === 'route')

  const run = async () => {
    if (!initiator) {
      setError(intl.formatMessage(sm.needInitiator))
      return
    }
    setBusy(true)
    setError(undefined)
    try {
      const attributes = Object.fromEntries(Object.entries(values).filter(([, v]) => v !== undefined))
      const entries = routed ? route.filter((p): p is Picked => p !== undefined).map((p) => ({ userId: p.id })) : []
      const response = await simulateWorkflowDefinition({
        path: { key: definitionKey, number: String(number) },
        body: { body, initiatorId: initiator.id, attributes, route: entries },
        throwOnError: true,
      })
      onResult(response.data)
    } catch (problem) {
      const p = isProblem(problem) ? problem : undefined
      onResult(undefined)
      setError(
        intl.formatMessage(
          p?.reason === 'DEFINITION_INVALID' ? sm.invalid : p?.status === 429 ? sm.limited : sm.failed,
        ),
      )
    } finally {
      setBusy(false)
    }
  }

  const summary = result?.ended === 'APPROVED' ? sm.approved : result?.ended === 'BLOCKED' ? sm.blocked : sm.limit
  return (
    <section aria-labelledby="simulation-title" className="rounded-md border p-4">
      <h3 id="simulation-title" className="text-base font-semibold">
        {intl.formatMessage(sm.title)}
      </h3>
      <p className="mb-3 text-sm text-muted-foreground">{intl.formatMessage(sm.lead)}</p>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={intl.formatMessage(sm.initiator)} required>
          {(p) => (
            <PeoplePicker
              id={p.id}
              kind="user"
              {...(initiator ? { value: initiator } : {})}
              {...(p['aria-describedby'] ? { describedBy: p['aria-describedby'] } : {})}
              onChange={setInitiator}
            />
          )}
        </Field>
        {(body.attributes ?? []).map((a) => (
          <AttributeInput
            key={a.name}
            name={a.name}
            type={a.type}
            value={values[a.name]}
            onChange={(v) => {
              setValues((current) => ({ ...current, [a.name]: v }))
            }}
          />
        ))}
      </div>
      {routed ? (
        <fieldset className="mt-3 flex flex-col gap-2">
          <legend className="text-sm font-medium">{intl.formatMessage(sm.route)}</legend>
          {route.map((picked, i) => (
            <div key={String(i)} className="flex items-end gap-2">
              <div className="flex-1">
                <Field label={intl.formatMessage(smv.approverN, { n: i + 1 })}>
                  {(p) => (
                    <PeoplePicker
                      id={p.id}
                      kind="user"
                      {...(picked ? { value: picked } : {})}
                      onChange={(next) => {
                        setRoute(route.map((x, j) => (j === i ? next : x)))
                      }}
                    />
                  )}
                </Field>
              </div>
              <Button
                variant="ghost"
                size="icon"
                aria-label={intl.formatMessage(smv.removeApprover, { n: i + 1 })}
                disabled={route.length === 1}
                onClick={() => {
                  setRoute(route.filter((_, j) => j !== i))
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
                setRoute([...route, undefined])
              }}
            >
              <Plus aria-hidden="true" />
              {intl.formatMessage(sm.addApprover)}
            </Button>
          </div>
        </fieldset>
      ) : null}
      <div className="mt-3">
        <Button disabled={busy} onClick={() => void run()}>
          <Play aria-hidden="true" />
          {intl.formatMessage(busy ? sm.running : sm.run)}
        </Button>
      </div>
      <div role="status" className="mt-3 text-sm">
        {error ? <p className="text-destructive">{error}</p> : null}
        {result ? <p className="font-medium">{intl.formatMessage(summary)}</p> : null}
      </div>
      {result ? (
        <ol aria-label={intl.formatMessage(sm.path)} className="mt-2 flex flex-col gap-2">
          {result.steps.map((step, i) => (
            <SimulatedStep key={`${step.stepId}-${String(i)}`} step={step} labels={labels} />
          ))}
        </ol>
      ) : null}
    </section>
  )
}

function SimulatedStep({ step, labels }: { step: SimulatedStepResponse; labels: Labels }) {
  const intl = useIntl()
  const people = step.people.map((p) =>
    p.actingFor ? `${p.name} (${intl.formatMessage(smv.actingFor, { name: p.actingFor.name })})` : p.name,
  )
  return (
    <li className="rounded-md border p-2 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium" dir="auto">
          {step.name}
        </span>
        {step.group != null ? (
          <span className="text-muted-foreground">{intl.formatMessage(smv.group, { n: step.group + 1 })}</span>
        ) : null}
        <Badge tone={outcomeTones[step.outcome]}>{intl.formatMessage(outcomeLabels[step.outcome])}</Badge>
      </div>
      {people.length > 0 ? <p dir="auto">{intl.formatList(people, { type: 'conjunction' })}</p> : null}
      {step.outcome === 'BLOCKED' ? <p className="text-destructive">{blockedText(intl, step.blockedReason)}</p> : null}
      {step.outcome === 'BRANCHED' ? (
        <p>
          <bdi>
            {step.branch != null && step.condition
              ? intl.formatMessage(smv.conditionHeld, { n: step.branch + 1, condition: step.condition })
              : intl.formatMessage(sm.otherwiseTaken)}
          </bdi>
        </p>
      ) : null}
      {step.sla ? (
        <p className="text-muted-foreground">{describeSla(intl, labels, step.sla as unknown as SlaJson)}</p>
      ) : null}
    </li>
  )
}
