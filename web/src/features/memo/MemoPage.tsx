import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Download, FilePenLine, FileText } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getMemoHistoryQueryKey,
  getMemoOptions,
  getMemoQueryKey,
  listMyMemosInfiniteQueryKey,
  listMyTasksInfiniteQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { claimTask, releaseTask } from '@/api/generated/sdk.gen'
import type { MemoView } from '@/api/generated/types.gen'
import { PROBLEM, type Problem } from '@/api/http'
import { FileUpload } from '@/components/page/FileUpload'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useToast } from '@/components/ui/toast-context'
import { newIdempotencyKey } from '@/lib/idempotency'
import { ActionDialog, type MemoActionKind } from './ActionDialog'
import { DoaPreviewDialog } from './DoaPreviewDialog'
import { dueLabel, isOverdue } from './due'
import { EditMemoDialog } from './EditMemoDialog'
import { MemoInterests } from './MemoInterests'
import { MemoHistory } from './MemoHistory'
import { referenceOf } from './memo-fields'
import { actionButtons, mm, mmv, statusLabels } from './memo-messages'
import { statusTone } from './memo-status'
import { RouteEditor } from './RouteEditor'
import { routeEntryLabel } from './route-entry'

const DOCX = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document,.docx'
const MAX_BYTES = 25 * 1024 * 1024

/** Which dialog each allowed action opens; EDIT, UPLOAD_DOCUMENT and EDIT_ROUTE are handled on the page itself. */
const DIALOG_ACTIONS: Partial<Record<MemoView['allowedActions'][number], MemoActionKind>> = {
  APPROVE: 'approve',
  RETURN: 'return',
  REJECT: 'reject',
  SUBMIT: 'submit',
  RESUBMIT: 'resubmit',
  WITHDRAW: 'withdraw',
  DISCARD: 'discard',
  RECOMMEND: 'recommend',
  RECALL: 'recall',
  ANSWER: 'answer',
  OPINION: 'opinion',
  SEND_BACK: 'sendBack',
  FORWARD: 'forward',
  DELEGATE: 'delegate',
  ASK: 'ask',
  REFER: 'refer',
  RECUSE: 'recuse',
}

/** The main action of the bar; the others are outlined. */
const PRIMARY = new Set<MemoActionKind>(['approve', 'recommend', 'submit', 'resubmit', 'answer', 'opinion'])

/** After these the task is no longer the person's, so the page goes back to their tasks. */
const LEAVES_TASK = new Set<MemoActionKind>([
  'approve',
  'recommend',
  'return',
  'reject',
  'sendBack',
  'forward',
  'answer',
  'opinion',
  'recuse',
])

/**
 * `/memos/$memoId` (design E01 §13, ux-review §4–§5): the memo's fields and status, its document, the route, the history
 * by round and the action bar. Below the medium breakpoint it stacks and the action bar is fixed at the bottom.
 */
export function MemoPage({ memoId }: { memoId: string }) {
  const intl = useIntl()
  const toast = useToast()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const memo = useQuery(getMemoOptions({ path: { id: memoId } }))
  const [dialog, setDialog] = useState<MemoActionKind>()
  const [editing, setEditing] = useState(false)
  const [previewing, setPreviewing] = useState(false)
  const [claiming, setClaiming] = useState(false)

  const replace = async (next: MemoView) => {
    queryClient.setQueryData(getMemoQueryKey({ path: { id: memoId } }), next)
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: getMemoHistoryQueryKey({ path: { id: memoId } }) }),
      queryClient.invalidateQueries({ queryKey: listMyTasksInfiniteQueryKey({ query: { limit: 25 } }) }),
      queryClient.invalidateQueries({ queryKey: listMyMemosInfiniteQueryKey({ query: { box: 'sent', limit: 25 } }) }),
    ])
  }
  const onProblem = async (problem: Problem | undefined) => {
    if (problem?.reason === 'DOCUMENT_BEING_EDITED') {
      toast.show(intl.formatMessage(mm.beingEdited), 'error')
    } else if (problem?.type === PROBLEM.conflict || problem?.status === 412) {
      toast.show(intl.formatMessage(mm.conflict), 'error')
      await memo.refetch()
    } else if (problem?.errors?.some((e) => e.field === 'document')) {
      toast.show(intl.formatMessage(mm.documentRequired), 'error')
    } else if (problem?.errors?.some((e) => e.code === 'doa-missing')) {
      toast.show(
        problem.errors.find((e) => e.code === 'doa-missing')?.message ?? intl.formatMessage(mm.failed),
        'error',
      )
    } else if (problem?.errors?.some((e) => e.field.startsWith('route'))) {
      toast.show(intl.formatMessage(mm.routeRequired), 'error')
    } else {
      toast.show(problem?.detail ?? intl.formatMessage(mm.failed), 'error')
    }
  }

  if (memo.isPending) return <LoadingState />
  if (memo.isError) {
    return (memo.error as Problem | undefined)?.status === 404 ? (
      <EmptyState title={intl.formatMessage(mm.notFound)} />
    ) : (
      <ErrorState onRetry={() => void memo.refetch()} />
    )
  }
  const view = memo.data
  const allowed = new Set(view.allowedActions)
  const dialogs = view.allowedActions.flatMap((a) => (DIALOG_ACTIONS[a] ? [DIALOG_ACTIONS[a]] : []))
  const document = view.document
  const taskId = view.myTask?.id

  /** Claim or release a shared task (design E04 §4.2): no remark, so no dialog. */
  const claim = async (release: boolean) => {
    if (!taskId) return
    setClaiming(true)
    try {
      const options = {
        path: { taskId },
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        throwOnError: true as const,
      }
      const next = release ? await releaseTask(options) : await claimTask(options)
      toast.show(intl.formatMessage(mm.done), 'success')
      await replace(next.data)
    } catch (problem) {
      await onProblem(problem as Problem | undefined)
    } finally {
      setClaiming(false)
    }
  }
  const claims = [
    allowed.has('CLAIM') ? ('claim' as const) : null,
    allowed.has('RELEASE') ? ('release' as const) : null,
  ].filter((c) => c !== null)

  return (
    <section aria-labelledby="memo-title" className="pb-24 md:pb-0">
      <PageHeader
        title={<span id="memo-title">{view.subject}</span>}
        description={`${referenceOf(intl, view.reference)} · ${view.type.name} · ${view.classification.name}`}
        status={<Badge tone={statusTone[view.status]}>{intl.formatMessage(statusLabels[view.status])}</Badge>}
        actions={
          allowed.has('EDIT') ? (
            <Button
              variant="outline"
              onClick={() => {
                setEditing(true)
              }}
            >
              {intl.formatMessage(mm.actionEdit)}
            </Button>
          ) : null
        }
      />
      <div className="grid gap-6 md:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="flex flex-col gap-6">
          <Section title={intl.formatMessage(mm.document)}>
            {document ? (
              <div className="flex flex-wrap items-center gap-3">
                <span className="text-sm">
                  {intl.formatMessage(mmv.documentVersion, { number: document.number, name: document.name })}
                </span>
                <Button variant="outline" asChild>
                  <a href={`/api/v1/files/${document.fileId}/versions/${document.versionId}/content`} download>
                    <Download aria-hidden="true" />
                    {intl.formatMessage(mm.download)}
                  </a>
                </Button>
                {/* Phones read only (design E02): the editor's mobile editing is out of scope. */}
                {allowed.has('EDIT_DOCUMENT') ? (
                  <Button variant="outline" className="max-sm:hidden" asChild>
                    <Link to="/memos/$memoId/document" params={{ memoId: view.id }} search={{ mode: 'edit' }}>
                      <FilePenLine aria-hidden="true" />
                      {intl.formatMessage(mm.editInBrowser)}
                    </Link>
                  </Button>
                ) : null}
                {allowed.has('VIEW_DOCUMENT') ? (
                  <Button variant="outline" className={allowed.has('EDIT_DOCUMENT') ? 'sm:hidden' : undefined} asChild>
                    <Link to="/memos/$memoId/document" params={{ memoId: view.id }} search={{ mode: 'view' }}>
                      <FileText aria-hidden="true" />
                      {intl.formatMessage(mm.openInBrowser)}
                    </Link>
                  </Button>
                ) : null}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">{intl.formatMessage(mm.documentNone)}</p>
            )}
            {allowed.has('UPLOAD_DOCUMENT') ? (
              <div className="mt-3">
                <FileUpload
                  label={intl.formatMessage(document ? mm.documentNewVersion : mm.documentUpload)}
                  hint={intl.formatMessage(mm.documentHint)}
                  purpose="memo.document"
                  owner={view.id}
                  accept={DOCX}
                  maxBytes={MAX_BYTES}
                  version={document ? { fileId: document.fileId, fileVersion: document.fileVersion } : undefined}
                  onUploaded={() => void memo.refetch()}
                />
              </div>
            ) : null}
          </Section>
          <Section title={intl.formatMessage(mm.history)}>
            <MemoHistory memoId={view.id} />
          </Section>
        </div>
        <div className="flex flex-col gap-6">
          {view.holders.length > 0 || view.myTask || view.dueAt ? (
            <Section title={intl.formatMessage(mm.holders)}>
              {view.holders.length > 0 ? <p className="text-sm">{view.holders.map((h) => h.name).join(', ')}</p> : null}
              {view.dueAt ? (
                <p className="mt-1">
                  <Badge tone={isOverdue(view.dueAt) ? 'danger' : 'neutral'}>{dueLabel(intl, view.dueAt)}</Badge>
                </p>
              ) : null}
              {view.myTask?.stepName ? (
                <p className="text-sm text-muted-foreground">
                  {intl.formatMessage(mmv.step, { step: view.myTask.stepName })}
                </p>
              ) : null}
              {view.myTask?.role === 'OPINION' ? (
                <Badge tone="neutral">{intl.formatMessage(mm.roleOpinion)}</Badge>
              ) : null}
              {view.myTask?.role === 'CLARIFY' ? (
                <Badge tone="neutral">{intl.formatMessage(mm.roleClarify)}</Badge>
              ) : null}
              {view.myTask?.shared ? (
                <p role="status" className="mt-2 text-sm">
                  {allowed.has('RELEASE')
                    ? intl.formatMessage(mm.claimed)
                    : view.myTask.claimedBy
                      ? intl.formatMessage(mmv.claimedBy, { name: view.myTask.claimedBy.name })
                      : intl.formatMessage(mm.shared)}
                </p>
              ) : null}
              {view.myTask?.waiting ? (
                <p role="status" className="mt-2 text-sm">
                  {intl.formatMessage(mm.waitingForAnswer)}
                </p>
              ) : null}
            </Section>
          ) : null}
          {allowed.has('EDIT_ROUTE') || view.route.length > 0 ? (
            <Section title={intl.formatMessage(mm.route)} lead={intl.formatMessage(mm.routeLead)}>
              {allowed.has('EDIT_ROUTE') ? (
                <RouteEditor
                  key={view.version}
                  memo={view}
                  onSaved={(saved) => {
                    toast.show(intl.formatMessage(mm.routeSaved), 'success')
                    void replace(saved)
                  }}
                  onProblem={(p) => void onProblem(p)}
                />
              ) : view.route.length === 0 ? (
                <p className="text-sm text-muted-foreground">{intl.formatMessage(mm.routeEmpty)}</p>
              ) : (
                <ol className="flex list-decimal flex-col gap-1 ps-5 text-sm">
                  {view.route.map((entry) => (
                    <li key={entry.ordinal}>{routeEntryLabel(intl, entry)}</li>
                  ))}
                </ol>
              )}
              {allowed.has('SUBMIT') || allowed.has('RESUBMIT') ? (
                <div className="mt-3">
                  <Button
                    variant="outline"
                    onClick={() => {
                      setPreviewing(true)
                    }}
                  >
                    {intl.formatMessage(mm.doaPreview)}
                  </Button>
                </div>
              ) : null}
            </Section>
          ) : null}
          {allowed.has('EDIT') || view.interests.length > 0 ? (
            <Section title={intl.formatMessage(mm.interests)} lead={intl.formatMessage(mm.interestsLead)}>
              <MemoInterests
                memo={view}
                editable={allowed.has('EDIT')}
                onChanged={(next) => void replace(next)}
                onProblem={(p) => void onProblem(p)}
              />
            </Section>
          ) : null}
          <div className="text-sm text-muted-foreground">
            {intl.formatMessage(mmv.from, { name: view.initiator.name })}
          </div>
        </div>
      </div>
      {dialogs.length > 0 || claims.length > 0 ? (
        <div
          role="toolbar"
          aria-label={intl.formatMessage(mm.actions)}
          className="fixed inset-x-0 bottom-0 z-10 flex flex-wrap justify-end gap-2 border-t bg-card px-4 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] md:static md:mt-6 md:border-0 md:bg-transparent md:p-0"
        >
          {claims.map((kind) => (
            <Button
              key={kind}
              className="min-h-11 min-w-11"
              variant="outline"
              disabled={claiming}
              onClick={() => void claim(kind === 'release')}
            >
              {intl.formatMessage(kind === 'claim' ? mm.actionClaim : mm.actionRelease)}
            </Button>
          ))}
          {dialogs.map((kind) => (
            <Button
              key={kind}
              className="min-h-11 min-w-11"
              variant={PRIMARY.has(kind) ? 'default' : 'outline'}
              onClick={() => {
                setDialog(kind)
              }}
            >
              {intl.formatMessage(actionButtons[kind])}
            </Button>
          ))}
        </div>
      ) : null}
      {dialog ? (
        <ActionDialog
          memo={view}
          kind={dialog}
          onClose={() => {
            setDialog(undefined)
          }}
          onDone={(next) => {
            setDialog(undefined)
            toast.show(intl.formatMessage(mm.done), 'success')
            void replace(next)
            if (LEAVES_TASK.has(dialog)) {
              void navigate({ to: '/inbox', search: { tab: 'tasks' } })
            }
          }}
          onProblem={(p) => {
            setDialog(undefined)
            void onProblem(p)
          }}
        />
      ) : null}
      {previewing ? (
        <DoaPreviewDialog
          memoId={view.id}
          onClose={() => {
            setPreviewing(false)
          }}
        />
      ) : null}
      {editing ? (
        <EditMemoDialog
          memo={view}
          onClose={() => {
            setEditing(false)
          }}
          onSaved={(saved) => {
            setEditing(false)
            void replace(saved)
          }}
          onProblem={(p) => void onProblem(p)}
        />
      ) : null}
    </section>
  )
}

function Section({ title, lead, children }: { title: string; lead?: string; children: React.ReactNode }) {
  return (
    <section className="rounded-md border p-4">
      <h2 className="text-base font-semibold">{title}</h2>
      {lead ? <p className="mb-3 text-sm text-muted-foreground">{lead}</p> : <div className="mb-3" />}
      {children}
    </section>
  )
}
