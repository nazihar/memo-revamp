import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Printer } from 'lucide-react'
import { useId } from 'react'
import { useIntl } from 'react-intl'
import { getActionReportOptions } from '@/api/generated/@tanstack/react-query.gen'
import type {
  ActionReportRowResponse,
  ActionReportSectionResponse,
  ReportMemoResponse,
} from '@/api/generated/types.gen'
import type { Problem } from '@/api/http'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/form-controls'
import { statusLabels } from '@/features/memo/memo-messages'
import { useTerms } from '@/features/terms/terms'
import { am } from './messages'
import { CloseAgreed } from './officer/CloseAgreed'
import { RagBadge, RagKey } from './RagBadge'
import { cancelWord, dayOf, dueWords } from './words'

type MemoStatus = keyof typeof statusLabels

type Shown = 'TO_CLOSE' | 'OVERDUE' | 'OPEN' | 'CLOSED' | 'CANCELLED' | 'CORRECTIONS'

const HEADINGS: Record<Shown, (typeof am)[keyof typeof am]> = {
  TO_CLOSE: am.sectionTO_CLOSE,
  OVERDUE: am.sectionOVERDUE,
  OPEN: am.sectionOPEN,
  CLOSED: am.sectionCLOSED,
  CANCELLED: am.sectionCANCELLED,
  CORRECTIONS: am.sectionCORRECTIONS,
}

const shown = (s: ActionReportSectionResponse): s is ActionReportSectionResponse & { section: Shown } =>
  s.section in HEADINGS

/**
 * `/meetings/{id}/action-report`, the action taken report (flow AO, design §1.12): fixed when the agenda was published,
 * the sections that need the meeting first (proposed to close, overdue), then open, closed and cancelled since the last
 * meeting and corrections to earlier minutes; each reader's rows and counts by the one rule. "Print" opens the reader's
 * rows as a PDF (built on demand by the server). An earlier revision's report can be chosen; it never changed. At the
 * meeting, its officers close the actions the committee agreed (E07 T10, flow AM2).
 */
export function ActionReportPage({
  meetingId,
  revision,
  onRevision,
}: {
  meetingId: string
  revision?: number
  onRevision: (revision: number | undefined) => void
}) {
  const intl = useIntl()
  const terms = useTerms()
  const revisionId = useId()
  const report = useQuery(getActionReportOptions({ path: { meetingId }, ...(revision ? { query: { revision } } : {}) }))
  if (report.isPending) return <LoadingState />
  if (report.isError) {
    return (report.error as Problem | undefined)?.status === 404 ? (
      <EmptyState title={intl.formatMessage(am.reportNone)} />
    ) : (
      <ErrorState onRetry={() => void report.refetch()} />
    )
  }
  const r = report.data
  const latest = Math.max(...r.revisions, r.revision)
  const pdf = `/api/v1/meetings/${meetingId}/action-report/pdf${revision ? `?revision=${String(revision)}` : ''}`
  return (
    <article aria-labelledby="report-title" className="flex max-w-5xl flex-col gap-6">
      <header className="flex flex-col gap-3">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <h1 id="report-title" className="text-2xl font-semibold md:text-3xl">
            {r.meetingLabel
              ? intl.formatMessage(am.reportOf, { committee: r.committee, meeting: r.meetingLabel })
              : intl.formatMessage(am.reportOfCommittee, { committee: r.committee })}
          </h1>
          <Button asChild variant="outline" className="min-h-11 text-base print:hidden">
            <a href={pdf} target="_blank" rel="noopener" aria-describedby="report-print-hint">
              <Printer aria-hidden="true" />
              {intl.formatMessage(am.reportPrint)}
            </a>
          </Button>
        </div>
        <p id="report-print-hint" className="sr-only">
          {intl.formatMessage(am.reportPrintHint)}
        </p>
        <p className="text-lg">
          {intl.formatMessage(am.reportBuilt, {
            date: intl.formatDate(r.builtAt, { dateStyle: 'long' }),
            time: intl.formatTime(r.builtAt, { timeStyle: 'short' }),
          })}
        </p>
        <p className="text-muted-foreground">
          {r.previousMeetingLabel
            ? intl.formatMessage(am.reportSince, { meeting: r.previousMeetingLabel })
            : r.since
              ? null
              : intl.formatMessage(am.reportSinceStart)}
        </p>
        {r.revisions.length > 1 ? (
          <div className="flex flex-wrap items-center gap-2 print:hidden">
            <label htmlFor={revisionId} className="font-medium">
              {intl.formatMessage(am.reportRevision)}
            </label>
            <Select
              id={revisionId}
              className="w-auto"
              value={String(r.revision)}
              onChange={(e) => {
                const n = Number(e.target.value)
                onRevision(n === latest ? undefined : n)
              }}
            >
              {[...r.revisions]
                .sort((x, y) => y - x)
                .map((n) => (
                  <option key={n} value={n}>
                    {intl.formatMessage(n === latest ? am.reportRevisionLatest : am.reportRevisionN, { n })}
                  </option>
                ))}
            </Select>
          </div>
        ) : null}
      </header>

      {r.sections.filter(shown).map((s, i) => (
        <Section key={s.section} number={i + 1} section={s} />
      ))}

      {revision ? null : <CloseAgreed report={r} />}

      {r.more > 0 ? <p className="text-lg">{intl.formatMessage(am.reportMore, { ...terms, count: r.more })}</p> : null}
      {r.heldBack ? (
        <p className="text-muted-foreground">{intl.formatMessage(am.reportHeldBack, { count: r.heldBack })}</p>
      ) : null}

      <RagKey amberWorkingDays={r.key.amberWorkingDays} />

      <p className="print:hidden">
        <Link
          to="/meetings/$meetingId"
          params={{ meetingId }}
          className="inline-flex min-h-11 items-center text-primary underline underline-offset-4"
        >
          {intl.formatMessage(am.reportBack)}
        </Link>
      </p>
    </article>
  )
}

function Section({
  number,
  section: s,
}: {
  number: number
  section: ActionReportSectionResponse & { section: Shown }
}) {
  const intl = useIntl()
  const terms = useTerms()
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-2 break-inside-avoid-page">
      <h2 id={headingId} className="text-xl font-semibold">
        {intl.formatMessage(am.sectionNumbered, {
          number,
          heading: intl.formatMessage(HEADINGS[s.section], { ...terms, count: s.count }),
        })}
      </h2>
      {s.section === 'CORRECTIONS' ? (
        s.corrections.length ? (
          <ul className="flex flex-col gap-1 text-lg">
            {s.corrections.map((c, i) => (
              <li key={i}>
                {c.approvedAt
                  ? intl.formatMessage(am.correctionApproved, {
                      label: c.label,
                      date: dayOf(intl, c.approvedAt, 'long'),
                    })
                  : c.label}
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-muted-foreground">{intl.formatMessage(am.sectionEmpty)}</p>
        )
      ) : s.rows.length ? (
        <ul className="flex flex-col divide-y border-y">
          {s.rows.map((row) => (
            <li key={row.actionId}>
              <Row row={row} />
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-muted-foreground">{intl.formatMessage(am.sectionEmpty)}</p>
      )}
    </section>
  )
}

/** "BRC A9/2026  Revise the liquidity contingency plan  Head of Treasury  ◐ Done 2 Dec", and what was said last. */
function Row({ row }: { row: ActionReportRowResponse }) {
  const intl = useIntl()
  const facts = [
    row.holder
      ? intl.formatMessage(am.rowOwnerHolder, { owner: row.owner, holder: row.holder })
      : intl.formatMessage(am.rowOwner, { owner: row.owner }),
    row.status === 'DONE_PENDING' && row.doneAt
      ? intl.formatMessage(am.rowDone, { date: dayOf(intl, row.doneAt) })
      : row.status === 'CLOSED' && row.closedAt
        ? intl.formatMessage(am.rowClosed, { date: dayOf(intl, row.closedAt) })
        : row.status === 'CANCELLED' && row.cancelKind
          ? intl.formatMessage(am.rowCancelled, { kind: cancelWord(intl, row.cancelKind) })
          : dueWords(intl, row),
    row.sourceLabel,
  ]
  return (
    <div className="flex flex-col gap-1 py-3">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <p className="flex flex-wrap gap-x-2 text-lg font-medium">
          <Link
            to="/actions/$actionId"
            params={{ actionId: row.actionId }}
            className="tabular-nums underline-offset-4 hover:underline"
          >
            {row.reference}
          </Link>
          <span>{row.title}</span>
        </p>
        <RagBadge rag={row.rag} />
      </div>
      <p className="text-muted-foreground">{facts.join(' · ')}</p>
      {row.status === 'CANCELLED' && row.cancelReason ? (
        <p dir="auto">{intl.formatMessage(am.quoted, { text: row.cancelReason })}</p>
      ) : null}
      {row.latestUpdate ? (
        <p>
          {row.latestUpdateAt
            ? intl.formatMessage(am.rowLatest, {
                date: dayOf(intl, row.latestUpdateAt),
                // The person's words in their own direction inside the reader's sentence (E07 T12).
                text: <bdi>{row.latestUpdate}</bdi>,
              })
            : intl.formatMessage(am.rowLatestUndated, { text: <bdi>{row.latestUpdate}</bdi> })}
        </p>
      ) : null}
      {row.memos.length ? <RowMemos memos={row.memos} /> : null}
    </div>
  )
}

/**
 * The row's follow-up memos the reader may read, as they stand now (the server joins them at read time by the memo's own
 * rule; a memo the reader may not read is not there at all). Each opens the memo.
 */
function RowMemos({ memos }: { memos: ReportMemoResponse[] }) {
  const intl = useIntl()
  const labelId = useId()
  return (
    <div className="flex flex-col gap-1">
      <p id={labelId} className="text-sm font-medium text-muted-foreground">
        {intl.formatMessage(am.rowMemos)}
      </p>
      <ul aria-labelledby={labelId} className="flex flex-col gap-1">
        {memos.map((m) => {
          const status = m.status in statusLabels ? intl.formatMessage(statusLabels[m.status as MemoStatus]) : m.status
          const words = m.reference
            ? intl.formatMessage(am.rowMemo, { reference: m.reference, subject: m.subject, status })
            : intl.formatMessage(am.rowMemoUnnumbered, { subject: m.subject, status })
          return (
            <li key={m.memoId}>
              <Link
                to="/memos/$memoId"
                params={{ memoId: m.memoId }}
                className="inline-flex min-h-11 items-center underline underline-offset-4"
              >
                {words}
              </Link>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
