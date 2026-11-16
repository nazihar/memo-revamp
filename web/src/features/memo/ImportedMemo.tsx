import { useQuery } from '@tanstack/react-query'
import { Download, Eye } from 'lucide-react'
import { useState } from 'react'
import { useIntl, type IntlShape, type MessageDescriptor } from 'react-intl'
import { getImportBatchOptions, listClassificationLevelsOptions } from '@/api/generated/@tanstack/react-query.gen'
import { correctLegacyMemo, reclassifyLegacyMemo } from '@/api/generated/sdk.gen'
import type {
  LegacyCorrectionRequest,
  LegacyMemoView,
  LegacyOriginalView,
  LegacyPersonView,
  MemoView,
} from '@/api/generated/types.gen'
import { isProblem, PROBLEM, type Problem } from '@/api/http'
import { PageHeader } from '@/components/page/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input, Select, Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { holds, PERMISSION, useMyAccess } from '@/features/access/access'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { FilePreviewDialog } from './FilePreviewDialog'
import { FollowToggle } from './FollowToggle'
import { lm, lmv } from './legacy-messages'
import { MemoAccess } from './MemoAccess'
import { MemoDetails } from './MemoDetails'
import { MemoLinks } from './MemoLinks'
import { referenceOf } from './memo-fields'
import { statusLabels } from './memo-messages'
import { statusTone } from './memo-status'

type LegacyField = LegacyCorrectionRequest['field']
const CORRECTABLE: Exclude<LegacyField, 'CLASSIFICATION'>[] = [
  'SUBJECT',
  'RAISED_ON',
  'DECIDED_ON',
  'CLOSED_ON',
  'INITIATOR_NAME',
  'LEGACY_UNIT',
  'LEGACY_TYPE',
]
const DATES = new Set<LegacyField>(['RAISED_ON', 'DECIDED_ON', 'CLOSED_ON'])
const fieldLabels: Record<LegacyField, MessageDescriptor> = {
  SUBJECT: lm.fieldSUBJECT,
  RAISED_ON: lm.fieldRAISED_ON,
  DECIDED_ON: lm.fieldDECIDED_ON,
  CLOSED_ON: lm.fieldCLOSED_ON,
  INITIATOR_NAME: lm.fieldINITIATOR_NAME,
  LEGACY_UNIT: lm.fieldLEGACY_UNIT,
  LEGACY_TYPE: lm.fieldLEGACY_TYPE,
  CLASSIFICATION: lm.fieldCLASSIFICATION,
}
const errors: Record<string, MessageDescriptor> = {
  DATE_INVALID: lm.errDATE_INVALID,
  DATE_FUTURE: lm.errDATE_FUTURE,
  DATE_ORDER: lm.errDATE_ORDER,
  VALUE_REQUIRED: lm.errVALUE_REQUIRED,
  UNCHANGED: lm.errUNCHANGED,
  REQUIRED: lm.errREQUIRED,
  TOO_LONG: lm.errTOO_LONG,
  USE_RECLASSIFY: lm.errUSE_RECLASSIFY,
  CLASSIFICATION_NOT_HIGHER: lm.errCLASSIFICATION_NOT_HIGHER,
  'above-clearance': lm.errAboveClearance,
  NOT_IMPORTED: lm.errNOT_IMPORTED,
}

/** A legacy date (`yyyy-mm-dd`, a calendar day in the batch's time zone) for people; never shifted by a time zone. */
function day(intl: IntlShape, value: string | null | undefined): string {
  return value ? intl.formatDate(`${value}T00:00:00Z`, { dateStyle: 'medium', timeZone: 'UTC' }) : ''
}

/** The value a field has now: the latest correction of it, else as given (design E06 §11, as built T4). */
function currentValue(view: MemoView, legacy: LegacyMemoView, field: LegacyField): string {
  if (field === 'SUBJECT') return view.subject
  const corrected = [...legacy.corrections].reverse().find((c) => c.field === field)
  if (corrected) return corrected.after ?? ''
  switch (field) {
    case 'RAISED_ON':
      return legacy.raisedOn
    case 'DECIDED_ON':
      return legacy.decidedOn ?? ''
    case 'CLOSED_ON':
      return legacy.closedOn ?? ''
    case 'INITIATOR_NAME':
      return legacy.initiator.name
    case 'LEGACY_UNIT':
      return legacy.unit
    case 'LEGACY_TYPE':
      return legacy.type
    default:
      return ''
  }
}

function personText(intl: IntlShape, person: LegacyPersonView): string {
  return person.user
    ? intl.formatMessage(lmv.linkedPerson, { name: person.name, user: person.user.name })
    : intl.formatMessage(lmv.unlinkedPerson, { name: person.name })
}

function errorText(intl: IntlShape, code: string | undefined): string {
  const message = code ? errors[code] : undefined
  return message ? intl.formatMessage(message) : intl.formatMessage(lm.errOther)
}

/**
 * The page of an imported memo (ux-review O1, design E06 §11): an "Imported" banner, its legacy details as given, the
 * legacy approval history shown as history, the originals (TIFF is download-only), no final record, and the corrections.
 * Holders of `memo.import` correct a detail or raise the classification, each with a reason and the step-up. There is
 * no route, workflow box, note sheet or action bar.
 */
export function ImportedMemo({
  view,
  legacy,
  onReplace,
  onProblem,
}: {
  view: MemoView
  legacy: LegacyMemoView
  onReplace: (next: MemoView) => void
  onProblem: (problem: Problem | undefined) => void
}) {
  const intl = useIntl()
  const toast = useToast()
  const access = useMyAccess()
  const importer = holds(access.data, PERMISSION.memoImport)
  const batch = useQuery({
    ...getImportBatchOptions({ path: { id: legacy.batchId } }),
    enabled: importer,
    retry: false,
  })
  const allowed = new Set(view.allowedActions)
  const [dialog, setDialog] = useState<'correct' | 'reclassify'>()
  const [previewing, setPreviewing] = useState<LegacyOriginalView>()
  const date = intl.formatDate(legacy.importedAt, { dateStyle: 'medium' })
  const source = <bdi dir="ltr">{legacy.sourceCode}</bdi>

  const asGiven = (value: string, given: string | null | undefined) =>
    given && given !== value ? intl.formatMessage(lmv.asGiven, { value, given }) : value
  const rows: [MessageDescriptor, React.ReactNode][] = [
    [lm.legacyNumber, <bdi dir="ltr">{legacy.legacyNumber}</bdi>],
    [lm.type, asGiven(view.type.name, legacy.type)],
    [lm.classification, asGiven(view.classification.name, legacy.classification)],
    [lm.unit, asGiven(view.originUnit?.name ?? '', legacy.unit)],
    [lm.raisedOn, day(intl, currentValue(view, legacy, 'RAISED_ON'))],
    ...(currentValue(view, legacy, 'DECIDED_ON')
      ? [[lm.decidedOn, day(intl, currentValue(view, legacy, 'DECIDED_ON'))] as [MessageDescriptor, string]]
      : []),
    ...(currentValue(view, legacy, 'CLOSED_ON')
      ? [[lm.closedOn, day(intl, currentValue(view, legacy, 'CLOSED_ON'))] as [MessageDescriptor, string]]
      : []),
    [lm.status, asGiven(intl.formatMessage(statusLabels[view.status]), legacy.status)],
    [lm.initiator, personText(intl, { ...legacy.initiator, name: currentValue(view, legacy, 'INITIATOR_NAME') })],
  ]

  return (
    <section aria-labelledby="memo-title" data-testid="imported-memo">
      <PageHeader
        title={<span id="memo-title">{view.subject}</span>}
        description={`${referenceOf(intl, view.reference)} · ${view.type.name} · ${view.classification.name}`}
        status={
          <>
            <Badge tone={statusTone[view.status]}>{intl.formatMessage(statusLabels[view.status])}</Badge>
            <Badge tone="neutral">
              <span data-testid="imported-badge">{intl.formatMessage(lm.imported)}</span>
            </Badge>
          </>
        }
        actions={
          <>
            <FollowToggle memoId={view.id} />
            {allowed.has('CORRECT_LEGACY') ? (
              <Button
                variant="outline"
                onClick={() => {
                  setDialog('correct')
                }}
              >
                {intl.formatMessage(lm.correct)}
              </Button>
            ) : null}
            {allowed.has('RECLASSIFY') ? (
              <Button
                variant="outline"
                onClick={() => {
                  setDialog('reclassify')
                }}
              >
                {intl.formatMessage(lm.reclassify)}
              </Button>
            ) : null}
          </>
        }
      />
      <div
        role="note"
        className="mb-6 rounded-md border border-status-warn-fg/30 bg-status-warn-bg p-4 text-sm text-status-warn-fg"
      >
        <p className="font-medium">
          {batch.data
            ? intl.formatMessage(lmv.importedFromBatch as MessageDescriptor, { source, date, batch: batch.data.name })
            : intl.formatMessage(lmv.importedFrom as MessageDescriptor, { source, date })}
        </p>
        <p>{intl.formatMessage(lm.notVerified)}</p>
      </div>
      <div className="grid gap-6 md:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="flex flex-col gap-6">
          <Section title={intl.formatMessage(lm.details)} id="legacy-details">
            <dl className="grid gap-x-4 gap-y-2 text-sm sm:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
              {rows.map(([label, value]) => (
                <div key={label.id} className="contents">
                  <dt className="text-muted-foreground">{intl.formatMessage(label)}</dt>
                  <dd className="break-words">{value}</dd>
                </div>
              ))}
            </dl>
          </Section>
          <MemoDetails memo={view} />
          <Section title={intl.formatMessage(lm.approvals)} id="legacy-approvals">
            {legacy.approvals.length === 0 ? (
              <p className="text-sm text-muted-foreground">{intl.formatMessage(lm.approvalsNone)}</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr>
                      {[lm.colOrder, lm.colName, lm.colPosition, lm.colDecision, lm.colDate, lm.colRemark].map((h) => (
                        <th key={h.id} scope="col" className="py-2 pe-3 text-start font-medium">
                          {intl.formatMessage(h)}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {legacy.approvals.map((a) => (
                      <tr key={a.ordinal} className="border-t align-top">
                        <td className="py-2 pe-3 tabular-nums">{intl.formatNumber(a.ordinal)}</td>
                        <td className="py-2 pe-3">
                          {a.person.user
                            ? intl.formatMessage(lmv.linkedPerson, { name: a.person.name, user: a.person.user.name })
                            : a.person.name}
                        </td>
                        <td className="py-2 pe-3">{a.position ?? ''}</td>
                        <td className="py-2 pe-3">{a.decision}</td>
                        <td className="py-2 pe-3">{day(intl, a.decidedOn)}</td>
                        <td className="py-2 whitespace-pre-line">{a.remark ?? ''}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Section>
          <Section title={intl.formatMessage(lm.originals)} id="legacy-originals">
            <ul className="flex flex-col gap-2">
              {legacy.originals.map((o) => (
                <Original
                  key={o.id}
                  original={o}
                  onPreview={() => {
                    setPreviewing(o)
                  }}
                />
              ))}
            </ul>
          </Section>
          <MemoLinks
            memo={view}
            onChanged={onReplace}
            onProblem={onProblem}
            onDone={(message) => {
              toast.show(message, 'success')
            }}
          />
        </div>
        <div className="flex flex-col gap-6">
          <Section title={intl.formatMessage(lm.finalRecord)} id="legacy-final-record">
            <p className="text-sm">{intl.formatMessage(lm.noFinalRecord)}</p>
          </Section>
          <Section
            title={intl.formatMessage(lmv.corrections, { count: legacy.corrections.length })}
            id="legacy-corrections"
          >
            <ul className="flex flex-col gap-2 text-sm">
              {legacy.corrections.map((c, i) => (
                <li key={`${c.correctedAt}-${String(i)}`}>
                  {intl.formatMessage(lmv.correction as MessageDescriptor, {
                    date: intl.formatDate(c.correctedAt, { dateStyle: 'medium' }),
                    field: intl.formatMessage(fieldLabels[c.field]),
                    before: (
                      <bdi
                        dir={DATES.has(c.field) ? 'ltr' : undefined}
                        className={DATES.has(c.field) ? 'whitespace-nowrap' : undefined}
                      >
                        {c.before ?? ''}
                      </bdi>
                    ),
                    after: (
                      <bdi
                        dir={DATES.has(c.field) ? 'ltr' : undefined}
                        className={DATES.has(c.field) ? 'whitespace-nowrap' : undefined}
                      >
                        {c.after ?? ''}
                      </bdi>
                    ),
                    reason: <bdi>{c.reason}</bdi>,
                    by: c.correctedBy.name,
                  })}
                </li>
              ))}
            </ul>
          </Section>
          <MemoAccess memo={view} onProblem={onProblem} />
        </div>
      </div>
      {previewing?.fileId && previewing.versionId ? (
        <FilePreviewDialog
          fileId={previewing.fileId}
          versionId={previewing.versionId}
          title={previewing.title ?? ''}
          mediaType={previewing.mediaType ?? ''}
          onClose={() => {
            setPreviewing(undefined)
          }}
        />
      ) : null}
      {dialog === 'correct' ? (
        <CorrectDialog
          view={view}
          legacy={legacy}
          onClose={() => {
            setDialog(undefined)
          }}
          onDone={(next) => {
            setDialog(undefined)
            toast.show(intl.formatMessage(lm.corrected), 'success')
            onReplace(next)
          }}
        />
      ) : null}
      {dialog === 'reclassify' ? (
        <ReclassifyDialog
          view={view}
          onClose={() => {
            setDialog(undefined)
          }}
          onDone={(next) => {
            setDialog(undefined)
            toast.show(intl.formatMessage(lm.reclassified), 'success')
            onReplace(next)
          }}
        />
      ) : null}
    </section>
  )
}

function Section({ title, id, children }: { title: string; id: string; children: React.ReactNode }) {
  return (
    <section aria-labelledby={id} className="rounded-md border p-4">
      <h2 id={id} className="mb-3 text-base font-semibold">
        {title}
      </h2>
      {children}
    </section>
  )
}

/** Sizes as the attachments show them. */
function size(intl: IntlShape, bytes: number | null | undefined): string {
  if (bytes == null) return ''
  return bytes >= 1024 * 1024
    ? intl.formatNumber(bytes / (1024 * 1024), { style: 'unit', unit: 'megabyte', maximumFractionDigits: 1 })
    : intl.formatNumber(Math.max(1, Math.round(bytes / 1024)), { style: 'unit', unit: 'kilobyte' })
}

function Original({ original, onPreview }: { original: LegacyOriginalView; onPreview: () => void }) {
  const intl = useIntl()
  const role = intl.formatMessage(original.role === 'MAIN' ? lm.main : lm.annex)
  if (original.restricted) {
    return (
      <li className="flex flex-wrap items-center gap-2 text-sm">
        <span className="w-16 text-muted-foreground">{role}</span>
        <span>{intl.formatMessage(lm.restricted)}</span>
      </li>
    )
  }
  const title = original.title ?? ''
  const tiff = original.mediaType === 'image/tiff'
  return (
    <li className="flex flex-wrap items-center gap-2 text-sm" data-testid="legacy-original">
      <span className="w-16 text-muted-foreground">{role}</span>
      <span className="min-w-0 flex-1 truncate font-medium">
        {intl.formatMessage(lmv.originalLine as MessageDescriptor, {
          title: <bdi>{title}</bdi>,
          size: size(intl, original.sizeBytes),
        })}
      </span>
      {original.classification ? <Badge tone="warn">{original.classification.name}</Badge> : null}
      {tiff ? (
        <span className="text-xs text-muted-foreground">{intl.formatMessage(lm.noPreview)}</span>
      ) : (
        <Button variant="ghost" size="icon" aria-label={intl.formatMessage(lmv.preview, { title })} onClick={onPreview}>
          <Eye aria-hidden="true" />
        </Button>
      )}
      <Button variant="ghost" size="icon" asChild>
        <a
          href={`/api/v1/files/${original.fileId ?? ''}/versions/${original.versionId ?? ''}/content`}
          download
          aria-label={intl.formatMessage(lmv.download, { title })}
        >
          <Download aria-hidden="true" />
        </a>
      </Button>
    </li>
  )
}

function problemOf(error: unknown): Problem | undefined {
  return isProblem(error) ? error : undefined
}

/** "Correct a legacy detail": the field, the corrected value and a reason; then the step-up (design E06 §11, T4). */
function CorrectDialog({
  view,
  legacy,
  onClose,
  onDone,
}: {
  view: MemoView
  legacy: LegacyMemoView
  onClose: () => void
  onDone: (next: MemoView) => void
}) {
  const intl = useIntl()
  const withStepUp = useWithStepUp()
  const [field, setField] = useState<Exclude<LegacyField, 'CLASSIFICATION'>>('SUBJECT')
  const [value, setValue] = useState(() => currentValue(view, legacy, 'SUBJECT'))
  const [reason, setReason] = useState('')
  const [problems, setProblems] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    if (!reason.trim()) {
      setProblems({ reason: intl.formatMessage(lm.errREQUIRED) })
      return
    }
    setBusy(true)
    setProblems({})
    try {
      const next = await withStepUp(() =>
        correctLegacyMemo({
          path: { id: view.id },
          body: { field, value: value.trim() || null, reason: reason.trim() },
          throwOnError: true,
        }),
      )
      onDone(next.data)
    } catch (e) {
      const problem = problemOf(e)
      if (problem?.type === PROBLEM.stepUpRequired) return
      const found = problem?.errors ?? []
      setProblems(
        found.length > 0
          ? Object.fromEntries(found.map((f) => [f.field === 'reason' ? 'reason' : 'value', errorText(intl, f.code)]))
          : { value: errorText(intl, problem?.reason) },
      )
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
      title={intl.formatMessage(lm.correct)}
      description={intl.formatMessage(lm.correctLead)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(lm.cancel)}
          </Button>
          <Button disabled={busy} onClick={() => void submit()}>
            {intl.formatMessage(lm.confirm)}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={intl.formatMessage(lm.field)} required>
          {(p) => (
            <Select
              {...p}
              value={field}
              onChange={(e) => {
                const next = e.target.value as Exclude<LegacyField, 'CLASSIFICATION'>
                setField(next)
                setValue(currentValue(view, legacy, next))
              }}
            >
              {CORRECTABLE.map((f) => (
                <option key={f} value={f}>
                  {intl.formatMessage(fieldLabels[f])}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field
          label={intl.formatMessage(lm.value)}
          hint={field === 'DECIDED_ON' ? intl.formatMessage(lm.valueHint) : undefined}
          error={problems.value}
        >
          {(p) => (
            <Input
              {...p}
              type={DATES.has(field) ? 'date' : 'text'}
              maxLength={field === 'SUBJECT' ? 300 : 200}
              value={value}
              onChange={(e) => {
                setValue(e.target.value)
              }}
            />
          )}
        </Field>
        <Field
          label={intl.formatMessage(lm.reason)}
          hint={intl.formatMessage(lm.reasonHint)}
          required
          error={problems.reason}
        >
          {(p) => (
            <Textarea
              {...p}
              rows={3}
              maxLength={500}
              value={reason}
              onChange={(e) => {
                setReason(e.target.value)
              }}
            />
          )}
        </Field>
      </div>
    </Dialog>
  )
}

/** "Raise classification": a stricter level and a reason; then the step-up (design E06 §11, T4). */
function ReclassifyDialog({
  view,
  onClose,
  onDone,
}: {
  view: MemoView
  onClose: () => void
  onDone: (next: MemoView) => void
}) {
  const intl = useIntl()
  const withStepUp = useWithStepUp()
  const levels = useQuery(listClassificationLevelsOptions())
  const higher = (levels.data ?? []).filter((l) => l.rank > view.classification.rank)
  const [level, setLevel] = useState('')
  const [reason, setReason] = useState('')
  const [problems, setProblems] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const chosen = level !== '' ? level : (higher[0]?.id ?? '')

  const submit = async () => {
    const missing: Record<string, string> = {}
    if (!chosen) missing.level = intl.formatMessage(lm.noHigher)
    if (!reason.trim()) missing.reason = intl.formatMessage(lm.errREQUIRED)
    setProblems(missing)
    if (Object.keys(missing).length > 0) return
    setBusy(true)
    try {
      const next = await withStepUp(() =>
        reclassifyLegacyMemo({
          path: { id: view.id },
          body: { classificationId: chosen, reason: reason.trim() },
          throwOnError: true,
        }),
      )
      onDone(next.data)
    } catch (e) {
      const problem = problemOf(e)
      if (problem?.type === PROBLEM.stepUpRequired) return
      const found = problem?.errors?.[0]
      setProblems(
        found?.field === 'reason'
          ? { reason: errorText(intl, found.code) }
          : { level: errorText(intl, found?.code ?? problem?.reason) },
      )
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
      title={intl.formatMessage(lm.reclassify)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(lm.cancel)}
          </Button>
          <Button disabled={busy || higher.length === 0} onClick={() => void submit()}>
            {intl.formatMessage(lm.confirm)}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field
          label={intl.formatMessage(lm.level)}
          hint={intl.formatMessage(higher.length > 0 ? lm.levelHint : lm.noHigher)}
          required
          error={problems.level}
        >
          {(p) => (
            <Select
              {...p}
              value={chosen}
              onChange={(e) => {
                setLevel(e.target.value)
              }}
            >
              {higher.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field
          label={intl.formatMessage(lm.reason)}
          hint={intl.formatMessage(lm.reasonHint)}
          required
          error={problems.reason}
        >
          {(p) => (
            <Textarea
              {...p}
              rows={3}
              maxLength={500}
              value={reason}
              onChange={(e) => {
                setReason(e.target.value)
              }}
            />
          )}
        </Field>
      </div>
    </Dialog>
  )
}
