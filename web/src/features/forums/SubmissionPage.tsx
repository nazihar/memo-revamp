import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useState, type ReactNode } from 'react'
import { useIntl } from 'react-intl'
import {
  getForumOptions,
  getForumPolicyOptions,
  getForumSubmissionOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import { isProblem } from '@/api/http'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useTerms } from '@/features/terms/terms'
import { paperName, paperTone } from './papers'
import { PaperTimeline } from './PaperTimeline'
import { pm } from './papers-messages'
import { formatDay } from './shared'
import { sm, smt, smv } from './submission-messages'
import { actionsFor, seesPapers, triages, type TriageAction } from './submissions'
import { TriageDialog } from './TriageDialogs'

/**
 * `/committees/$forumId/submissions/$submissionId`: one paper, where the committee's notifications lead (design E02 §3).
 * Its author, its presenter, readers of its memo and the committee's officers open it; secretaries and administrators
 * triage it here as from the queue. The committee itself may be hidden from the author, so the page needs only the paper.
 */
export function SubmissionPage({ forumId, submissionId }: { forumId: string; submissionId: string }) {
  const intl = useIntl()
  const terms = useTerms()
  const paper = useQuery(getForumSubmissionOptions({ path: { forumId, id: submissionId } }))
  // Officers only; anyone else gets 404 here and simply sees no triage actions.
  const forum = useQuery({ ...getForumOptions({ path: { forumId } }), retry: false })
  const officer = forum.data ? seesPapers(forum.data) : false
  const policy = useQuery({ ...getForumPolicyOptions({ path: { forumId } }), enabled: officer })
  const [open, setOpen] = useState<TriageAction>()

  if (paper.isPending) return <LoadingState />
  if (paper.isError) {
    return isProblem(paper.error) && paper.error.status === 404 ? (
      <EmptyState title={intl.formatMessage(sm.notFound)} />
    ) : (
      <ErrorState onRetry={() => void paper.refetch()} />
    )
  }
  const s = paper.data
  const actions = forum.data && triages(forum.data) ? actionsFor(s) : []
  const labels: Record<TriageAction, string> = {
    accept: intl.formatMessage(sm.accept),
    defer: intl.formatMessage(sm.defer),
    return: intl.formatMessage(sm.returnIt),
    decline: intl.formatMessage(sm.decline),
    refer: intl.formatMessage(smt.refer, terms),
    cover: intl.formatMessage(sm.coverSheet),
  }

  return (
    <section aria-labelledby="paper-title" className="flex max-w-3xl flex-col gap-4">
      <PageHeader
        className="mb-0"
        title={<span id="paper-title">{intl.formatMessage(smv.title, { name: s.forumName })}</span>}
        status={<Badge tone={paperTone[s.status]}>{intl.formatMessage(pm[s.status])}</Badge>}
        description={<span dir="auto">{paperName(intl, s)}</span>}
      />
      {officer ? (
        <Link
          to="/committees/$forumId"
          params={{ forumId }}
          search={{ tab: 'submissions' }}
          className="w-fit text-sm underline-offset-4 hover:underline"
        >
          {intl.formatMessage(smt.back, terms)}
        </Link>
      ) : null}
      <dl className="grid gap-x-6 gap-y-3 rounded-md border p-4 text-sm sm:grid-cols-[max-content_1fr]">
        <Row label={intl.formatMessage(sm.memo)}>
          {s.memo ? (
            <Link to="/memos/$memoId" params={{ memoId: s.memo.id }} className="underline underline-offset-4">
              {intl.formatMessage(sm.openMemo)}
            </Link>
          ) : (
            intl.formatMessage(pm.restrictedHint)
          )}
        </Row>
        <Row label={intl.formatMessage(sm.purpose)}>{intl.formatMessage(pm[s.purpose])}</Row>
        <Row label={intl.formatMessage(sm.sentBy)}>{s.submittedBy.name}</Row>
        {s.presenter ? <Row label={intl.formatMessage(sm.presenter)}>{s.presenter.name}</Row> : null}
        {s.targetDate ? <Row label={intl.formatMessage(sm.targetDate)}>{formatDay(intl, s.targetDate)}</Row> : null}
        {s.resolutionSought ? (
          <Row label={intl.formatMessage(sm.resolutionSought)}>
            <span className="whitespace-pre-wrap" dir="auto">
              {s.resolutionSought}
            </span>
          </Row>
        ) : null}
      </dl>
      {s.waits ? null : <p className="text-sm text-muted-foreground">{intl.formatMessage(pm.forInformation)}</p>}
      <PaperTimeline submission={s} className="rounded-md border p-4" />
      {actions.length ? (
        <div role="toolbar" aria-label={intl.formatMessage(sm.colActions)} className="flex flex-wrap gap-2">
          {actions.map((action) => (
            <Button
              key={action}
              variant={action === 'accept' ? 'default' : 'outline'}
              className="min-h-11"
              onClick={() => {
                setOpen(action)
              }}
            >
              {labels[action]}
            </Button>
          ))}
        </div>
      ) : null}
      {open ? (
        <TriageDialog
          action={open}
          submission={s}
          policy={policy.data?.submissionPolicy}
          onClose={() => {
            setOpen(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="font-medium">{label}</dt>
      <dd>{children}</dd>
    </>
  )
}
