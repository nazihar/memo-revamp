import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { Download, Redo2, Undo2, Upload } from 'lucide-react'
import { useRef, useState } from 'react'
import { useIntl, type MessageDescriptor } from 'react-intl'
import {
  getWorkflowDefinitionOptions,
  getWorkflowDefinitionQueryKey,
  getWorkflowDefinitionVersionOptions,
  getWorkflowDefinitionVersionQueryKey,
  listWorkflowDefinitionsQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import {
  approveWorkflowDefinition,
  newWorkflowDefinitionDraft,
  replaceWorkflowDefinitionDraft,
  retireWorkflowDefinition,
  sendBackWorkflowDefinition,
  submitWorkflowDefinition,
  validateWorkflowDefinition,
} from '@/api/generated/sdk.gen'
import type { FieldProblem, VersionResponse, VersionSummary } from '@/api/generated/types.gen'
import { isProblem, PROBLEM, type Problem } from '@/api/http'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/misc'
import { bm, bmv } from './builder/builder-messages'
import { DefinitionDiagram } from './builder/DefinitionDiagram'
import { StepsView } from './builder/StepsView'
import { useBuilder, useLiveValidation } from './builder/useBuilder'
import { UnsavedBanner, UnsavedChangesGuard } from '@/components/page/FormPage'
import { useToast } from '@/components/ui/toast-context'
import { holds, PERMISSION, useMyAccess } from '@/features/access/access'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { DefinitionMigration } from './DefinitionMigration'
import { stateLabels, wm, wmv } from './workflow-messages'

type Decision = 'approve' | 'sendBack' | 'retire'
type View = 'diagram' | 'steps' | 'json'

/** Refusals that say what to do rather than 'reload'. */
const REFUSALS: Partial<Record<string, MessageDescriptor>> = {
  SAME_PERSON: wm.samePerson,
  IN_USE: wm.inUse,
  DRAFT_EXISTS: wm.draftExists,
}

/**
 * `/admin/workflows/$key` (design E03 §12): the versions of one definition and, for the chosen one, its body, what
 * validation finds in it and the lifecycle actions the viewer may take. E06 replaces the JSON editor with a visual one
 * on the same API.
 */
export function WorkflowDefinitionPage({ definitionKey, version }: { definitionKey: string; version?: number }) {
  const intl = useIntl()
  const navigate = useNavigate()
  const definition = useQuery(getWorkflowDefinitionOptions({ path: { key: definitionKey } }))
  const access = useMyAccess()
  const manage = holds(access.data, PERMISSION.workflowDefinitionsManage)
  const approve = holds(access.data, PERMISSION.workflowDefinitionsApprove)

  if (definition.isPending) return <LoadingState />
  if (definition.isError) {
    return (definition.error as Problem | undefined)?.status === 404 ? (
      <EmptyState title={intl.formatMessage(wm.notFound)} />
    ) : (
      <ErrorState onRetry={() => void definition.refetch()} />
    )
  }
  const { definition: summary, versions } = definition.data
  const shown = version ?? summary.openVersion?.number ?? summary.publishedVersion ?? versions[0]?.number
  const show = (number: number) => void navigate({ to: '.', search: { version: number } })

  const columns: DataTableColumn<VersionSummary>[] = [
    {
      id: 'number',
      header: intl.formatMessage(wm.colVersion),
      cell: ({ row }) => intl.formatMessage(wmv.version, { number: row.original.number }),
    },
    {
      id: 'state',
      header: intl.formatMessage(wm.colState),
      cell: ({ row }) => (
        <Badge tone={row.original.state === 'PUBLISHED' ? 'ok' : 'neutral'}>
          {intl.formatMessage(stateLabels[row.original.state])}
        </Badge>
      ),
    },
    {
      id: 'submitted',
      header: intl.formatMessage(wm.colSubmitted),
      cell: ({ row }) => row.original.submittedBy?.name ?? '',
    },
    {
      id: 'reviewed',
      header: intl.formatMessage(wm.colReviewed),
      cell: ({ row }) => row.original.reviewedBy?.name ?? '',
    },
    {
      id: 'show',
      header: () => <span className="sr-only">{intl.formatMessage(wm.show)}</span>,
      cell: ({ row }) => (
        <Button
          variant={row.original.number === shown ? 'default' : 'outline'}
          aria-pressed={row.original.number === shown}
          onClick={() => {
            show(row.original.number)
          }}
        >
          {intl.formatMessage(wm.show)}
        </Button>
      ),
    },
  ]

  return (
    <section aria-labelledby="workflow-title" className="flex flex-col gap-6">
      <PageHeader
        title={<span id="workflow-title">{summary.name}</span>}
        description={summary.description ?? summary.key}
        actions={
          manage && !summary.builtIn && !summary.openVersion ? (
            <NewDraftButton definitionKey={definitionKey} onCreated={show} />
          ) : null
        }
      />
      <DataTable
        caption={intl.formatMessage(wm.versionsCaption)}
        columns={columns}
        rows={versions}
        getRowId={(row) => String(row.number)}
        state="ready"
      />
      {shown ? (
        <VersionPanel
          key={shown}
          definitionKey={definitionKey}
          number={shown}
          builtIn={summary.builtIn}
          manage={manage}
          approve={approve}
        />
      ) : null}
      {approve && summary.publishedVersion && !summary.fragment ? (
        <DefinitionMigration definitionKey={definitionKey} />
      ) : null}
    </section>
  )
}

function NewDraftButton({ definitionKey, onCreated }: { definitionKey: string; onCreated: (number: number) => void }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  return (
    <Button
      disabled={busy}
      onClick={() => {
        setBusy(true)
        void newWorkflowDefinitionDraft({ path: { key: definitionKey }, body: {}, throwOnError: true })
          .then(async (created) => {
            await invalidate(queryClient, definitionKey)
            onCreated(created.data.version.number)
          })
          .catch((problem: unknown) => {
            const reason = isProblem(problem) ? problem.reason : undefined
            toast.show(intl.formatMessage(reason === 'DRAFT_EXISTS' ? wm.draftExists : wm.failed), 'error')
          })
          .finally(() => {
            setBusy(false)
          })
      }}
    >
      {intl.formatMessage(wm.newDraft)}
    </Button>
  )
}

async function invalidate(queryClient: ReturnType<typeof useQueryClient>, key: string) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: getWorkflowDefinitionQueryKey({ path: { key } }) }),
    queryClient.invalidateQueries({ queryKey: listWorkflowDefinitionsQueryKey() }),
  ])
}

function VersionPanel({
  definitionKey,
  number,
  builtIn,
  manage,
  approve,
}: {
  definitionKey: string
  number: number
  builtIn: boolean
  manage: boolean
  approve: boolean
}) {
  const intl = useIntl()
  const path = { key: definitionKey, number: String(number) }
  const version = useQuery(getWorkflowDefinitionVersionOptions({ path }))
  if (version.isPending) return <LoadingState rows={6} />
  if (version.isError) return <ErrorState onRetry={() => void version.refetch()} />
  return (
    <section aria-labelledby="version-title" className="rounded-md border p-4">
      <h2 id="version-title" className="mb-3 text-base font-semibold">
        {intl.formatMessage(wmv.versionState, {
          number,
          state: intl.formatMessage(stateLabels[version.data.version.state]),
        })}
      </h2>
      <VersionEditor
        key={version.data.revision}
        definitionKey={definitionKey}
        version={version.data}
        builtIn={builtIn}
        manage={manage}
        approve={approve}
      />
    </section>
  )
}

function VersionEditor({
  definitionKey,
  version,
  builtIn,
  manage,
  approve,
}: {
  definitionKey: string
  version: VersionResponse
  builtIn: boolean
  manage: boolean
  approve: boolean
}) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const file = useRef<HTMLInputElement>(null)
  const original = JSON.stringify(version.body, null, 2)
  const builder = useBuilder(original)
  const { text, setText } = builder
  const drawable = builder.body
  const [problems, setProblems] = useState<FieldProblem[]>(version.problems)
  const [jsonError, setJsonError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [decision, setDecision] = useState<Decision>()
  const [conflict, setConflict] = useState(false)
  const [view, setView] = useState<View>('diagram')
  const state = version.version.state
  const editable = manage && state === 'DRAFT'
  const dirty = text !== original
  const number = version.version.number
  const path = { key: definitionKey, number: String(number) }
  const ifMatch = { 'If-Match': `"${version.revision}"` }
  const checking = useLiveValidation({ path, text, original, enabled: editable, onProblems: setProblems })
  const onCommand = editable ? builder.edit : undefined

  const parsed = (): Record<string, unknown> | undefined => {
    try {
      const value = JSON.parse(text) as unknown
      if (value && typeof value === 'object' && !Array.isArray(value)) return value as Record<string, unknown>
    } catch {
      // reported below
    }
    setJsonError(intl.formatMessage(wm.bodyInvalidJson))
    return undefined
  }

  const refresh = async (next?: VersionResponse) => {
    if (next) queryClient.setQueryData(getWorkflowDefinitionVersionQueryKey({ path }), next)
    await invalidate(queryClient, definitionKey)
    if (!next) await queryClient.invalidateQueries({ queryKey: getWorkflowDefinitionVersionQueryKey({ path }) })
  }

  const fail = async (problem: unknown, saving = false) => {
    const p = isProblem(problem) ? problem : undefined
    if (saving && p?.status === 412) {
      // Someone saved this draft meanwhile: keep the local work and let the author choose (design E06 §3).
      setConflict(true)
      return
    }
    const refusal = p?.reason ? REFUSALS[p.reason] : undefined
    if (p?.reason === 'DEFINITION_INVALID') {
      setProblems((p.errors ?? []).map((e) => ({ field: e.field, code: e.code ?? '', message: e.message })))
      toast.show(intl.formatMessage(wm.invalid), 'error')
    } else if (refusal) {
      toast.show(intl.formatMessage(refusal), 'error')
    } else if (p?.status === 412 || p?.type === PROBLEM.conflict) {
      // Changed meanwhile (a stale If-Match, or a state another reviewer moved it out of): show it as it is now.
      toast.show(intl.formatMessage(wm.conflict), 'error')
      await refresh()
    } else {
      toast.show(p?.detail ?? intl.formatMessage(wm.failed), 'error')
    }
  }

  const run = async (
    work: () => Promise<VersionResponse | undefined>,
    message: MessageDescriptor = wm.done,
    saving = false,
  ) => {
    setBusy(true)
    try {
      const next = await work()
      await refresh(next)
      toast.show(intl.formatMessage(message), 'success')
    } catch (problem) {
      await fail(problem, saving)
    } finally {
      setBusy(false)
    }
  }

  const save = () => {
    const body = parsed()
    if (!body) return
    void run(
      async () =>
        (await replaceWorkflowDefinitionDraft({ path, headers: ifMatch, body: { body }, throwOnError: true })).data,
      wm.saved,
      true,
    )
  }

  const check = () => {
    const body = parsed()
    if (!body) return
    setBusy(true)
    void validateWorkflowDefinition({ path, body: { body }, throwOnError: true })
      .then((result) => {
        setProblems(result.data.problems)
        if (result.data.valid) toast.show(intl.formatMessage(wm.noProblems), 'success')
      })
      .catch(fail)
      .finally(() => {
        setBusy(false)
      })
  }

  const importFile = async (picked: File | undefined) => {
    if (!picked) return
    setText(await picked.text())
    setJsonError(undefined)
  }

  return (
    <div className="flex flex-col gap-4">
      {version.version.reviewRemark ? (
        <div className="rounded-md bg-muted p-3 text-sm">
          <p className="font-medium">{intl.formatMessage(wm.reviewRemark)}</p>
          <p className="whitespace-pre-wrap">{version.version.reviewRemark}</p>
        </div>
      ) : null}
      {editable ? (
        <div className="flex flex-wrap items-center gap-2">
          <div role="toolbar" aria-label={intl.formatMessage(bm.editTools)} className="flex gap-2">
            <Button variant="outline" disabled={!builder.canUndo} onClick={builder.undo}>
              <Undo2 aria-hidden="true" />
              {intl.formatMessage(bm.undo)}
            </Button>
            <Button variant="outline" disabled={!builder.canRedo} onClick={builder.redo}>
              <Redo2 aria-hidden="true" />
              {intl.formatMessage(bm.redo)}
            </Button>
          </div>
          <UnsavedBanner visible={dirty} />
          <p role="status" className="text-sm text-muted-foreground">
            {checking
              ? intl.formatMessage(bm.checking)
              : dirty
                ? intl.formatMessage(bmv.checked, { count: problems.length })
                : null}
          </p>
        </div>
      ) : null}
      {conflict ? (
        <div role="alert" className="flex flex-col gap-2 rounded-md border border-destructive p-3 text-sm">
          <p>{intl.formatMessage(bm.conflict)}</p>
          <div className="flex flex-wrap gap-2">
            <Button
              variant="outline"
              onClick={() => {
                setConflict(false)
                void refresh()
              }}
            >
              {intl.formatMessage(bm.reload)}
            </Button>
            <Button
              variant="outline"
              onClick={() => {
                void navigator.clipboard.writeText(text).then(() => {
                  toast.show(intl.formatMessage(bm.copied), 'success')
                })
              }}
            >
              {intl.formatMessage(bm.copy)}
            </Button>
          </div>
        </div>
      ) : null}
      <UnsavedChangesGuard when={editable && dirty} />
      <Tabs
        value={view}
        onValueChange={(v) => {
          setView(v as View)
        }}
      >
        <TabsList aria-label={intl.formatMessage(bm.views)}>
          <TabsTrigger value="diagram">{intl.formatMessage(bm.viewDiagram)}</TabsTrigger>
          <TabsTrigger value="steps">{intl.formatMessage(bm.viewSteps)}</TabsTrigger>
          <TabsTrigger value="json">{intl.formatMessage(bm.viewJson)}</TabsTrigger>
        </TabsList>
        <TabsContent value="diagram" className="pt-3">
          {drawable ? (
            <DefinitionDiagram body={drawable} labels={version.labels} problems={problems} onCommand={onCommand} />
          ) : (
            <p className="text-sm">{intl.formatMessage(bm.notReadable)}</p>
          )}
        </TabsContent>
        <TabsContent value="steps" className="pt-3">
          {drawable ? (
            <StepsView body={drawable} labels={version.labels} problems={problems} onCommand={onCommand} />
          ) : (
            <p className="text-sm">{intl.formatMessage(bm.notReadable)}</p>
          )}
        </TabsContent>
        <TabsContent value="json" className="pt-3">
          <Field label={intl.formatMessage(wm.body)} hint={intl.formatMessage(wm.bodyHint)} error={jsonError}>
            {(p) => (
              <Textarea
                {...p}
                dir="ltr"
                rows={18}
                spellCheck={false}
                readOnly={!editable}
                className="font-mono text-xs"
                value={text}
                onChange={(e) => {
                  setText(e.target.value)
                  setJsonError(undefined)
                }}
              />
            )}
          </Field>
        </TabsContent>
      </Tabs>
      <section aria-labelledby="problems-title">
        <h3 id="problems-title" className="mb-1 text-sm font-semibold">
          {intl.formatMessage(wm.problems)}
        </h3>
        {problems.length === 0 ? (
          <p className="text-sm text-muted-foreground">{intl.formatMessage(wm.noProblems)}</p>
        ) : (
          <ul className="flex flex-col gap-1 text-sm">
            {problems.map((problem) => (
              <li key={`${problem.field}:${problem.code}:${problem.message}`} className="flex flex-wrap gap-2">
                <code dir="ltr" className="text-xs">
                  {pointer(problem.field)}
                </code>
                <span>{problem.message}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
      <div role="toolbar" aria-label={intl.formatMessage(wm.versions)} className="flex flex-wrap gap-2">
        {editable ? (
          <>
            <Button disabled={busy || !dirty} onClick={save}>
              {intl.formatMessage(wm.save)}
            </Button>
            <Button variant="outline" disabled={busy} onClick={check}>
              {intl.formatMessage(wm.validate)}
            </Button>
            <Button
              variant="outline"
              disabled={busy || dirty}
              onClick={() =>
                void run(
                  async () => (await submitWorkflowDefinition({ path, headers: ifMatch, throwOnError: true })).data,
                )
              }
            >
              {intl.formatMessage(wm.submit)}
            </Button>
            <Button variant="outline" disabled={busy} onClick={() => file.current?.click()}>
              <Upload aria-hidden="true" />
              {intl.formatMessage(wm.import)}
            </Button>
            <input
              ref={file}
              type="file"
              accept="application/json,.json"
              className="sr-only"
              tabIndex={-1}
              aria-hidden="true"
              onChange={(e) => void importFile(e.target.files?.[0])}
            />
          </>
        ) : null}
        {approve && state === 'IN_REVIEW' ? (
          <>
            <Button
              disabled={busy}
              onClick={() => {
                setDecision('approve')
              }}
            >
              {intl.formatMessage(wm.approve)}
            </Button>
            <Button
              variant="outline"
              disabled={busy}
              onClick={() => {
                setDecision('sendBack')
              }}
            >
              {intl.formatMessage(wm.sendBack)}
            </Button>
          </>
        ) : null}
        {approve && state === 'PUBLISHED' && !builtIn ? (
          <Button
            variant="outline"
            disabled={busy}
            onClick={() => {
              setDecision('retire')
            }}
          >
            {intl.formatMessage(wm.retire)}
          </Button>
        ) : null}
        <Button variant="outline" asChild>
          <a href={`/api/v1/admin/workflow-definitions/${definitionKey}/versions/${number}/export`} download>
            <Download aria-hidden="true" />
            {intl.formatMessage(wm.export)}
          </a>
        </Button>
      </div>
      {decision ? (
        <DecisionDialog
          kind={decision}
          onClose={() => {
            setDecision(undefined)
          }}
          definitionKey={definitionKey}
          number={number}
          onDone={(next) => {
            setDecision(undefined)
            void refresh(next).then(() => {
              toast.show(intl.formatMessage(wm.done), 'success')
            })
          }}
          onProblem={(problem) => {
            setDecision(undefined)
            void fail(problem)
          }}
        />
      ) : null}
    </div>
  )
}

/** The reviewer's decisions: approving and retiring go through step-up when the server asks; sending back needs a remark. */
function DecisionDialog({
  kind,
  definitionKey,
  number,
  onClose,
  onDone,
  onProblem,
}: {
  kind: Decision
  definitionKey: string
  number: number
  onClose: () => void
  onDone: (next: VersionResponse) => void
  onProblem: (problem: Problem | undefined) => void
}) {
  const intl = useIntl()
  const withStepUp = useWithStepUp()
  const [remark, setRemark] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const path = { key: definitionKey, number: String(number) }
  const title = { approve: wm.approveTitle, sendBack: wm.sendBackTitle, retire: wm.retireTitle }[kind]
  const button = { approve: wm.approve, sendBack: wm.sendBack, retire: wm.retire }[kind]

  const confirm = async () => {
    const text = remark.trim() || undefined
    if (kind === 'sendBack' && !text) {
      setError(intl.formatMessage(wm.remarkRequired))
      return
    }
    setBusy(true)
    try {
      const result =
        kind === 'sendBack'
          ? await sendBackWorkflowDefinition({ path, body: { remark: text }, throwOnError: true })
          : kind === 'approve'
            ? await withStepUp(() => approveWorkflowDefinition({ path, body: { remark: text }, throwOnError: true }))
            : await withStepUp(() => retireWorkflowDefinition({ path, throwOnError: true }))
      onDone(result.data)
    } catch (problem) {
      onProblem(isProblem(problem) ? problem : undefined)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(title)}
      {...(kind === 'approve' ? { description: intl.formatMessage(wm.approveLead) } : {})}
      {...(kind === 'retire' ? { description: intl.formatMessage(wm.retireLead) } : {})}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(wm.cancel)}
          </Button>
          <Button
            variant={kind === 'retire' ? 'destructive' : 'default'}
            disabled={busy}
            onClick={() => void confirm()}
          >
            {intl.formatMessage(button)}
          </Button>
        </>
      }
    >
      {kind === 'retire' ? null : (
        <Field
          label={intl.formatMessage(kind === 'sendBack' ? wm.remark : wm.remarkOptional)}
          required={kind === 'sendBack'}
          error={error}
        >
          {(p) => (
            <Textarea
              {...p}
              rows={4}
              maxLength={4000}
              value={remark}
              onChange={(e) => {
                setRemark(e.target.value)
                setError(undefined)
              }}
            />
          )}
        </Field>
      )}
    </Dialog>
  )
}

/** A problem's JSON pointer as shown; the empty pointer is the whole definition. */
function pointer(field: string | undefined): string {
  return field && field.length > 0 ? field : ROOT
}

const ROOT = '/'
