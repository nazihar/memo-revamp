import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState, type SyntheticEvent } from 'react'
import { useIntl } from 'react-intl'
import { getMeetingOptions } from '@/api/generated/@tanstack/react-query.gen'
import { closeAgreedActions } from '@/api/generated/sdk.gen'
import type { ActionReportResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Checkbox, Textarea } from '@/components/ui/form-controls'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { ao } from './messages'
import { officerRefusal, reasonWords } from './problems'
import { RAISING, refreshActions } from './shared'

/**
 * "Close the actions the committee agreed" (flow AM2, design §1.9): at the meeting whose agenda carries the report, the
 * officers tick the actions proposed to close that the committee agreed, and close them in one request; each is
 * verified on its own with the remark "Agreed at BRC 4/2026, item 3" (or the one given). An officer who owns or helps
 * with one of them cannot close it: the result says so, and another officer does. Shown to the report's officers while
 * the meeting runs, is held or has its minutes approved.
 */
export function CloseAgreed({ report }: { report: ActionReportResponse }) {
  const intl = useIntl()
  const terms = useTerms()
  const queryClient = useQueryClient()
  const headingId = useId()
  const officer = report.heldBack != null
  const meeting = useQuery({ ...getMeetingOptions({ path: { meetingId: report.meetingId } }), enabled: officer })
  const rows = report.sections.find((s) => s.section === 'TO_CLOSE')?.rows ?? []
  const [chosen, setChosen] = useState<string[]>([])
  const [remark, setRemark] = useState('')
  const [key, setKey] = useState(() => newIdempotencyKey())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const [said, setSaid] = useState({ text: '', times: 0 })
  const saidRef = useRef<HTMLParagraphElement>(null)
  const firstBox = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (said.times > 0) saidRef.current?.focus()
  }, [said])
  if (!officer || !rows.length || !meeting.data || !RAISING.includes(meeting.data.status)) return null

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    if (!chosen.length) {
      setError(intl.formatMessage(ao.closeChooseOne))
      firstBox.current?.focus()
      return
    }
    setError(undefined)
    setBusy(true)
    try {
      const { data } = await closeAgreedActions({
        path: { meetingId: report.meetingId },
        headers: { 'Idempotency-Key': key },
        body: { actionIds: chosen, ...(remark.trim() ? { remark: remark.trim() } : {}) },
        throwOnError: true,
      })
      setKey(newIdempotencyKey())
      const closed = data.results.filter((r) => r.closed).length
      const failed = data.results.filter((r) => !r.closed)
      const ref = new Map(rows.map((r) => [r.actionId, r.reference]))
      setSaid((s) => ({
        text: failed.length
          ? [
              intl.formatMessage(ao.closedSome, { closed, failed: failed.length }),
              ...failed.map((r) =>
                intl.formatMessage(ao.notSentLine, {
                  title: ref.get(r.actionId) ?? r.reference ?? '',
                  why: reasonWords(intl, terms, r.reason),
                }),
              ),
            ].join('\n')
          : intl.formatMessage(ao.closedAll, { count: closed }),
        times: s.times + 1,
      }))
      setChosen(failed.map((r) => r.actionId))
      await refreshActions(queryClient)
    } catch (problem) {
      setSaid((s) => ({ text: officerRefusal(intl, terms, problem), times: s.times + 1 }))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3 rounded-md border p-4 print:hidden">
      <h2 id={headingId} className="text-lg font-semibold">
        {intl.formatMessage(ao.closeAgreedTitle)}
      </h2>
      <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-3">
        <fieldset className="flex flex-col gap-1" aria-describedby={`${headingId}-hint`}>
          <legend className="sr-only">{intl.formatMessage(ao.closeAgreedTitle)}</legend>
          <p id={`${headingId}-hint`} className="text-sm text-muted-foreground">
            {intl.formatMessage(ao.closeAgreedHint)}
          </p>
          {rows.map((r, i) => (
            <label key={r.actionId} className="flex min-h-11 cursor-pointer items-center gap-3">
              <Checkbox
                ref={i === 0 ? firstBox : undefined}
                className="size-5"
                checked={chosen.includes(r.actionId)}
                aria-label={intl.formatMessage(ao.closeAgreedRow, { reference: r.reference })}
                onChange={(e) => {
                  setChosen((c) => (e.target.checked ? [...c, r.actionId] : c.filter((x) => x !== r.actionId)))
                }}
              />
              <span>
                <span className="me-2 font-medium tabular-nums">{r.reference}</span>
                {r.title}
              </span>
            </label>
          ))}
          {error ? <p className="text-sm font-medium text-destructive">{error}</p> : null}
        </fieldset>
        <Field
          label={intl.formatMessage(ao.closeAgreedRemark)}
          hint={intl.formatMessage(ao.closeAgreedRemarkHint)}
          optionalLabel={intl.formatMessage(ao.optional)}
        >
          {(p) => (
            <Textarea
              {...p}
              rows={2}
              maxLength={2000}
              value={remark}
              className="text-base"
              onChange={(e) => {
                setRemark(e.target.value)
              }}
            />
          )}
        </Field>
        <div>
          <Button type="submit" className="min-h-11 text-base" disabled={busy}>
            {intl.formatMessage(ao.closeAgreed, { count: chosen.length })}
          </Button>
        </div>
      </form>
      <p
        ref={saidRef}
        tabIndex={-1}
        role="status"
        className="whitespace-pre-line text-base font-medium focus-visible:outline-2 focus-visible:outline-ring empty:hidden"
      >
        {said.text}
      </p>
    </section>
  )
}
