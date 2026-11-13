import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { getWorkflowDefinitionVersionOptions } from '@/api/generated/@tanstack/react-query.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Field } from '@/components/ui/field'
import { Select } from '@/components/ui/form-controls'
import { bm } from './builder-messages'
import { DefinitionDiagram } from './DefinitionDiagram'
import { asDefinition, type DefinitionBody } from './definition'
import { describeType, stepName, type Labels } from './describe'
import { describeChange } from './describe-change'
import { diff, isEmpty, type Change, type StepChange } from './diff'

const vm = defineMessages({
  compareWith: { id: 'builder.changes.compareWith', defaultMessage: 'Compared with' },
  same: { id: 'builder.changes.same', defaultMessage: 'Nothing changed between these versions.' },
  workflow: { id: 'builder.changes.workflow', defaultMessage: 'The workflow as a whole' },
  list: { id: 'builder.changes.list', defaultMessage: 'Changes, step by step' },
  unreadable: {
    id: 'builder.changes.unreadable',
    defaultMessage: 'That version cannot be compared: its body is not a workflow.',
  },
})

const vmv = defineMessages<{
  version: { number: number }
  summary: { added: number; removed: number; changed: number }
  line: { before: string; after: string }
}>({
  version: { id: 'builder.changes.version', defaultMessage: 'Version {number}' },
  summary: {
    id: 'builder.changes.summary',
    defaultMessage:
      '{added, plural, =0 {} one {# step added. } other {# steps added. }}{removed, plural, =0 {} one {# step removed. } other {# steps removed. }}{changed, plural, =0 {} one {# step changed.} other {# steps changed.}}',
  },
  line: { id: 'builder.changes.line', defaultMessage: '{before} → {after}' },
})

const kindLabels = { added: bm.added, removed: bm.removed, changed: bm.changed } as const
const kindTones = { added: 'ok', removed: 'danger', changed: 'warn' } as const

function ChangeList({
  changes,
  labels,
  before,
  body,
}: {
  changes: Change[]
  labels: Labels
  before: DefinitionBody
  body: DefinitionBody
}) {
  const intl = useIntl()
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
      {changes.map((change) => {
        const words = describeChange(intl, labels, before.steps, body.steps, change)
        return (
          <div key={change.path} className="contents">
            <dt className="font-medium">{words.field}</dt>
            <dd dir="auto">{intl.formatMessage(vmv.line, { before: words.before, after: words.after })}</dd>
          </div>
        )
      })}
    </dl>
  )
}

function StepChangeItem({
  change,
  labels,
  before,
  body,
}: {
  change: StepChange
  labels: Labels
  before: DefinitionBody
  body: DefinitionBody
}) {
  const intl = useIntl()
  return (
    <li className="rounded-md border p-3">
      <div className="mb-1 flex flex-wrap items-center gap-2">
        <h4 className="font-semibold" dir="auto">
          {stepName(change.step)}
        </h4>
        <Badge tone="neutral">{describeType(intl, change.step)}</Badge>
        <Badge tone={kindTones[change.kind]}>{intl.formatMessage(kindLabels[change.kind])}</Badge>
      </div>
      {change.changes.length > 0 ? (
        <ChangeList changes={change.changes} labels={labels} before={before} body={body} />
      ) : null}
    </li>
  )
}

/**
 * The review's Changes view (design E06 §6, ADR-0042): this version against another (the published one by default),
 * steps matched by id. The diagram marks added and changed steps with a word and an icon, never colour alone; the list
 * says what changed in words, removed steps included.
 */
export function ChangesView({
  definitionKey,
  number,
  body,
  labels,
  versions,
  compareWith,
}: {
  definitionKey: string
  number: number
  body: DefinitionBody
  labels: Labels
  /** Every version number of the definition. */
  versions: number[]
  compareWith: number
}) {
  const intl = useIntl()
  const [other, setOther] = useState(compareWith)
  const query = useQuery(getWorkflowDefinitionVersionOptions({ path: { key: definitionKey, number: String(other) } }))
  const before = query.data ? asDefinition(query.data.body) : undefined
  const result = useMemo(() => (before ? diff(before, body) : undefined), [before, body])
  const allLabels = useMemo(() => ({ ...(query.data?.labels ?? {}), ...labels }), [query.data, labels])
  const marks = useMemo(
    () =>
      new Map(
        (result?.steps ?? [])
          .filter((s) => s.kind !== 'removed')
          .map((s) => [s.id, s.kind as 'added' | 'changed'] as const),
      ),
    [result],
  )
  const count = (kind: StepChange['kind']) => result?.steps.filter((s) => s.kind === kind).length ?? 0

  return (
    <div className="flex flex-col gap-3">
      <Field label={intl.formatMessage(vm.compareWith)}>
        {(p) => (
          <Select
            {...p}
            className="w-48"
            value={String(other)}
            onChange={(e) => {
              setOther(Number(e.target.value))
            }}
          >
            {versions
              .filter((n) => n !== number)
              .map((n) => (
                <option key={n} value={n}>
                  {intl.formatMessage(vmv.version, { number: n })}
                </option>
              ))}
          </Select>
        )}
      </Field>
      {query.isPending ? (
        <LoadingState rows={4} />
      ) : query.isError ? (
        <ErrorState onRetry={() => void query.refetch()} />
      ) : !result ? (
        <p className="text-sm">{intl.formatMessage(vm.unreadable)}</p>
      ) : isEmpty(result) ? (
        <p role="status" className="text-sm">
          {intl.formatMessage(vm.same)}
        </p>
      ) : (
        <>
          <p role="status" className="text-sm font-medium">
            {intl.formatMessage(vmv.summary, {
              added: count('added'),
              removed: count('removed'),
              changed: count('changed'),
            })}
          </p>
          <DefinitionDiagram body={body} labels={allLabels} problems={[]} changes={marks} />
          {result.top.length > 0 ? (
            <section aria-labelledby="changes-workflow" className="rounded-md border p-3">
              <h4 id="changes-workflow" className="mb-1 font-semibold">
                {intl.formatMessage(vm.workflow)}
              </h4>
              <ChangeList changes={result.top} labels={allLabels} before={before ?? body} body={body} />
            </section>
          ) : null}
          <ol aria-label={intl.formatMessage(vm.list)} className="flex flex-col gap-2">
            {result.steps.map((s) => (
              <StepChangeItem
                key={`${s.kind}-${s.id}`}
                change={s}
                labels={allLabels}
                before={before ?? body}
                body={body}
              />
            ))}
          </ol>
        </>
      )}
    </div>
  )
}
