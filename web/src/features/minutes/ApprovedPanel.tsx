import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ShieldCheck } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getMinutesQueryKey,
  listMinutesApprovalsOptions,
  listMinutesCorrectionsOptions,
  listMinutesRecordsOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import {
  checkMinutesRecord,
  decideMinutesCorrection,
  proposeMinutesCorrection,
  retryMinutesRecord,
} from '@/api/generated/sdk.gen'
import type {
  MinutesApprovalResponse,
  MinutesCorrectionResponse,
  MinutesRecordCheckResponse,
  MinutesRecordResponse,
} from '@/api/generated/types.gen'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Select, Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { FormDialog } from '@/features/forums/FormDialog'
import { CopyViewer, type CopySource } from './CopyViewer'
import { POLL_MS, useMinutesWrite } from './data'
import { mn, mnv } from './messages'
import {
  day,
  factorWords,
  fieldProblems,
  fingerprint,
  itemName,
  redactedWords,
  refusal,
  type Minutes,
  type MinutesItem,
} from './words'

const MAX_TEXT = 4_000
const MAX_REASON = 500
const MAX_NOTE = 1_000
const big = 'min-h-11 text-base'

/**
 * The approved minutes (flow AG4): "Read the minutes" (the reader's own copy of the current record version), the
 * record with its fingerprint and the approval stamps with "Check", the earlier versions, and the corrections (proposed
 * by a secretary, approved or declined by the chair with a fresh step-up; E06 T8). While a record version is made, the
 * page asks again every two seconds. When what the reader reads could not be made, it says so (the secretary has been
 * told); a secretary not recused gets "Make the record again".
 */
export function ApprovedPanel({
  page,
  items,
  meetingName,
  timeZone,
}: {
  page: Minutes
  items: MinutesItem[]
  meetingName: string
  timeZone?: string
}) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const meetingId = page.meetingId
  const records = useQuery({
    ...listMinutesRecordsOptions({ path: { meetingId } }),
    retry: false,
    refetchInterval: (q) => (q.state.data?.some((r) => r.state === 'PENDING') ? POLL_MS : false),
  })
  const approvals = useQuery({ ...listMinutesApprovalsOptions({ path: { meetingId } }), retry: false })
  const [reading, setReading] = useState<CopySource>()
  const versions = records.data ?? []
  const current = versions.find((r) => r.current) ?? versions.find((r) => r.state === 'READY')
  const pending = versions.find((r) => r.state === 'PENDING')
  // A version made (or failed again) changes what the secretary may do: read the page again once it settles.
  const wasPending = useRef(false)
  useEffect(() => {
    if (wasPending.current && !pending) {
      void queryClient.invalidateQueries({ queryKey: getMinutesQueryKey({ path: { meetingId } }) })
    }
    wasPending.current = Boolean(pending)
  }, [pending, queryClient, meetingId])
  // What the reader reads could not be made: the record, or their own copy of it.
  const failed = !current?.file && versions.some((r) => r.failed)
  return (
    <div className="flex flex-col gap-5 text-lg">
      <div className="flex flex-wrap items-center gap-3">
        <Button
          className={big}
          onClick={() => {
            setReading({ kind: 'own' })
          }}
        >
          {intl.formatMessage(mn.readMinutes)}
        </Button>
        {page.correctedAt ? (
          <Badge tone="neutral">
            {intl.formatMessage(mnv.correctedOn, { date: day(intl, page.correctedAt, timeZone) })}
          </Badge>
        ) : null}
      </div>
      <section aria-labelledby="minutes-record" className="flex flex-col gap-3">
        <h2 id="minutes-record" className="text-xl font-semibold">
          {intl.formatMessage(mn.recordTitle)}
        </h2>
        <p role="status">
          {pending
            ? intl.formatMessage(mn.recordPending)
            : page.mayRetryRecord
              ? intl.formatMessage(mn.recordFailedOfficer)
              : failed
                ? intl.formatMessage(mn.recordFailed)
                : ''}
        </p>
        {page.mayRetryRecord && !pending ? <RetryRecord meetingId={meetingId} /> : null}
        {current?.file ? <RecordLine meetingId={meetingId} record={current} approvals={approvals.data ?? []} /> : null}
        {versions.filter((r) => r.state === 'READY').length > 1 ? (
          <div className="flex flex-col gap-2">
            <h3 className="text-lg font-semibold">{intl.formatMessage(mn.versions)}</h3>
            <ul className="flex flex-col gap-2">
              {versions
                .filter((r) => r.state === 'READY')
                .map((r) => (
                  <li key={r.version} className="flex flex-wrap items-center gap-3">
                    <span>
                      {intl.formatMessage(mnv.versionLine, {
                        version: r.version,
                        date: day(intl, r.builtAt ?? r.createdAt, timeZone),
                      })}
                      {r.current ? <span className="ms-1">{intl.formatMessage(mn.current)}</span> : null}
                    </span>
                    <Button
                      variant="outline"
                      className={big}
                      onClick={() => {
                        setReading({ kind: 'record', version: r.version })
                      }}
                    >
                      {intl.formatMessage(mnv.readVersion, { version: r.version })}
                    </Button>
                  </li>
                ))}
            </ul>
          </div>
        ) : null}
      </section>
      <Corrections page={page} items={items} timeZone={timeZone} />
      {reading ? (
        <CopyViewer
          meetingId={meetingId}
          source={reading}
          title={
            reading.kind === 'record'
              ? `${meetingName} — ${intl.formatMessage(mnv.readVersion, { version: reading.version })}`
              : `${intl.formatMessage(mn.title)} — ${meetingName}`
          }
          onClose={() => {
            setReading(undefined)
          }}
        />
      ) : null}
    </div>
  )
}

/** "Make the record again" (a secretary not recused): the failed record and readers' copies are asked for again. */
function RetryRecord({ meetingId }: { meetingId: string }) {
  const intl = useIntl()
  const write = useMinutesWrite()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const [refused, setRefused] = useState<string>()
  const retry = async () => {
    setBusy(true)
    setRefused(undefined)
    try {
      await write(['retry-record', meetingId], (key) =>
        retryMinutesRecord({ path: { meetingId }, headers: { 'Idempotency-Key': key }, throwOnError: true }),
      )
      toast.show(intl.formatMessage(mn.recordRetried), 'success')
    } catch (problem) {
      setRefused(refusal(intl, problem))
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="flex flex-col gap-2">
      <div>
        <Button className={big} disabled={busy} onClick={() => void retry()}>
          {intl.formatMessage(mn.retryRecord)}
        </Button>
      </div>
      {refused ? (
        <p role="alert" className="text-destructive">
          {refused}
        </p>
      ) : null}
    </div>
  )
}

/** The current record version: fingerprint, what the reader's copy leaves out, the stamps and "Check". */
function RecordLine({
  meetingId,
  record,
  approvals,
}: {
  meetingId: string
  record: MinutesRecordResponse
  approvals: MinutesApprovalResponse[]
}) {
  const intl = useIntl()
  const [result, setResult] = useState<MinutesRecordCheckResponse>()
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)
  const resultRef = useRef<HTMLDivElement>(null)
  const file = record.file
  const check = async () => {
    setBusy(true)
    setFailed(false)
    try {
      const { data } = await checkMinutesRecord({ path: { meetingId, version: record.version }, throwOnError: true })
      setResult(data)
    } catch {
      setFailed(true)
    } finally {
      setBusy(false)
      requestAnimationFrame(() => resultRef.current?.focus())
    }
  }
  return (
    <div className="flex flex-col gap-2">
      {file ? (
        <p>
          <span className="font-mono">
            {intl.formatMessage(mnv.recordLine, { version: record.version, fingerprint: fingerprint(file.sha256) })}
          </span>
          {file.compliant ? null : (
            <span className="ms-1">{intl.formatMessage(mnv.paren, { text: intl.formatMessage(mn.notCompliant) })}</span>
          )}
        </p>
      ) : null}
      {record.redacted.length ? (
        <p>{intl.formatMessage(mnv.yourCopyLeavesOut, { items: redactedWords(intl, record.redacted) })}</p>
      ) : null}
      <ul className="flex flex-col gap-2">
        {approvals.map((a) => (
          <li key={a.id} className="flex items-start gap-2">
            <ShieldCheck aria-hidden="true" className="mt-1 size-5 shrink-0 text-status-ok-fg" />
            <span>{stampWords(intl, a)}</span>
          </li>
        ))}
      </ul>
      <div>
        <Button variant="outline" className={big} disabled={busy} onClick={() => void check()}>
          {intl.formatMessage(mn.check)}
        </Button>
      </div>
      <div ref={resultRef} tabIndex={-1} role="status" className="outline-none">
        {failed ? <p>{intl.formatMessage(mn.failed)}</p> : null}
        {result ? (
          result.matches ? (
            <p className="text-status-ok-fg">{intl.formatMessage(mn.checkMatches)}</p>
          ) : (
            <div className="text-destructive">
              <p>{intl.formatMessage(mn.checkProblems)}</p>
              <ul className="list-disc ps-6">
                {result.problems.map((p) => (
                  <li key={p.code}>{checkWords(intl, p.code)}</li>
                ))}
              </ul>
            </div>
          )
        ) : null}
      </div>
    </div>
  )
}

function checkWords(intl: ReturnType<typeof useIntl>, code: string): string {
  switch (code) {
    case 'DOCUMENT_CHANGED':
    case 'DOCUMENT_MISSING':
    case 'FILE_CHANGED':
    case 'FILE_MISSING':
    case 'NOT_READY':
      return intl.formatMessage(mn[code])
    default:
      return intl.formatMessage(mn.checkOther)
  }
}

/** "Approved by Dr F. Karim, Chair — authenticated with password and authenticator, 1 minute before". */
function stampWords(intl: ReturnType<typeof useIntl>, a: MinutesApprovalResponse): string {
  const values = { name: a.by.name, role: intl.formatMessage(mn[a.role]), factors: factorWords(intl, a) }
  if (a.kind === 'CORRECTION') return intl.formatMessage(mnv.correctionApprovedBy, values)
  return a.stepUpAgeSeconds != null
    ? intl.formatMessage(mnv.approvedByAge, { ...values, minutes: Math.floor(a.stepUpAgeSeconds / 60) })
    : intl.formatMessage(mnv.approvedBy, values)
}

/** The corrections after approval: the list, "Propose a correction", and the approver's answer with a step-up. */
function Corrections({ page, items, timeZone }: { page: Minutes; items: MinutesItem[]; timeZone?: string }) {
  const intl = useIntl()
  const corrections = useQuery({
    ...listMinutesCorrectionsOptions({ path: { meetingId: page.meetingId } }),
    retry: false,
  })
  const [proposing, setProposing] = useState(false)
  const [said, setSaid] = useState<string>()
  const saidRef = useRef<HTMLParagraphElement>(null)
  const list = corrections.data?.corrections ?? []
  const done = (text: string) => {
    setProposing(false)
    setSaid(text)
    requestAnimationFrame(() => saidRef.current?.focus())
  }
  return (
    <section aria-labelledby="minutes-corrections" className="flex flex-col gap-3">
      <h2 id="minutes-corrections" className="text-xl font-semibold">
        {intl.formatMessage(mn.correctionsTitle)}
      </h2>
      <p ref={saidRef} tabIndex={-1} role="status" className="outline-none">
        {said}
      </p>
      {corrections.isSuccess && !list.length ? <p>{intl.formatMessage(mn.correctionsNone)}</p> : null}
      {list.length ? (
        <ul className="flex flex-col gap-3">
          {list.map((c) => (
            <Correction
              key={c.id}
              page={page}
              correction={c}
              mayDecide={Boolean(corrections.data?.mayDecide)}
              timeZone={timeZone}
              onDone={done}
            />
          ))}
        </ul>
      ) : null}
      {page.mayProposeCorrection || corrections.data?.mayPropose ? (
        <div>
          <Button
            variant="outline"
            className={big}
            onClick={() => {
              setProposing(true)
            }}
          >
            {intl.formatMessage(mn.proposeCorrection)}
          </Button>
        </div>
      ) : null}
      {proposing ? (
        <ProposeDialog
          page={page}
          items={items}
          onDone={done}
          onClose={() => {
            setProposing(false)
          }}
        />
      ) : null}
    </section>
  )
}

function Correction({
  page,
  correction: c,
  mayDecide,
  timeZone,
  onDone,
}: {
  page: Minutes
  correction: MinutesCorrectionResponse
  mayDecide: boolean
  timeZone?: string
  onDone: (said: string) => void
}) {
  const intl = useIntl()
  const write = useMinutesWrite()
  const withStepUp = useWithStepUp()
  const toast = useToast()
  const [declining, setDeclining] = useState(false)
  const [refused, setRefused] = useState<string>()
  const [busy, setBusy] = useState(false)
  const part = c.itemNumber ? intl.formatMessage(mnv.item, { number: c.itemNumber }) : intl.formatMessage(mn.inGeneral)
  const decide = async (decision: 'APPROVE' | 'DECLINE', note?: string): Promise<string | undefined> => {
    try {
      const body = { decision, ...(note ? { note } : {}) }
      await write([c.id, body], (key) =>
        withStepUp(() =>
          decideMinutesCorrection({
            path: { meetingId: page.meetingId, correctionId: c.id },
            headers: { 'Idempotency-Key': key },
            body,
            throwOnError: true,
          }),
        ),
      )
      const text = intl.formatMessage(decision === 'APPROVE' ? mn.correctionApproved : mn.correctionDeclined)
      toast.show(text, 'success')
      onDone(text)
      return undefined
    } catch (problem) {
      return refusal(intl, problem)
    }
  }
  const state = c.state === 'APPROVED' ? intl.formatMessage(mn.APPROVED) : intl.formatMessage(mn[c.state])
  return (
    <li className="flex flex-col gap-2 rounded-md border p-3">
      <p className="flex flex-wrap items-center gap-2 text-base text-muted-foreground">
        <Badge tone={c.state === 'APPROVED' ? 'ok' : c.state === 'DECLINED' ? 'neutral' : 'warn'}>{state}</Badge>
        {intl.formatMessage(mnv.correctionLine, {
          part,
          name: c.proposedBy.name,
          date: day(intl, c.proposedAt, timeZone),
        })}
      </p>
      {c.withheld ? (
        <p>{intl.formatMessage(mnv.correctionWithheld, { number: c.itemNumber ?? '?' })}</p>
      ) : (
        <>
          <p dir="auto" className="whitespace-pre-line">
            {c.text}
          </p>
          {c.reason ? (
            <p className="text-base">{intl.formatMessage(mnv.correctionReasonLine, { reason: c.reason })}</p>
          ) : null}
        </>
      )}
      {c.decidedBy && c.decidedAt ? (
        <p className="text-base">
          {c.note
            ? intl.formatMessage(mnv.correctionDecidedNote, {
                state,
                name: c.decidedBy.name,
                date: day(intl, c.decidedAt, timeZone),
                note: c.note,
              })
            : intl.formatMessage(mnv.correctionDecided, {
                state,
                name: c.decidedBy.name,
                date: day(intl, c.decidedAt, timeZone),
              })}
        </p>
      ) : null}
      {mayDecide && c.state === 'PROPOSED' ? (
        <div className="flex flex-wrap justify-end gap-2">
          <Button
            variant="outline"
            className={big}
            disabled={busy}
            onClick={() => {
              setDeclining(true)
            }}
          >
            {intl.formatMessage(mn.declineCorrection)}
          </Button>
          <Button
            className={big}
            disabled={busy}
            onClick={() => {
              setBusy(true)
              setRefused(undefined)
              void decide('APPROVE').then((r) => {
                setRefused(r)
                setBusy(false)
              })
            }}
          >
            {intl.formatMessage(mn.approveCorrection)}
          </Button>
        </div>
      ) : null}
      {refused ? (
        <p role="alert" className="text-base text-destructive">
          {refused}
        </p>
      ) : null}
      {declining ? (
        <DeclineDialog
          onSubmit={(note) => decide('DECLINE', note)}
          onClose={() => {
            setDeclining(false)
          }}
        />
      ) : null}
    </li>
  )
}

function DeclineDialog({
  onSubmit,
  onClose,
}: {
  onSubmit: (note?: string) => Promise<string | undefined>
  onClose: () => void
}) {
  const intl = useIntl()
  const [note, setNote] = useState('')
  return (
    <FormDialog
      large
      destructive
      title={intl.formatMessage(mn.declineTitle)}
      description={intl.formatMessage(mn.approveStepUp)}
      submitLabel={intl.formatMessage(mn.declineConfirm)}
      cancelLabel={intl.formatMessage(mn.cancel)}
      onClose={onClose}
      onSubmit={() => onSubmit(note.trim() || undefined)}
    >
      <Field label={intl.formatMessage(mn.declineNote)} optionalLabel={intl.formatMessage(mn.optional)}>
        {(p) => (
          <Textarea
            {...p}
            rows={3}
            maxLength={MAX_NOTE}
            className="text-base"
            value={note}
            onChange={(e) => {
              setNote(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}

/** "Propose a correction": which part, the correction and why; the chair approves it (flow AG4). */
function ProposeDialog({
  page,
  items,
  onDone,
  onClose,
}: {
  page: Minutes
  items: MinutesItem[]
  onDone: (said: string) => void
  onClose: () => void
}) {
  const intl = useIntl()
  const write = useMinutesWrite()
  const [itemId, setItemId] = useState('')
  const [text, setText] = useState('')
  const [reason, setReason] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  return (
    <FormDialog
      large
      side
      title={intl.formatMessage(mn.proposeCorrection)}
      description={intl.formatMessage(mn.proposeLead)}
      submitLabel={intl.formatMessage(mn.propose)}
      cancelLabel={intl.formatMessage(mn.cancel)}
      onClose={onClose}
      onSubmit={async () => {
        const missing: Record<string, string> = {}
        if (!text.trim()) missing.text = intl.formatMessage(mn.correctionRequired)
        if (!reason.trim()) missing.reason = intl.formatMessage(mn.reasonRequired)
        setErrors(missing)
        if (Object.keys(missing).length) return undefined
        try {
          const body = { text: text.trim(), reason: reason.trim(), ...(itemId ? { itemId } : {}) }
          await write(body, (key) =>
            proposeMinutesCorrection({
              path: { meetingId: page.meetingId },
              headers: { 'Idempotency-Key': key },
              body,
              throwOnError: true,
            }),
          )
          onDone(intl.formatMessage(mn.proposed))
          return undefined
        } catch (problem) {
          const fields = fieldProblems(intl, problem)
          setErrors(fields)
          return Object.keys(fields).length ? undefined : refusal(intl, problem)
        }
      }}
    >
      <Field label={intl.formatMessage(mn.whichPart)}>
        {(p) => (
          <Select
            {...p}
            className="min-h-11 text-base"
            value={itemId}
            onChange={(e) => {
              setItemId(e.target.value)
            }}
          >
            <option value="">{intl.formatMessage(mn.inGeneral)}</option>
            {items.map((i) => (
              <option key={i.itemId} value={i.itemId}>
                {itemName(intl, i)}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Field label={intl.formatMessage(mn.correctionText)} required error={errors.text}>
        {(p) => (
          <Textarea
            {...p}
            name="text"
            rows={5}
            maxLength={MAX_TEXT}
            className="text-base"
            value={text}
            onChange={(e) => {
              setText(e.target.value)
            }}
          />
        )}
      </Field>
      <Field label={intl.formatMessage(mn.correctionReason)} required error={errors.reason}>
        {(p) => (
          <Textarea
            {...p}
            name="reason"
            rows={2}
            maxLength={MAX_REASON}
            className="text-base"
            value={reason}
            onChange={(e) => {
              setReason(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}
