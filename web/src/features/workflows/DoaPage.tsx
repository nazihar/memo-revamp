import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getDoaVersionOptions,
  getDoaVersionQueryKey,
  listDoaVersionsOptions,
  listDoaVersionsQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import {
  approveDoaVersion,
  createDoaDraft,
  replaceDoaDraft,
  sendBackDoaVersion,
  simulateDoa,
  submitDoaVersion,
} from '@/api/generated/sdk.gen'
import type { DoaRule, DoaSimulationResponse, DoaVersionResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge, type Tone } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select, Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { dm, dmv } from './doa-messages'
import { DoaRulesEditor } from './DoaRulesEditor'
import { useDoaNames } from './doa-names'
import { stateLabels } from './workflow-messages'

const tones: Record<DoaVersionResponse['state'], Tone> = {
  DRAFT: 'neutral',
  IN_REVIEW: 'warn',
  PUBLISHED: 'ok',
  RETIRED: 'neutral',
}

/**
 * `/admin/workflow/doa` (design E04 §3, §5, ux-review flow 5): the DoA's versions; a draft's date and rules, edited and
 * submitted by holders of `workflow.doa.manage`; a version in review, published (after a step-up) or sent back by a
 * holder of `workflow.doa.approve` who did not submit it; and a simulator.
 */
export function DoaPage() {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const list = useQuery(listDoaVersionsOptions())
  const [open, setOpen] = useState<number>()
  const [busy, setBusy] = useState(false)
  const versions = list.data ?? []
  const selected = open ?? versions[0]?.number

  const refusal = (problem: unknown) => {
    const reason = isProblem(problem) ? problem.reason : undefined
    toast.show(intl.formatMessage(reason && reason in dm ? dm[reason as keyof typeof dm] : dm.failed), 'error')
  }

  const newDraft = async () => {
    setBusy(true)
    try {
      const created = await createDoaDraft({ body: {}, throwOnError: true })
      await queryClient.invalidateQueries({ queryKey: listDoaVersionsQueryKey() })
      setOpen(created.data.number)
    } catch (problem) {
      refusal(problem)
    } finally {
      setBusy(false)
    }
  }

  const date = (value?: string | null) =>
    value ? intl.formatDate(`${value}T00:00:00`, { dateStyle: 'medium' }) : intl.formatMessage(dm.notSet)
  const columns: DataTableColumn<DoaVersionResponse>[] = [
    {
      id: 'version',
      header: intl.formatMessage(dm.colVersion),
      cell: ({ row }) => intl.formatMessage(dmv.version, { number: row.original.number }),
    },
    {
      id: 'state',
      header: intl.formatMessage(dm.colState),
      cell: ({ row }) => (
        <Badge tone={tones[row.original.state]}>{intl.formatMessage(stateLabels[row.original.state])}</Badge>
      ),
    },
    {
      id: 'effective',
      header: intl.formatMessage(dm.colEffective),
      cell: ({ row }) => date(row.original.effectiveFrom),
    },
    {
      id: 'submitted',
      header: intl.formatMessage(dm.colSubmitted),
      cell: ({ row }) => row.original.submittedBy?.name ?? '',
    },
    {
      id: 'reviewed',
      header: intl.formatMessage(dm.colReviewed),
      cell: ({ row }) => row.original.reviewedBy?.name ?? '',
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{intl.formatMessage(dm.colActions)}</span>,
      cell: ({ row }) => (
        <Button
          variant={row.original.number === selected ? 'default' : 'outline'}
          aria-pressed={row.original.number === selected}
          aria-label={intl.formatMessage(dmv.openVersion, { number: row.original.number })}
          onClick={() => {
            setOpen(row.original.number)
          }}
        >
          {intl.formatMessage(dm.open)}
        </Button>
      ),
    },
  ]

  return (
    <section aria-labelledby="doa-title" className="flex flex-col gap-6">
      <PageHeader
        title={<span id="doa-title">{intl.formatMessage(dm.title)}</span>}
        description={intl.formatMessage(dm.lead)}
        actions={
          <Button disabled={busy} onClick={() => void newDraft()}>
            {intl.formatMessage(dm.newDraft)}
          </Button>
        }
      />
      <DataTable
        caption={intl.formatMessage(dm.caption)}
        columns={columns}
        rows={versions}
        getRowId={(row) => String(row.number)}
        state={list.isPending ? 'loading' : list.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void list.refetch() }}
        empty={intl.formatMessage(dm.empty)}
      />
      {selected != null ? <VersionPanel key={selected} number={selected} onRefusal={refusal} /> : null}
      <Simulator versions={versions} />
    </section>
  )
}

function VersionPanel({ number, onRefusal }: { number: number; onRefusal: (problem: unknown) => void }) {
  const intl = useIntl()
  const toast = useToast()
  const withStepUp = useWithStepUp()
  const queryClient = useQueryClient()
  const path = { path: { number: String(number) } }
  const version = useQuery(getDoaVersionOptions(path))
  const [rules, setRules] = useState<DoaRule[]>()
  const [effective, setEffective] = useState<string>()
  const [remark, setRemark] = useState('')
  const [problems, setProblems] = useState<string[]>([])
  const [busy, setBusy] = useState(false)

  if (version.isPending) return <LoadingState />
  if (version.isError) return <ErrorState onRetry={() => void version.refetch()} />
  const v = version.data
  const draft = v.state === 'DRAFT'
  const current = rules ?? v.rules ?? []
  const effectiveFrom = effective ?? v.effectiveFrom ?? ''

  const run = async (call: () => Promise<unknown>) => {
    setBusy(true)
    setProblems([])
    try {
      await call()
      toast.show(intl.formatMessage(dm.done), 'success')
      setRules(undefined)
      setEffective(undefined)
      setRemark('')
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: getDoaVersionQueryKey(path) }),
        queryClient.invalidateQueries({ queryKey: listDoaVersionsQueryKey() }),
      ])
    } catch (problem) {
      const p = isProblem(problem) ? problem : undefined
      if (p?.errors?.length) {
        setProblems(p.errors.map((e) => `${e.field}: ${e.message}`))
      } else if (p?.status === 412 || p?.type?.endsWith('conflict')) {
        toast.show(intl.formatMessage(dm.conflict), 'error')
        setRules(undefined)
        await version.refetch()
      } else {
        onRefusal(problem)
      }
    } finally {
      setBusy(false)
    }
  }

  const ifMatch = { 'If-Match': `"${v.revision}"` }
  const save = () =>
    run(() =>
      replaceDoaDraft({
        ...path,
        headers: ifMatch,
        body: { effectiveFrom: effectiveFrom || undefined, rules: current },
        throwOnError: true,
      }),
    )
  const submit = () => run(() => submitDoaVersion({ ...path, headers: ifMatch, throwOnError: true }))
  const approve = () =>
    run(() =>
      withStepUp(() =>
        approveDoaVersion({ ...path, body: { remark: remark.trim() || undefined }, throwOnError: true }),
      ),
    )
  const sendBack = () => run(() => sendBackDoaVersion({ ...path, body: { remark: remark.trim() }, throwOnError: true }))
  const changed = rules !== undefined || effective !== undefined

  return (
    <section aria-labelledby="doa-version-title" className="rounded-md border p-4">
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <h2 id="doa-version-title" className="text-lg font-semibold">
          {intl.formatMessage(dmv.version, { number: v.number })}
        </h2>
        <Badge tone={tones[v.state]}>{intl.formatMessage(stateLabels[v.state])}</Badge>
      </div>
      {problems.length > 0 ? (
        <div role="alert" className="mb-4 rounded-md border border-destructive p-3 text-sm text-destructive">
          <p className="font-medium">{intl.formatMessage(dm.invalid)}</p>
          <ul className="list-disc ps-5">
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </div>
      ) : null}
      {v.reviewRemark ? (
        <p className="mb-4 text-sm">{intl.formatMessage(dmv.reviewRemark, { remark: v.reviewRemark })}</p>
      ) : null}
      <div className="mb-4 max-w-xs">
        <Field
          label={intl.formatMessage(dm.effectiveFrom)}
          hint={draft ? intl.formatMessage(dm.effectiveHint) : undefined}
        >
          {(p) => (
            <Input
              {...p}
              type="date"
              value={effectiveFrom}
              readOnly={!draft}
              onChange={(e) => {
                setEffective(e.target.value)
              }}
            />
          )}
        </Field>
      </div>
      <h3 className="text-base font-semibold">{intl.formatMessage(dm.rules)}</h3>
      <p className="mb-3 text-sm text-muted-foreground">{intl.formatMessage(dm.rulesLead)}</p>
      <DoaRulesEditor rules={current} positions={v.positions} onChange={draft ? setRules : undefined} />
      {draft ? (
        <div className="mt-4 flex flex-wrap gap-2">
          <Button variant="outline" disabled={busy || !changed} onClick={() => void save()}>
            {intl.formatMessage(dm.save)}
          </Button>
          <Button disabled={busy || changed} onClick={() => void submit()}>
            {intl.formatMessage(dm.submit)}
          </Button>
        </div>
      ) : null}
      {v.state === 'IN_REVIEW' ? (
        <div className="mt-4 flex flex-col gap-3">
          <Field label={intl.formatMessage(dm.remark)} hint={intl.formatMessage(dm.remarkHint)}>
            {(p) => (
              <Textarea
                {...p}
                rows={2}
                maxLength={4000}
                value={remark}
                onChange={(e) => {
                  setRemark(e.target.value)
                }}
              />
            )}
          </Field>
          <div className="flex flex-wrap gap-2">
            <Button disabled={busy} onClick={() => void approve()}>
              {intl.formatMessage(dm.approve)}
            </Button>
            <Button variant="outline" disabled={busy || !remark.trim()} onClick={() => void sendBack()}>
              {intl.formatMessage(dm.sendBack)}
            </Button>
          </div>
        </div>
      ) : null}
    </section>
  )
}

function Simulator({ versions }: { versions: DoaVersionResponse[] }) {
  const intl = useIntl()
  const names = useDoaNames({})
  const [initiator, setInitiator] = useState<Picked>()
  const [memoType, setMemoType] = useState('')
  const [rank, setRank] = useState('0')
  const [version, setVersion] = useState('')
  const [result, setResult] = useState<DoaSimulationResponse>()
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const simulate = async () => {
    if (!initiator) {
      setError(intl.formatMessage(dm.chooseInitiator))
      return
    }
    setBusy(true)
    setError(undefined)
    try {
      const answer = await simulateDoa({
        body: {
          subjectType: 'memo',
          initiatorId: initiator.id,
          version: version ? Number(version) : undefined,
          attributes: { ...(memoType ? { memoTypeId: memoType } : {}), classificationRank: Number(rank) || 0 },
        },
        throwOnError: true,
      })
      setResult(answer.data)
    } catch {
      setError(intl.formatMessage(dm.failed))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="doa-simulator-title" className="rounded-md border p-4">
      <h2 id="doa-simulator-title" className="text-lg font-semibold">
        {intl.formatMessage(dm.simulator)}
      </h2>
      <p className="mb-3 text-sm text-muted-foreground">{intl.formatMessage(dm.simulatorLead)}</p>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={intl.formatMessage(dm.initiator)} required error={error}>
          {(p) => <PeoplePicker id={p.id} kind="user" value={initiator} onChange={setInitiator} />}
        </Field>
        <Field label={intl.formatMessage(dm.memoType)}>
          {(p) => (
            <Select
              {...p}
              value={memoType}
              onChange={(e) => {
                setMemoType(e.target.value)
              }}
            >
              <option value="">{intl.formatMessage(dm.anyMemoType)}</option>
              {names.types.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label={intl.formatMessage(dm.classificationRank)}>
          {(p) => (
            <Input
              {...p}
              type="number"
              min={0}
              value={rank}
              onChange={(e) => {
                setRank(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(dm.version)}>
          {(p) => (
            <Select
              {...p}
              value={version}
              onChange={(e) => {
                setVersion(e.target.value)
              }}
            >
              <option value="">{intl.formatMessage(dm.inForce)}</option>
              {versions.map((v) => (
                <option key={v.number} value={v.number}>
                  {intl.formatMessage(dmv.version, { number: v.number })}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </div>
      <div className="mt-3">
        <Button variant="outline" disabled={busy} onClick={() => void simulate()}>
          {intl.formatMessage(dm.simulate)}
        </Button>
      </div>
      {result ? (
        <div role="status" className="mt-4 flex flex-col gap-2 text-sm">
          {result.reason === 'NO_DOA' ? <p>{intl.formatMessage(dm.noDoa)}</p> : null}
          {result.reason === 'NO_RULE' ? <p>{intl.formatMessage(dm.noRule)}</p> : null}
          {result.version != null && result.ruleName ? (
            <p className="font-medium">
              {intl.formatMessage(dmv.result, { version: result.version, rule: result.ruleName })}
            </p>
          ) : null}
          <ol className="list-decimal ps-5">
            {result.groups.map((g, i) => (
              <li key={i}>
                {intl.formatMessage(dmv.needs, {
                  needed: g.needed,
                  names: g.people.length > 0 ? g.people.map((p) => p.name).join(', ') : intl.formatMessage(dm.nobody),
                })}
              </li>
            ))}
          </ol>
        </div>
      ) : null}
    </section>
  )
}
