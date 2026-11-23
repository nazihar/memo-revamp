import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { FileDown } from 'lucide-react'
import { useEffect, useRef, useState, type RefObject } from 'react'
import { useIntl } from 'react-intl'
import {
  getResolutionOptions,
  listResolutionExtractsOptions,
  listResolutionExtractsQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { issueResolutionExtract } from '@/api/generated/sdk.gen'
import type { ResolutionExtractResponse, ResolutionResponse } from '@/api/generated/types.gen'
import { isProblem, problemType, PROBLEM } from '@/api/http'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { InTheRegisterLink } from '@/features/decision-register/links'
import { FormDialog } from '@/features/forums/FormDialog'
import { newIdempotencyKey } from '@/lib/idempotency'
import { rsm, rsmv } from './messages'

/** How often an extract being made is asked about. */
export const EXTRACT_POLL_MS = 2_000

const day = (intl: ReturnType<typeof useIntl>, at: string) => intl.formatDate(at, { dateStyle: 'long' })
const date = (intl: ReturnType<typeof useIntl>, d: string) =>
  intl.formatDate(`${d}T12:00:00Z`, { dateStyle: 'long', timeZone: 'UTC' })

/**
 * `/resolutions/$resolutionId` (flow AC2): the number and whether it is in force, the committee, meeting, date and item,
 * the outcome in capitals, the text, conditions and validity, the memo and when it moved on, whether the minutes are
 * approved, and the certified extracts (AC3). A reader recused from the item, or a reader of its memo, gets the outcome,
 * number, text and conditions only (the server leaves the rest out).
 */
export function ResolutionPage({ resolutionId }: { resolutionId: string }) {
  const intl = useIntl()
  const resolution = useQuery(getResolutionOptions({ path: { resolutionId } }))
  if (resolution.isPending) return <LoadingState />
  if (resolution.isError) return <ErrorState onRetry={() => void resolution.refetch()} />
  const r = resolution.data
  const item = r.itemNumber
    ? r.itemTitle
      ? intl.formatMessage(rsmv.itemTitle, { number: r.itemNumber, title: r.itemTitle })
      : intl.formatMessage(rsmv.itemNumber, { number: r.itemNumber })
    : ''
  return (
    <section aria-labelledby="resolution-title" className="flex max-w-3xl flex-col gap-5">
      <PageHeader
        className="mb-0"
        title={<span id="resolution-title">{intl.formatMessage(rsmv.numberTitle, { number: r.number })}</span>}
        status={
          <Badge tone={r.state === 'IN_FORCE' ? 'ok' : 'neutral'}>
            {intl.formatMessage(r.state === 'IN_FORCE' ? rsm.inForce : rsm.void)}
          </Badge>
        }
      />
      <p className="text-lg">
        {intl.formatMessage(rsmv.meetingLine, {
          committee: r.forumName,
          meeting: r.meetingLabel ?? '',
          date: date(intl, r.meetingDate),
          item,
        })}
      </p>
      {r.viewerRecused ? <p className="text-lg font-medium">{intl.formatMessage(rsm.recused)}</p> : null}
      {r.state === 'VOID' && r.voidReason && r.voidedAt ? (
        <p role="status" className="rounded-md border p-3 text-lg">
          {intl.formatMessage(rsmv.voidedOn, { date: day(intl, r.voidedAt), reason: r.voidReason })}
        </p>
      ) : null}
      <div className="flex flex-col gap-2 text-lg">
        <p className="text-xl font-semibold">{r.outcomeLabel.toLocaleUpperCase(intl.locale)}</p>
        {r.text ? <p className="whitespace-pre-line">{r.text}</p> : null}
        {r.conditions.length ? (
          <div>
            <p className="font-medium">{intl.formatMessage(rsm.conditions)}</p>
            <ol className="list-decimal ps-8">
              {r.conditions.map((c, i) => (
                <li key={i}>{c}</li>
              ))}
            </ol>
          </div>
        ) : null}
        {r.validUntil ? <p>{intl.formatMessage(rsmv.validUntil, { date: date(intl, r.validUntil) })}</p> : null}
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-lg">
        {r.memo ? (
          <>
            <dt className="font-medium">{intl.formatMessage(rsm.memo)}</dt>
            <dd>
              <Link
                to="/memos/$memoId"
                params={{ memoId: r.memo.id }}
                className="inline-flex min-h-11 items-center underline underline-offset-4"
              >
                {r.releasedAt
                  ? intl.formatMessage(rsmv.memoMoved, {
                      reference: r.memo.reference ?? intl.formatMessage(rsm.memo),
                      date: day(intl, r.releasedAt),
                    })
                  : (r.memo.reference ?? intl.formatMessage(rsm.memo))}
              </Link>
            </dd>
          </>
        ) : null}
        <dt className="font-medium">{intl.formatMessage(rsm.minutes)}</dt>
        <dd>
          {r.minutesApprovedAt
            ? intl.formatMessage(rsmv.minutesApproved, { date: day(intl, r.minutesApprovedAt) })
            : intl.formatMessage(rsm.minutesNotApproved)}
        </dd>
      </dl>
      {r.full ? (
        <Link
          to="/meetings/$meetingId"
          params={{ meetingId: r.meetingId }}
          className="inline-flex min-h-11 w-fit items-center underline-offset-4 hover:underline"
        >
          {intl.formatMessage(rsm.backToMeeting)}
        </Link>
      ) : null}
      <InTheRegisterLink forumId={r.forumId} resolutionNumber={r.number} />
      <Extracts resolution={r} />
    </section>
  )
}

/**
 * The certified extracts (flow AC3): each with who issued it, when, what it says about the minutes and its
 * fingerprint, "Open the extract (PDF)" once made; "Issue a certified extract" for a secretary who may (with their
 * authenticator). Readers who may not see extracts (members, 403) see nothing of them.
 */
function Extracts({ resolution: r }: { resolution: ResolutionResponse }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const withStepUp = useWithStepUp()
  const [issuing, setIssuing] = useState(false)
  const [issued, setIssued] = useState<string>()
  const openRef = useRef<HTMLAnchorElement>(null)
  const path = { path: { resolutionId: r.id } }
  const extracts = useQuery({
    ...listResolutionExtractsOptions(path),
    retry: false,
    refetchInterval: (q) => (q.state.data?.some((e) => e.state === 'PENDING') ? EXTRACT_POLL_MS : false),
  })
  const ready = extracts.data?.find((e) => e.id === issued && e.state === 'READY')

  // The extract just issued: once it is made, its link takes the focus ("the PDF opens" from there).
  useEffect(() => {
    if (ready) openRef.current?.focus()
  }, [ready])

  if (extracts.isPending || (extracts.isError && !r.mayIssueExtract)) return null
  const list = extracts.data ?? []

  const issue = async (): Promise<string | undefined> => {
    const key = newIdempotencyKey()
    try {
      const result = await withStepUp(() =>
        issueResolutionExtract({ ...path, headers: { 'Idempotency-Key': key }, throwOnError: true }),
      )
      setIssued(result.data.id)
      setIssuing(false)
      await queryClient.invalidateQueries({ queryKey: listResolutionExtractsQueryKey(path) })
      return undefined
    } catch (problem) {
      if (problemType(problem) === PROBLEM.stepUpRequired) return intl.formatMessage(rsm.stepUpCancelled)
      if (!isProblem(problem)) return intl.formatMessage(rsm.failed)
      switch (problem.reason) {
        case 'NOT_SECRETARY':
        case 'RECUSED':
        case 'VOID':
        case 'NOT_RELEASED':
          return intl.formatMessage(rsm[problem.reason])
      }
      return intl.formatMessage(rsm.failed)
    }
  }

  return (
    <section aria-labelledby="extracts-title" className="flex flex-col gap-3 border-t pt-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="extracts-title" className="text-xl font-semibold">
          {intl.formatMessage(rsm.extracts)}
        </h2>
        {r.mayIssueExtract ? (
          <Button
            className="min-h-11 text-base"
            onClick={() => {
              setIssuing(true)
            }}
          >
            {intl.formatMessage(rsm.issue)}
          </Button>
        ) : null}
      </div>
      {issued && !ready ? (
        <p role="status" className="text-base">
          {intl.formatMessage(rsm.issued)}
        </p>
      ) : null}
      {list.length === 0 ? (
        <p className="text-base">{intl.formatMessage(rsm.extractsNone)}</p>
      ) : (
        <ul className="flex flex-col divide-y rounded-md border">
          {list.map((e) => (
            <ExtractRow key={e.id} extract={e} resolutionId={r.id} {...(e.id === issued ? { openRef } : {})} />
          ))}
        </ul>
      )}
      {issuing ? (
        <FormDialog
          large
          title={intl.formatMessage(rsm.issueTitle)}
          description={intl.formatMessage(rsmv.certifyAs, { committee: r.forumName })}
          submitLabel={intl.formatMessage(rsm.issueSubmit)}
          cancelLabel={intl.formatMessage(rsm.cancel)}
          onSubmit={issue}
          onClose={() => {
            setIssuing(false)
          }}
        >
          <p className="text-base">
            {intl.formatMessage(r.minutesApprovedAt ? rsm.issueApproved : rsm.issueNotApproved)}
          </p>
          <p className="text-base text-muted-foreground">{intl.formatMessage(rsm.issueWhat)}</p>
        </FormDialog>
      ) : null}
    </section>
  )
}

function ExtractRow({
  extract: e,
  resolutionId,
  openRef,
}: {
  extract: ResolutionExtractResponse
  resolutionId: string
  openRef?: RefObject<HTMLAnchorElement | null>
}) {
  const intl = useIntl()
  const url = `/api/v1/resolutions/${resolutionId}/extracts/${e.id}/content`
  return (
    <li className="flex flex-col gap-1 p-3 text-base">
      <span className="font-medium">
        {intl.formatMessage(rsmv.extractLine, {
          time: intl.formatDate(e.issuedAt, { dateStyle: 'medium', timeStyle: 'short' }),
          name: e.issuedBy.name,
        })}
      </span>
      <span>{intl.formatMessage(rsmv.extractMinutes, { approved: e.minutesApproved ? 'yes' : 'no' })}</span>
      {e.sha256 ? (
        <span className="break-all font-mono text-sm text-muted-foreground" dir="ltr">
          {intl.formatMessage(rsmv.fingerprint, { hash: e.sha256 })}
        </span>
      ) : null}
      {e.state === 'READY' ? (
        <Button asChild variant="outline" className="mt-1 min-h-11 w-fit text-base">
          <a ref={openRef} href={url} download>
            <FileDown aria-hidden="true" />
            {intl.formatMessage(rsm.open)}
          </a>
        </Button>
      ) : e.state === 'FAILED' ? (
        <span role="alert">{intl.formatMessage(e.error === 'CHANGED' ? rsm.changedExtract : rsm.failedExtract)}</span>
      ) : (
        <span>{intl.formatMessage(rsm.pending)}</span>
      )}
    </li>
  )
}
