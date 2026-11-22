import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getForumTypeOptions,
  getForumTypeQueryKey,
  getForumTypeVersionOptions,
  getForumTypeVersionQueryKey,
  listClassificationLevelsOptions,
  listForumTypesQueryKey,
  listForumTypeVersionsOptions,
  listForumTypeVersionsQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import {
  activateForumType,
  approveForumTypeVersion,
  createForumTypeDraft,
  deactivateForumType,
  replaceForumTypeDraft,
  sendBackForumTypeVersion,
  submitForumTypeVersion,
} from '@/api/generated/sdk.gen'
import type { ForumTypeVersionResponse } from '@/api/generated/types.gen'
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
import { useTerms } from '@/features/terms/terms'
import { TemplateTab } from '@/features/meetings/agenda/TemplateTab'
import { tm } from '@/features/meetings/agenda/template-messages'
import { stateLabels } from '@/features/workflows/workflow-messages'
import { changesBetween } from './changes'
import { ChangesTab } from './ChangesTab'
import { ft, ftt, ftv } from './messages'
import { MeetingsTab } from './MeetingsTab'
import { fmm } from './minutes-messages'
import { MinutesTab } from './MinutesTab'
import { OutcomesTab } from './OutcomesTab'
import { PolicyTab } from './PolicyTab'
import { QuorumTab } from './QuorumTab'
import { settingsOf, settingsRequest, type TypeSettings } from './settings'
import { TermsTab } from './TermsTab'

const tones: Record<ForumTypeVersionResponse['state'], Tone> = {
  DRAFT: 'neutral',
  IN_REVIEW: 'warn',
  PUBLISHED: 'ok',
  RETIRED: 'neutral',
}

const REASONS = ['SAME_PERSON', 'DRAFT_EXISTS', 'WRONG_STATE'] as const

/**
 * `/admin/committee-types/$typeId` (flow J2, ADR-0056): one committee type, a version selector, and the version's
 * policy in tabs. A draft is changed and submitted by a holder of `forum.types.manage`; a version in review is
 * published (after a step-up) or sent back by a holder of `forum.types.approve` who did not submit it. The version
 * shown is in the address.
 */
export function ForumTypePage({ typeId, version }: { typeId: string; version?: number }) {
  const intl = useIntl()
  const terms = useTerms()
  const navigate = useNavigate()
  const toast = useToast()
  const queryClient = useQueryClient()
  const access = useMyAccess()
  const type = useQuery(getForumTypeOptions({ path: { typeId } }))
  const versions = useQuery(listForumTypeVersionsOptions({ path: { typeId } }))
  const [busy, setBusy] = useState(false)
  const list = versions.data ?? []
  const selected = version ?? list[0]?.number
  const manage = holds(access.data, PERMISSION.forumTypesManage)

  if (type.isPending || versions.isPending) return <LoadingState />
  if (type.isError || versions.isError) {
    return <ErrorState onRetry={() => void Promise.all([type.refetch(), versions.refetch()])} />
  }
  const t = type.data

  const open = (number: number) =>
    navigate({ to: '/admin/committee-types/$typeId', params: { typeId }, search: { version: number } })

  const refusal = (problem: unknown) => {
    const reason = REASONS.find((r) => isProblem(problem) && problem.reason === r)
    toast.show(intl.formatMessage(reason ? ft[reason] : ft.failed), 'error')
  }

  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: getForumTypeQueryKey({ path: { typeId } }) }),
      queryClient.invalidateQueries({ queryKey: listForumTypeVersionsQueryKey({ path: { typeId } }) }),
      queryClient.invalidateQueries({ queryKey: listForumTypesQueryKey() }),
    ])

  const newDraft = async () => {
    setBusy(true)
    try {
      const created = await createForumTypeDraft({ path: { typeId }, throwOnError: true })
      await refresh()
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
      await (t.active ? deactivateForumType : activateForumType)({ path: { typeId }, throwOnError: true })
      await refresh()
    } catch (problem) {
      refusal(problem)
    } finally {
      setBusy(false)
    }
  }

  const hasOpen = list.some((v) => v.state === 'DRAFT' || v.state === 'IN_REVIEW')
  const published = list.find((v) => v.state === 'PUBLISHED')
  return (
    <section aria-labelledby="forum-type-title" className="flex flex-col gap-4">
      <PageHeader
        className="mb-0"
        title={
          <span id="forum-type-title">{intl.formatMessage(ftv.nameWithCode, { name: t.name, code: t.code })}</span>
        }
        status={
          <Badge tone={t.active ? 'ok' : 'neutral'}>{intl.formatMessage(t.active ? ft.active : ft.inactive)}</Badge>
        }
        description={
          <Link to="/admin/committee-types" className="underline-offset-4 hover:underline">
            {intl.formatMessage(ftt.back, terms)}
          </Link>
        }
        actions={
          manage ? (
            <>
              {!hasOpen ? (
                <Button variant="outline" disabled={busy} onClick={() => void newDraft()}>
                  {intl.formatMessage(ft.newDraft)}
                </Button>
              ) : null}
              <Button variant="outline" disabled={busy} onClick={() => void toggle()}>
                {intl.formatMessage(t.active ? ft.switchOff : ft.switchOn)}
              </Button>
            </>
          ) : undefined
        }
      />
      {!t.active ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(ftt.switchedOffHint, terms)}</p>
      ) : null}
      <div className="flex flex-wrap items-end gap-4">
        <Field label={intl.formatMessage(ft.versionLabel)} className="w-56">
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
                  {intl.formatMessage(ftv.version, {
                    number: v.number,
                    state: intl.formatMessage(stateLabels[v.state]),
                  })}
                </option>
              ))}
            </Select>
          )}
        </Field>
        {published ? (
          <p className="pb-2.5 text-sm text-muted-foreground">
            {intl.formatMessage(ftv.published, { number: published.number })}
          </p>
        ) : null}
      </div>
      {selected != null ? (
        <VersionEditor
          key={selected}
          typeId={typeId}
          number={selected}
          published={published}
          manage={manage}
          approve={holds(access.data, PERMISSION.forumTypesApprove)}
          onRefusal={refusal}
          onChanged={refresh}
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
  onChanged,
}: {
  typeId: string
  number: number
  published?: ForumTypeVersionResponse
  manage: boolean
  approve: boolean
  onRefusal: (problem: unknown) => void
  onChanged: () => Promise<unknown>
}) {
  const intl = useIntl()
  const terms = useTerms()
  const toast = useToast()
  const withStepUp = useWithStepUp()
  const queryClient = useQueryClient()
  const path = { path: { typeId, number: String(number) } }
  const version = useQuery(getForumTypeVersionOptions(path))
  const levels = useQuery(listClassificationLevelsOptions())
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
      toast.show(intl.formatMessage(ft.done), 'success')
      setEdited(undefined)
      setRemark('')
      await Promise.all([queryClient.invalidateQueries({ queryKey: getForumTypeVersionQueryKey(path) }), onChanged()])
    } catch (problem) {
      const p = isProblem(problem) ? problem : undefined
      if (p?.errors?.length) {
        setProblems(Object.fromEntries(p.errors.map((e) => [e.field, e.message])))
      } else if (p?.status === 412) {
        toast.show(intl.formatMessage(ft.conflict), 'error')
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
  const save = () =>
    run(() => replaceForumTypeDraft({ ...path, headers: ifMatch, body: settingsRequest(settings), throwOnError: true }))
  const submit = () => run(() => submitForumTypeVersion({ ...path, headers: ifMatch, throwOnError: true }))
  const approveVersion = () =>
    run(() =>
      withStepUp(() =>
        approveForumTypeVersion({ ...path, body: { remark: remark.trim() || undefined }, throwOnError: true }),
      ),
    )
  const sendBack = () =>
    run(() => sendBackForumTypeVersion({ ...path, body: { remark: remark.trim() }, throwOnError: true }))

  const levelName = (id: string | null) =>
    id ? (levels.data?.find((l) => l.id === id)?.name ?? id) : intl.formatMessage(ft.lowest)
  const before = published && published.number !== v.number ? settingsOf(published) : undefined
  const changes = before ? changesBetween(intl, terms, before, settings, levelName) : []
  const problemList = Object.entries(problems)
  const open = v.state === 'DRAFT' || v.state === 'IN_REVIEW'
  const state = intl.formatMessage(stateLabels[v.state])

  return (
    <section aria-labelledby="version-title" className="flex flex-col gap-4 rounded-md border p-4">
      <div className="flex flex-wrap items-start gap-3">
        <div className="flex min-w-0 flex-[1_1_20rem] flex-col gap-1">
          <div className="flex flex-wrap items-center gap-3">
            <h2 id="version-title" className="text-lg font-semibold">
              {intl.formatMessage(ftv.version, { number: v.number, state })}
            </h2>
            <Badge tone={tones[v.state]}>{state}</Badge>
          </div>
          {v.submittedBy && v.submittedAt && v.state === 'IN_REVIEW' ? (
            <p className="text-sm text-muted-foreground">
              {intl.formatMessage(ftv.submittedBy, {
                name: v.submittedBy.name,
                when: intl.formatDate(v.submittedAt, { dateStyle: 'medium', timeStyle: 'short' }),
              })}
            </p>
          ) : null}
          {open ? <p className="text-sm">{intl.formatMessage(ftt.appliesTo, terms)}</p> : null}
          {v.reviewRemark ? (
            <p className="text-sm">{intl.formatMessage(ftv.reviewRemark, { remark: v.reviewRemark })}</p>
          ) : null}
          {!draft && !reviewing ? (
            <p className="text-sm text-muted-foreground">{intl.formatMessage(ft.readOnly)}</p>
          ) : null}
        </div>
        {draft ? (
          <div className="flex flex-wrap items-center gap-2">
            {edited ? <span className="text-sm text-muted-foreground">{intl.formatMessage(ft.unsaved)}</span> : null}
            <Button variant="outline" disabled={busy || !edited} onClick={() => void save()}>
              {intl.formatMessage(ft.save)}
            </Button>
            <Button disabled={busy || Boolean(edited)} onClick={() => void submit()}>
              {intl.formatMessage(ft.submit)}
            </Button>
          </div>
        ) : null}
      </div>
      {reviewing ? (
        <div className="flex max-w-2xl flex-col gap-3 rounded-md bg-muted/40 p-3">
          <Field label={intl.formatMessage(ft.remark)} hint={intl.formatMessage(ft.remarkHint)}>
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
            <Button variant="outline" disabled={busy || !remark.trim()} onClick={() => void sendBack()}>
              {intl.formatMessage(ft.sendBack)}
            </Button>
            <Button disabled={busy} onClick={() => void approveVersion()}>
              {intl.formatMessage(ft.approve)}
            </Button>
          </div>
        </div>
      ) : null}
      {problemList.length > 0 ? (
        <div role="alert" className="rounded-md border border-destructive p-3 text-sm text-destructive">
          <p className="font-medium">{intl.formatMessage(ft.invalid)}</p>
          <ul className="list-disc ps-5">
            {problemList.map(([field, message]) => (
              <li key={field}>{message}</li>
            ))}
          </ul>
        </div>
      ) : null}

      <Tabs defaultValue={reviewing ? 'changes' : 'policy'}>
        <TabsList aria-label={intl.formatMessage(ftt.tabs, terms)}>
          <TabsTrigger value="policy">{intl.formatMessage(ft.tabPolicy)}</TabsTrigger>
          <TabsTrigger value="quorum">{intl.formatMessage(ft.tabQuorum)}</TabsTrigger>
          <TabsTrigger value="outcomes">{intl.formatMessage(ft.tabOutcomes)}</TabsTrigger>
          <TabsTrigger value="terms">{intl.formatMessage(ft.tabTerms)}</TabsTrigger>
          <TabsTrigger value="agenda">{intl.formatMessage(tm.tab)}</TabsTrigger>
          <TabsTrigger value="meetings">{intl.formatMessage(ft.tabMeetings)}</TabsTrigger>
          <TabsTrigger value="minutes">{intl.formatMessage(fmm.tab)}</TabsTrigger>
          <TabsTrigger value="changes">{intl.formatMessage(ftv.tabChanges, { count: changes.length })}</TabsTrigger>
        </TabsList>
        <TabsContent value="policy" className="pt-4">
          <PolicyTab settings={settings} onChange={change} levels={levels.data ?? []} errors={problems} />
        </TabsContent>
        <TabsContent value="quorum" className="pt-4">
          <QuorumTab
            rule={settings.quorum}
            attributes={settings.memberAttributes}
            onChange={
              change
                ? (quorum) => {
                    change({ quorum })
                  }
                : undefined
            }
            errors={problems}
          />
        </TabsContent>
        <TabsContent value="outcomes" className="pt-4">
          <OutcomesTab
            outcomes={settings.outcomes}
            onChange={
              change
                ? (outcomes) => {
                    change({ outcomes })
                  }
                : undefined
            }
            errors={problems}
            terms={terms}
          />
        </TabsContent>
        <TabsContent value="terms" className="pt-4">
          <TermsTab settings={settings} onChange={change} errors={problems} />
        </TabsContent>
        <TabsContent value="agenda" className="pt-4">
          <TemplateTab
            agenda={settings.agenda}
            onChange={
              change
                ? (agenda) => {
                    change({ agenda })
                  }
                : undefined
            }
          />
        </TabsContent>
        <TabsContent value="meetings" className="pt-4">
          <MeetingsTab settings={settings} onChange={change} errors={problems} />
        </TabsContent>
        <TabsContent value="minutes" className="pt-4">
          <MinutesTab
            minutes={settings.minutes}
            {...(change
              ? {
                  onChange: (minutes: TypeSettings['minutes']) => {
                    change({ minutes })
                  },
                }
              : {})}
            errors={problems}
            typeId={typeId}
            number={v.number}
            unsaved={Boolean(edited)}
          />
        </TabsContent>
        <TabsContent value="changes" className="pt-4">
          <ChangesTab changes={changes} first={!published} />
        </TabsContent>
      </Tabs>
    </section>
  )
}
