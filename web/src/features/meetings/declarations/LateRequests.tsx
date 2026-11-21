import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useIntl, type IntlShape } from 'react-intl'
import {
  getAgendaDraftQueryKey,
  getMyLateRequestsOptions,
  getMyLateRequestsQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { decideLateRequest } from '@/api/generated/sdk.gen'
import type { LateRequestResponse, MeetingResponse } from '@/api/generated/types.gen'
import { isProblem, problemType, PROBLEM } from '@/api/http'
import { Button } from '@/components/ui/button'
import { AlertDialog } from '@/components/ui/dialog'
import { pm } from '../page/messages'
import { lm, lmv } from './late-messages'
import { itemName } from './words'

const TITLE_ID = 'late-requests-title'

const button = 'h-auto min-h-11 whitespace-normal text-base'

/** "Valuation report, for 5 Term loan, Delta Textiles", or "Restricted item" and "A restricted paper" by the rule. */
function requestName(intl: IntlShape, r: LateRequestResponse): string {
  const item = itemName(intl, r.number, r.itemTitle)
  if (!r.paperId) return item
  return intl.formatMessage(lmv.paperFor, { paper: r.paperTitle ?? intl.formatMessage(lm.restrictedPaper), item })
}

/** A refused decision in words. */
function lateRefusal(intl: IntlShape, problem: unknown): string {
  if (problemType(problem) === PROBLEM.rateLimited) return intl.formatMessage(lm.rateLimited)
  if (!isProblem(problem)) return intl.formatMessage(lm.failed)
  switch (problem.reason) {
    case 'RECUSED':
      return intl.formatMessage(lm.recused)
    case 'WRONG_STATE':
      return intl.formatMessage(lm.wrongState)
    case 'FROZEN':
      return intl.formatMessage(lm.frozen)
  }
  switch (problem.status) {
    case 403:
      return intl.formatMessage(lm.forbidden)
    case 404:
      return intl.formatMessage(lm.notFound)
    case 409:
      return intl.formatMessage(lm.wrongState)
    case 412:
      return intl.formatMessage(lm.stale)
    case 429:
      return intl.formatMessage(lm.rateLimited)
  }
  return intl.formatMessage(lm.failed)
}

/**
 * "Late papers for you to decide" on the meeting page (Phase 4 E04, design §1.7 as built): the late items and papers
 * waiting for the viewer's decision, from `GET …/agenda/late-requests`, which lists only the ones the server says they
 * decide — the chair, or the vice-chair on the items the chair has declared an interest in (who, as a member of the
 * meeting, cannot open the draft agenda). Each reads as the paper rule lets the viewer read it ("Restricted item", "A
 * restricted paper"). "Agree to include it" or "Not this meeting" (confirmed first), sent with the agenda's version;
 * the answer replaces the list, and a refusal is said in words and the list read again. Nothing is shown when there is
 * nothing to decide.
 */
export function LateRequests({ meeting }: { meeting: MeetingResponse }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const meetingId = meeting.id
  const requests = useQuery(getMyLateRequestsOptions({ path: { meetingId } }))
  const [declining, setDeclining] = useState<LateRequestResponse>()
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<{ text: string; problem: boolean }>()

  const list = requests.data?.requests ?? []
  if (!list.length && !status) return null

  const decide = async (r: LateRequestResponse, agree: boolean) => {
    const name = requestName(intl, r)
    setBusy(true)
    setStatus(undefined)
    try {
      const answer = await decideLateRequest({
        path: { meetingId },
        headers: { 'If-Match': `"${String(requests.data?.version ?? 0)}"` },
        body: { itemId: r.itemId, agree, ...(r.paperId ? { paperId: r.paperId } : {}) },
        throwOnError: true,
      })
      queryClient.setQueryData(getMyLateRequestsQueryKey({ path: { meetingId } }), answer.data)
      setStatus({ text: intl.formatMessage(agree ? lmv.agreed : lmv.declined, { name }), problem: false })
    } catch (problem) {
      setStatus({ text: lateRefusal(intl, problem), problem: true })
      await requests.refetch()
    } finally {
      setBusy(false)
      await queryClient.invalidateQueries({ queryKey: getAgendaDraftQueryKey({ path: { meetingId } }) })
      window.setTimeout(() => document.getElementById(TITLE_ID)?.focus(), 0)
    }
  }

  const deadline = requests.data?.paperDeadline
  return (
    <section
      aria-labelledby={TITLE_ID}
      className="flex flex-col gap-3 rounded-md border border-status-warn-fg/40 p-4 text-lg print:hidden"
    >
      <h2 id={TITLE_ID} tabIndex={-1} className="text-xl font-semibold">
        {intl.formatMessage(lm.title)}
      </h2>
      {status ? (
        <p
          role={status.problem ? 'alert' : 'status'}
          className={status.problem ? 'text-destructive' : 'text-status-ok-fg'}
        >
          {status.text}
        </p>
      ) : null}
      {list.length ? (
        <>
          <p>{intl.formatMessage(lm.lead)}</p>
          {deadline ? (
            <p className="text-base">
              {intl.formatMessage(lmv.dueOn, {
                date: intl.formatDate(`${deadline}T12:00:00Z`, { dateStyle: 'medium', timeZone: 'UTC' }),
              })}
            </p>
          ) : null}
          <ul className="flex flex-col gap-4">
            {list.map((r) => {
              const name = requestName(intl, r)
              return (
                <li
                  key={r.paperId ?? r.itemId}
                  className="flex flex-col gap-2 border-t pt-3 first:border-t-0 first:pt-0"
                >
                  <p dir="auto" className="font-medium">
                    {name}
                  </p>
                  {r.purpose ? (
                    <p className="text-base text-muted-foreground">{intl.formatMessage(pm[r.purpose])}</p>
                  ) : null}
                  <div className="flex flex-wrap gap-2">
                    <Button
                      variant="outline"
                      className={button}
                      disabled={busy}
                      aria-label={intl.formatMessage(lmv.declineNamed, { name })}
                      onClick={() => {
                        setDeclining(r)
                      }}
                    >
                      {intl.formatMessage(lm.decline)}
                    </Button>
                    <Button
                      className={button}
                      disabled={busy}
                      aria-label={intl.formatMessage(lmv.includeNamed, { name })}
                      onClick={() => void decide(r, true)}
                    >
                      {intl.formatMessage(lm.include)}
                    </Button>
                  </div>
                </li>
              )
            })}
          </ul>
        </>
      ) : null}
      {declining ? (
        <AlertDialog
          open
          onOpenChange={(open) => {
            if (!open) setDeclining(undefined)
          }}
          title={intl.formatMessage(lmv.declineTitle, { name: requestName(intl, declining) })}
          description={intl.formatMessage(declining.paperId ? lm.declinePaperText : lm.declineItemText)}
          confirmLabel={intl.formatMessage(lm.decline)}
          cancelLabel={intl.formatMessage(lm.cancel)}
          onConfirm={() => {
            const r = declining
            setDeclining(undefined)
            void decide(r, false)
          }}
        />
      ) : null}
    </section>
  )
}
