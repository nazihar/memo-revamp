import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getMemoTypeVersionOptions,
  getMemoTypeVersionQueryKey,
  listAllMemoTypesOptions,
  listAllMemoTypesQueryKey,
  listClassificationLevelsOptions,
  listMemoTypeDefinitionsOptions,
  listMemoTypeVersionsOptions,
  listMemoTypeVersionsQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import {
  approveMemoTypeVersion,
  createMemoTypeDraft,
  replaceMemoTypeDraft,
  sendBackMemoTypeVersion,
  setMemoTypeActive,
  submitMemoTypeVersion,
} from '@/api/generated/sdk.gen'
import type { MemoTypeVersionResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge, type Tone } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Select, Textarea } from '@/components/ui/form-controls'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/misc'
import { useToast } from '@/components/ui/toast-context'
import { PERMISSION, holds, useMyAccess } from '@/features/access/access'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { stateLabels } from '@/features/workflows/workflow-messages'
import { FieldsTab } from './FieldsTab'
import { tm, tmv } from './messages'
import { NumberingTab } from './NumberingTab'
import { SettingsTab } from './SettingsTab'
import { changesBetween, settingsOf, type TypeSettings } from './settings'

const tones: Record<MemoTypeVersionResponse['state'], Tone> = {
  DRAFT: 'neutral',
  IN_REVIEW: 'warn',
  PUBLISHED: 'ok',
  RETIRED: 'neutral',
}

/**
 * `/admin/memo-types/$typeId` (design E01 §10 flow A2, ADR-0043): one memo type and its versions. A draft is changed and
 * submitted by a holder of `memo.types.manage`; a version in review is published (after a step-up) or sent back by a holder
 * of `memo.types.approve` who did not submit it. The version shown is in the address.
 */
export function MemoTypePage({ typeId, version }: { typeId: string; version?: number }) {
  const intl = useIntl()
  const navigate = useNavigate()
  const toast = useToast()
  const queryClient = useQueryClient()
  const access = useMyAccess()
  const types = useQuery(listAllMemoTypesOptions())
  const versions = useQuery(listMemoTypeVersionsOptions({ path: { typeId } }))
  const [busy, setBusy] = useState(false)
  const type = types.data?.find((t) => t.id === typeId)
  const list = versions.data ?? []
  const selected = version ?? list[0]?.number
  const manage = holds(access.data, PERMISSION.memoTypesManage)

  if (types.isPending || versions.isPending) return <LoadingState />
  if (types.isError || versions.isError || !type) {
    return <ErrorState onRetry={() => void Promise.all([types.refetch(), versions.refetch()])} />
  }

  const open = (number: number) =>
    navigate({ to: '/admin/memo-types/$typeId', params: { typeId }, search: { version: number } })

  const refusal = (problem: unknown) => {
    const reason = isProblem(problem) ? problem.reason : undefined
    toast.show(intl.formatMessage(reason && reason in tm ? tm[reason as keyof typeof tm] : tm.failed), 'error')
  }

  const newDraft = async () => {
    setBusy(true)
    try {
      const created = await createMemoTypeDraft({ path: { typeId }, throwOnError: true })
      await queryClient.invalidateQueries({ queryKey: listMemoTypeVersionsQueryKey({ path: { typeId } }) })
      await queryClient.invalidateQueries({ queryKey: listAllMemoTypesQueryKey() })
      await open(created.data.number)
    } catch (problem) {
      refusal(problem)
    } finally {
      setBusy(false)
    }
  }

  const toggle = async () => {
    setBusy(true)
    try {
      await setMemoTypeActive({
        path: { id: typeId },
        headers: { 'If-Match': `"${String(type.version)}"` },
        body: { active: !type.active },
        throwOnError: true,
      })
      await queryClient.invalidateQueries({ queryKey: listAllMemoTypesQueryKey() })
    } catch (problem) {
      refusal(problem)
    } finally {
      setBusy(false)
    }
  }

  const hasOpen = list.some((v) => v.state === 'DRAFT' || v.state === 'IN_REVIEW')
  return (
    <section aria-labelledby="memo-type-title" className="flex flex-col gap-4">
      <PageHeader
        title={
          <span id="memo-type-title">
            {type.name} <code className="text-base text-muted-foreground">{type.code}</code>
          </span>
        }
        description={
          <span className="flex flex-wrap items-center gap-2">
            <Badge tone={type.active ? 'ok' : 'neutral'}>
              {intl.formatMessage(type.active ? tm.active : tm.inactive)}
            </Badge>
            <Link to="/admin/memo-types" className="text-sm underline-offset-4 hover:underline">
              {intl.formatMessage(tm.back)}
            </Link>
          </span>
        }
        actions={
          manage ? (
            <span className="flex flex-wrap gap-2">
              {!hasOpen ? (
                <Button variant="outline" disabled={busy} onClick={() => void newDraft()}>
                  {intl.formatMessage(tm.newDraft)}
                </Button>
              ) : null}
              <Button variant="outline" disabled={busy} onClick={() => void toggle()}>
                {intl.formatMessage(type.active ? tm.switchOff : tm.switchOn)}
              </Button>
            </span>
          ) : undefined
        }
      />
      <div className="max-w-xs">
        <Field label={intl.formatMessage(tm.versionLabel)}>
          {(p) => (
            <Select
              {...p}
              value={String(selected ?? '')}
              onChange={(e) => {
                void open(Number(e.target.value))
              }}
            >
              {list.map((v) => (
                <option key={v.number} value={v.number}>
                  {intl.formatMessage(tmv.version, {
                    number: v.number,
                    state: intl.formatMessage(stateLabels[v.state]),
                  })}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </div>
      {selected != null ? (
        <VersionEditor
          key={selected}
          typeId={typeId}
          number={selected}
          published={list.find((v) => v.state === 'PUBLISHED')}
          manage={manage}
          approve={holds(access.data, PERMISSION.memoTypesApprove)}
          onRefusal={refusal}
        />
      ) : null}
    </section>
  )
}

function VersionEditor({
  typeId,
  number,
  published,
  manage,
  approve,
  onRefusal,
}: {
  typeId: string
  number: number
  published?: MemoTypeVersionResponse
  manage: boolean
  approve: boolean
  onRefusal: (problem: unknown) => void
}) {
  const intl = useIntl()
  const toast = useToast()
  const withStepUp = useWithStepUp()
  const queryClient = useQueryClient()
  const path = { path: { typeId, number: String(number) } }
  const version = useQuery(getMemoTypeVersionOptions(path))
  const levels = useQuery(listClassificationLevelsOptions())
  const definitions = useQuery(listMemoTypeDefinitionsOptions())
  const [edited, setEdited] = useState<TypeSettings>()
  const [remark, setRemark] = useState('')
  const [problems, setProblems] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)

  if (version.isPending) return <LoadingState />
  if (version.isError) return <ErrorState onRetry={() => void version.refetch()} />
  const v = version.data
  const draft = v.state === 'DRAFT' && manage
  const reviewing = v.state === 'IN_REVIEW' && approve
  const settings = edited ?? settingsOf(v)
  const change = draft
    ? (next: Partial<TypeSettings>) => {
        setEdited({ ...settings, ...next })
      }
    : undefined

  const run = async (call: () => Promise<unknown>) => {
    setBusy(true)
    setProblems({})
    try {
      await call()
      toast.show(intl.formatMessage(tm.done), 'success')
      setEdited(undefined)
      setRemark('')
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: getMemoTypeVersionQueryKey(path) }),
        queryClient.invalidateQueries({ queryKey: listMemoTypeVersionsQueryKey({ path: { typeId } }) }),
        queryClient.invalidateQueries({ queryKey: listAllMemoTypesQueryKey() }),
      ])
    } catch (problem) {
      const p = isProblem(problem) ? problem : undefined
      if (p?.errors?.length) {
        setProblems(Object.fromEntries(p.errors.map((e) => [e.field, e.message])))
      } else if (p?.status === 412) {
        toast.show(intl.formatMessage(tm.conflict), 'error')
        setEdited(undefined)
        await version.refetch()
      } else {
        onRefusal(problem)
      }
    } finally {
      setBusy(false)
    }
  }

  const ifMatch = { 'If-Match': `"${String(v.revision)}"` }
  const save = () => run(() => replaceMemoTypeDraft({ ...path, headers: ifMatch, body: settings, throwOnError: true }))
  const submit = () => run(() => submitMemoTypeVersion({ ...path, headers: ifMatch, throwOnError: true }))
  const approveVersion = () =>
    run(() =>
      withStepUp(() =>
        approveMemoTypeVersion({ ...path, body: { remark: remark.trim() || undefined }, throwOnError: true }),
      ),
    )
  const sendBack = () =>
    run(() => sendBackMemoTypeVersion({ ...path, body: { remark: remark.trim() }, throwOnError: true }))
  const problemList = Object.entries(problems)
  const before = published && published.number !== v.number ? settingsOf(published) : undefined

  return (
    <section aria-labelledby="version-title" className="flex flex-col gap-4 rounded-md border p-4">
      <div className="flex flex-wrap items-center gap-3">
        <h2 id="version-title" className="text-lg font-semibold">
          {intl.formatMessage(tmv.version, { number: v.number, state: intl.formatMessage(stateLabels[v.state]) })}
        </h2>
        <Badge tone={tones[v.state]}>{intl.formatMessage(stateLabels[v.state])}</Badge>
        {v.submittedBy && v.state === 'IN_REVIEW' ? (
          <span className="text-sm text-muted-foreground">
            {intl.formatMessage(tmv.submittedBy, { name: v.submittedBy.name })}
          </span>
        ) : null}
      </div>
      {v.reviewRemark ? (
        <p className="text-sm">{intl.formatMessage(tmv.reviewRemark, { remark: v.reviewRemark })}</p>
      ) : null}
      {!draft && v.state !== 'IN_REVIEW' ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(tm.readOnly)}</p>
      ) : null}
      {problemList.length > 0 ? (
        <div role="alert" className="rounded-md border border-destructive p-3 text-sm text-destructive">
          <p className="font-medium">{intl.formatMessage(tm.invalid)}</p>
          <ul className="list-disc ps-5">
            {problemList.map(([field, message]) => (
              <li key={field}>{message}</li>
            ))}
          </ul>
        </div>
      ) : null}

      <Tabs defaultValue={reviewing ? 'changes' : 'settings'}>
        <TabsList aria-label={intl.formatMessage(tm.tabs)}>
          <TabsTrigger value="settings">{intl.formatMessage(tm.tabSettings)}</TabsTrigger>
          <TabsTrigger value="form">{intl.formatMessage(tm.tabForm)}</TabsTrigger>
          <TabsTrigger value="numbering">{intl.formatMessage(tm.tabNumbering)}</TabsTrigger>
          <TabsTrigger value="changes">{intl.formatMessage(tm.tabChanges)}</TabsTrigger>
        </TabsList>
        <TabsContent value="settings" className="pt-4">
          <SettingsTab
            settings={settings}
            onChange={change}
            levels={levels.data ?? []}
            definitions={definitions.data ?? []}
            names={v.allowedNames}
            errors={problems}
          />
        </TabsContent>
        <TabsContent value="form" className="pt-4">
          <FieldsTab
            fields={settings.fields}
            onChange={
              change
                ? (fields) => {
                    change({ fields })
                  }
                : undefined
            }
            errors={problems}
          />
        </TabsContent>
        <TabsContent value="numbering" className="pt-4">
          <NumberingTab
            numbering={settings.numbering}
            code={settings.code}
            onChange={
              change
                ? (numbering) => {
                    change({ numbering })
                  }
                : undefined
            }
            errors={problems}
          />
        </TabsContent>
        <TabsContent value="changes" className="pt-4">
          <ChangesTab before={before} after={settings} />
        </TabsContent>
      </Tabs>

      {draft ? (
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" disabled={busy || !edited} onClick={() => void save()}>
            {intl.formatMessage(tm.save)}
          </Button>
          <Button disabled={busy || Boolean(edited)} onClick={() => void submit()}>
            {intl.formatMessage(tm.submit)}
          </Button>
          {edited ? <span className="text-sm text-muted-foreground">{intl.formatMessage(tm.unsaved)}</span> : null}
        </div>
      ) : null}
      {reviewing ? (
        <div className="flex max-w-2xl flex-col gap-3">
          <Field label={intl.formatMessage(tm.remark)} hint={intl.formatMessage(tm.remarkHint)}>
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
            <Button disabled={busy} onClick={() => void approveVersion()}>
              {intl.formatMessage(tm.approve)}
            </Button>
            <Button variant="outline" disabled={busy || !remark.trim()} onClick={() => void sendBack()}>
              {intl.formatMessage(tm.sendBack)}
            </Button>
          </div>
        </div>
      ) : null}
    </section>
  )
}

const settingLabels = {
  name: tm.name,
  code: tm.code,
  defaultClassificationId: tm.classification,
  definitionKey: tm.workflow,
  routePolicy: tm.policy,
  allowedApprovers: tm.policy,
  doaCheck: tm.doaCheck,
  stepUpOnApprove: tm.stepUp,
  raiseScope: tm.scope,
  numbering: tm.tabNumbering,
  retention: tm.retention,
  attachmentRules: tm.attachments,
  autoCloseDays: tm.autoClose,
}

const kindLabels = { added: tm.added, removed: tm.removedChange, changed: tm.changed }
const kindTones: Record<'added' | 'removed' | 'changed', Tone> = { added: 'ok', removed: 'danger', changed: 'warn' }

/** What this version changes compared with the published one (design E01 §10 flow A2). */
function ChangesTab({ before, after }: { before?: TypeSettings; after: TypeSettings }) {
  const intl = useIntl()
  if (!before) return <p className="text-sm">{intl.formatMessage(tm.changesFirst)}</p>
  const changes = changesBetween(before, after)
  if (changes.length === 0) return <p className="text-sm">{intl.formatMessage(tm.changesNone)}</p>
  return (
    <div className="flex flex-col gap-2">
      <p className="text-sm text-muted-foreground">{intl.formatMessage(tm.changesLead)}</p>
      <ul className="flex flex-col gap-2">
        {changes.map((c) => (
          <li key={`${c.kind}-${c.what}`} className="flex items-center gap-2 text-sm">
            <Badge tone={kindTones[c.kind]}>{intl.formatMessage(kindLabels[c.kind])}</Badge>
            {c.what.startsWith('field:')
              ? intl.formatMessage(tmv.field, { label: c.what.slice('field:'.length) })
              : intl.formatMessage(settingLabels[c.what as keyof typeof settingLabels])}
          </li>
        ))}
      </ul>
    </div>
  )
}
