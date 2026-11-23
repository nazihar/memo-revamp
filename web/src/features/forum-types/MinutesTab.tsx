import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, Copy, Plus, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getForumTypeMinutesTemplateOptions,
  getForumTypeMinutesTemplateQueryKey,
  getForumTypeVersionQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { previewForumTypeMinutesTemplate, previewResolutionNumbering } from '@/api/generated/sdk.gen'
import type {
  MinutesPolicy,
  MinutesTemplateProblemView,
  ResolutionNumberPart,
  ResolutionNumbering,
} from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { FileUpload } from '@/components/page/FileUpload'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { FilePreviewDialog } from '@/features/memo/FilePreviewDialog'
import { useTerms } from '@/features/terms/terms'
import { fmm, fmmt, fmmv } from './minutes-messages'
import { NumberInput } from './NumberInput'

const DOCX = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document,.docx'
const MAX_BYTES = 10 * 1024 * 1024
const KINDS: ResolutionNumberPart['kind'][] = [
  'TEXT',
  'COMMITTEE_CODE',
  'YEAR',
  'FINANCIAL_YEAR',
  'MEETING_NUMBER',
  'SEQUENCE',
]
const MONTHS = Array.from({ length: 12 }, (_, i) => i + 1)
/** How a year looks with 4 or 2 digits: numbers, the same in every language. */
const YEAR_SAMPLE = { long: '2026', short: '26' }
const written = (tag: string) => `{{${tag}}}`

const PART_LABELS = {
  TEXT: fmm.partTEXT,
  COMMITTEE_CODE: fmm.partCOMMITTEE_CODE,
  YEAR: fmm.partYEAR,
  FINANCIAL_YEAR: fmm.partFINANCIAL_YEAR,
  MEETING_NUMBER: fmm.partMEETING_NUMBER,
  SEQUENCE: fmm.partSEQUENCE,
}

/**
 * The committee type's Minutes tab (flow AI, design §1.8): how minutes are approved (at the next meeting, or by
 * circulation then noted), the working days members have to comment and after which the secretary is reminded, when a
 * memo moves on after the committee decides, the resolution numbers (parts, when they start again, and a live example
 * from the server's own renderer), and the minutes template (built-in or uploaded, what its check found in words,
 * deprecated tags, a preview with a sample meeting and the list of tags). Changed on a draft ([onChange] set), reviewed
 * by a second administrator like the rest of the type.
 */
export function MinutesTab({
  minutes,
  onChange,
  errors,
  typeId,
  number,
  unsaved,
}: {
  minutes: MinutesPolicy
  onChange?: (minutes: MinutesPolicy) => void
  errors: Record<string, string>
  typeId: string
  number: number
  unsaved: boolean
}) {
  const intl = useIntl()
  const terms = useTerms()
  const set = (next: Partial<MinutesPolicy>) => onChange?.({ ...minutes, ...next })
  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <fieldset disabled={!onChange} className="flex flex-col gap-5">
        <legend className="sr-only">{intl.formatMessage(fmm.tab)}</legend>
        <fieldset className="flex flex-col gap-2">
          <legend className="text-base font-semibold">{intl.formatMessage(fmm.approval)}</legend>
          <p className="text-xs text-muted-foreground">{intl.formatMessage(fmmt.approvalLead, terms)}</p>
          <Radio
            name="minutes-approval"
            checked={minutes.approval === 'AT_NEXT_MEETING'}
            label={intl.formatMessage(fmm.atNextMeeting)}
            onChange={() => {
              set({ approval: 'AT_NEXT_MEETING' })
            }}
          />
          <Radio
            name="minutes-approval"
            checked={minutes.approval === 'BY_CIRCULATION'}
            label={intl.formatMessage(fmm.byCirculation)}
            onChange={() => {
              set({ approval: 'BY_CIRCULATION' })
            }}
          />
          <div className="grid gap-4 sm:grid-cols-2">
            <Field
              label={intl.formatMessage(fmm.circulationDays)}
              hint={intl.formatMessage(fmm.circulationDaysHint)}
              error={errors['minutes/circulationWorkingDays']}
            >
              {(p) => (
                <NumberInput
                  {...p}
                  min={1}
                  max={20}
                  className="min-h-11"
                  value={minutes.circulationWorkingDays}
                  onChange={(v) => {
                    set({ circulationWorkingDays: v ?? 0 })
                  }}
                />
              )}
            </Field>
            <Field
              label={intl.formatMessage(fmm.draftDays)}
              hint={intl.formatMessage(fmm.draftDaysHint)}
              error={errors['minutes/draftWorkingDays']}
            >
              {(p) => (
                <NumberInput
                  {...p}
                  min={1}
                  max={30}
                  className="min-h-11"
                  value={minutes.draftWorkingDays}
                  onChange={(v) => {
                    set({ draftWorkingDays: v ?? 0 })
                  }}
                />
              )}
            </Field>
          </div>
        </fieldset>
        <fieldset className="flex flex-col gap-1 border-t pt-4">
          <legend className="text-base font-semibold">{intl.formatMessage(fmm.memoResumes)}</legend>
          <Radio
            name="minutes-memo"
            checked={minutes.memoResumes === 'AT_MEETING_END'}
            label={intl.formatMessage(fmm.atMeetingEnd)}
            onChange={() => {
              set({ memoResumes: 'AT_MEETING_END' })
            }}
          />
          <Radio
            name="minutes-memo"
            checked={minutes.memoResumes === 'ON_MINUTES_APPROVAL'}
            label={intl.formatMessage(fmm.onApproval)}
            onChange={() => {
              set({ memoResumes: 'ON_MINUTES_APPROVAL' })
            }}
          />
          <p className="text-xs text-muted-foreground">{intl.formatMessage(fmmt.memoResumesLead, terms)}</p>
        </fieldset>
        <Numbering
          numbering={minutes.resolutionNumbering}
          errors={errors}
          {...(onChange
            ? {
                onChange: (resolutionNumbering: ResolutionNumbering) => {
                  set({ resolutionNumbering })
                },
              }
            : {})}
        />
      </fieldset>
      <Template
        typeId={typeId}
        number={number}
        draft={Boolean(onChange)}
        unsaved={unsaved}
        error={errors['minutes/templateFileId']}
      />
    </div>
  )
}

function Radio({
  name,
  checked,
  label,
  onChange,
}: {
  name: string
  checked: boolean
  label: string
  onChange: () => void
}) {
  return (
    <label className="flex min-h-11 cursor-pointer items-center gap-3 text-sm">
      <input
        type="radio"
        name={name}
        className="size-5 shrink-0 accent-primary"
        checked={checked}
        onChange={onChange}
      />
      {label}
    </label>
  )
}

/** The resolution number's parts in order, when it starts again, and the server's example ("BRC/2026/014"). */
function Numbering({
  numbering,
  errors,
  onChange,
}: {
  numbering: ResolutionNumbering
  errors: Record<string, string>
  onChange?: (numbering: ResolutionNumbering) => void
}) {
  const intl = useIntl()
  const [sample, setSample] = useState<{ samples: string[]; problems: string[] }>()
  const set = (next: Partial<ResolutionNumbering>) => onChange?.({ ...numbering, ...next })
  const parts = numbering.parts
  const setPart = (i: number, part: ResolutionNumberPart) => {
    set({ parts: parts.map((p, j) => (i === j ? part : p)) })
  }
  const move = (from: number, to: number) => {
    const next = [...parts]
    const [item] = next.splice(from, 1)
    if (item) next.splice(to, 0, item)
    set({ parts: next })
  }
  const body = JSON.stringify(numbering)
  useEffect(() => {
    let cancelled = false
    const timer = setTimeout(() => {
      previewResolutionNumbering({ body: { numbering }, throwOnError: true })
        .then((r) => {
          if (!cancelled) {
            setSample({ samples: r.data.samples, problems: r.data.problems.map((p) => p.message ?? p.code ?? '') })
          }
        })
        .catch(() => {
          if (!cancelled) setSample(undefined)
        })
    }, 300)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
    // The body string stands for the numbering, so equal numberings do not ask again.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [body])
  const numberingErrors = Object.entries(errors).filter(([k]) => k.startsWith('minutes/resolutionNumbering'))

  return (
    <section aria-labelledby="resolution-numbers" className="flex flex-col gap-3 border-t pt-4">
      <h3 id="resolution-numbers" className="text-base font-semibold">
        {intl.formatMessage(fmm.numbers)}
      </h3>
      <p className="text-xs text-muted-foreground">{intl.formatMessage(fmm.numbersLead)}</p>
      <ol className="flex flex-col gap-2">
        {parts.map((part, i) => {
          const name = intl.formatMessage(PART_LABELS[part.kind])
          return (
            <li key={`${String(i)}-${part.kind}`} className="flex flex-wrap items-center gap-2 rounded-md border p-2">
              <span className="w-36 text-sm font-medium">{name}</span>
              {part.kind === 'TEXT' ? (
                <Input
                  aria-label={name}
                  dir="ltr"
                  className="min-h-11 w-32"
                  maxLength={20}
                  value={part.text ?? ''}
                  onChange={(e) => {
                    setPart(i, { ...part, text: e.target.value })
                  }}
                />
              ) : null}
              {part.kind === 'YEAR' ? (
                <Select
                  aria-label={intl.formatMessage(fmm.digits)}
                  className="min-h-11 w-24"
                  value={String(part.digits ?? 4)}
                  onChange={(e) => {
                    setPart(i, { ...part, digits: Number(e.target.value) })
                  }}
                >
                  <option value="4">{YEAR_SAMPLE.long}</option>
                  <option value="2">{YEAR_SAMPLE.short}</option>
                </Select>
              ) : null}
              {part.kind === 'FINANCIAL_YEAR' ? (
                <Select
                  aria-label={name}
                  className="min-h-11 w-28"
                  value={part.style ?? 'LONG'}
                  onChange={(e) => {
                    setPart(i, { ...part, style: e.target.value as 'LONG' | 'SHORT' })
                  }}
                >
                  <option value="LONG">{intl.formatMessage(fmm.styleLong)}</option>
                  <option value="SHORT">{intl.formatMessage(fmm.styleShort)}</option>
                </Select>
              ) : null}
              {part.kind === 'SEQUENCE' ? (
                <label className="flex items-center gap-2 text-sm">
                  {intl.formatMessage(fmm.digits)}
                  <Input
                    inputMode="numeric"
                    className="min-h-11 w-20"
                    value={String(part.digits ?? 3)}
                    onChange={(e) => {
                      setPart(i, { ...part, digits: Number.parseInt(e.target.value, 10) || 1 })
                    }}
                  />
                </label>
              ) : null}
              {onChange ? (
                <span className="ms-auto flex gap-1">
                  <Button
                    variant="outline"
                    size="icon"
                    disabled={i === 0}
                    aria-label={intl.formatMessage(fmmv.moveUp, { name })}
                    onClick={() => {
                      move(i, i - 1)
                    }}
                  >
                    <ArrowUp aria-hidden="true" />
                  </Button>
                  <Button
                    variant="outline"
                    size="icon"
                    disabled={i === parts.length - 1}
                    aria-label={intl.formatMessage(fmmv.moveDown, { name })}
                    onClick={() => {
                      move(i, i + 1)
                    }}
                  >
                    <ArrowDown aria-hidden="true" />
                  </Button>
                  <Button
                    variant="outline"
                    size="icon"
                    aria-label={intl.formatMessage(fmmv.remove, { name })}
                    onClick={() => {
                      set({ parts: parts.filter((_, j) => j !== i) })
                    }}
                  >
                    <X aria-hidden="true" />
                  </Button>
                </span>
              ) : null}
            </li>
          )
        })}
      </ol>
      {onChange ? (
        <div className="flex flex-wrap gap-2">
          {KINDS.map((kind) => (
            <Button
              key={kind}
              variant="outline"
              className="min-h-11"
              aria-label={intl.formatMessage(fmmv.add, { name: intl.formatMessage(PART_LABELS[kind]) })}
              onClick={() => {
                const part: ResolutionNumberPart =
                  kind === 'TEXT'
                    ? { kind, text: '/' }
                    : kind === 'YEAR'
                      ? { kind, digits: 4 }
                      : kind === 'SEQUENCE'
                        ? { kind, digits: 3 }
                        : kind === 'FINANCIAL_YEAR'
                          ? { kind, style: 'LONG' }
                          : { kind }
                set({ parts: [...parts, part] })
              }}
            >
              <Plus aria-hidden="true" />
              {intl.formatMessage(PART_LABELS[kind])}
            </Button>
          ))}
        </div>
      ) : null}
      <fieldset className="flex flex-col gap-1">
        <legend className="text-sm font-medium">{intl.formatMessage(fmm.reset)}</legend>
        {(
          [
            ['YEARLY', fmm.resetYearly],
            ['FINANCIAL_YEAR', fmm.resetFinancial],
            ['NEVER', fmm.resetNever],
          ] as const
        ).map(([reset, label]) => (
          <Radio
            key={reset}
            name="resolution-reset"
            checked={numbering.reset === reset}
            label={intl.formatMessage(label)}
            onChange={() => {
              set({ reset })
            }}
          />
        ))}
      </fieldset>
      {numbering.reset === 'FINANCIAL_YEAR' || parts.some((p) => p.kind === 'FINANCIAL_YEAR') ? (
        <Field label={intl.formatMessage(fmm.fyStart)} className="w-56">
          {(p) => (
            <Select
              {...p}
              className="min-h-11"
              value={String(numbering.fyStartMonth)}
              onChange={(e) => {
                set({ fyStartMonth: Number(e.target.value) })
              }}
            >
              {MONTHS.map((month) => (
                <option key={month} value={month}>
                  {intl.formatDate(new Date(Date.UTC(2026, month - 1, 1)), { month: 'long', timeZone: 'UTC' })}
                </option>
              ))}
            </Select>
          )}
        </Field>
      ) : null}
      <div aria-live="polite" className="rounded-md border bg-muted/30 p-3">
        <p className="text-sm font-medium">{intl.formatMessage(fmm.example)}</p>
        {sample?.samples.length ? (
          <p className="mt-1 font-mono text-base" dir="ltr">
            {sample.samples[1]
              ? intl.formatMessage(fmmv.exampleLine, { now: sample.samples[0] ?? '', next: sample.samples[1] })
              : intl.formatMessage(fmmv.exampleOne, { now: sample.samples[0] ?? '' })}
          </p>
        ) : !sample ? (
          <p className="mt-1 text-sm text-muted-foreground">{intl.formatMessage(fmm.exampleLoading)}</p>
        ) : null}
        {sample?.problems.length || numberingErrors.length ? (
          <ul className="mt-1 list-disc ps-5 text-sm text-destructive">
            {[...(sample?.problems ?? []), ...numberingErrors.map(([, m]) => m)].map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        ) : null}
      </div>
    </section>
  )
}

/**
 * The minutes template of the version: the built-in one or the upload, its problems in words ("word/document.xml,
 * paragraph 12: … is not a known tag. Did you mean {{item.notes}}?"), deprecated tags as warnings, refusals, upload on a
 * draft, a preview with the sample meeting, and the list of tags.
 */
function Template({
  typeId,
  number,
  draft,
  unsaved,
  error,
}: {
  typeId: string
  number: number
  draft: boolean
  unsaved: boolean
  error?: string
}) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const path = { path: { typeId, number: String(number) } }
  const info = useQuery(getForumTypeMinutesTemplateOptions(path))
  const [busy, setBusy] = useState(false)
  const [preview, setPreview] = useState<{ fileId: string; versionId: string }>()
  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: getForumTypeMinutesTemplateQueryKey(path) }),
      queryClient.invalidateQueries({ queryKey: getForumTypeVersionQueryKey(path) }),
    ])
  const showPreview = async () => {
    setBusy(true)
    try {
      const res = await previewForumTypeMinutesTemplate({ ...path, throwOnError: true })
      setPreview(res.data)
    } catch (problem) {
      toast.show(
        intl.formatMessage(
          isProblem(problem) && problem.reason === 'TEMPLATE_PROBLEMS' ? fmm.TEMPLATE_PROBLEMS : fmm.failed,
        ),
        'error',
      )
    } finally {
      setBusy(false)
    }
  }
  const copy = (tag: string) => {
    void navigator.clipboard.writeText(written(tag)).then(() => {
      toast.show(intl.formatMessage(fmm.copied), 'success')
    })
  }
  if (info.isPending) return <LoadingState rows={2} />
  if (info.isError) return <ErrorState onRetry={() => void info.refetch()} />
  const { template, problems, warnings, refusals, available, tags, owner } = info.data
  return (
    <section aria-labelledby="minutes-template" className="flex flex-col gap-3 border-t pt-4">
      <h3 id="minutes-template" className="text-base font-semibold">
        {intl.formatMessage(fmm.template)}
      </h3>
      <div className="flex flex-wrap items-center gap-3 text-sm">
        <span>
          {template
            ? intl.formatMessage(fmmv.file, {
                name: template.name,
                size: `${intl.formatNumber(template.sizeBytes / 1024, { maximumFractionDigits: 0 })} KB`,
              })
            : intl.formatMessage(fmm.builtIn)}
        </span>
        {draft ? (
          <Button
            variant="outline"
            className="min-h-11"
            disabled={busy || problems.length > 0}
            onClick={() => void showPreview()}
          >
            {intl.formatMessage(fmm.preview)}
          </Button>
        ) : null}
      </div>
      {error ? <p className="text-sm text-destructive">{error}</p> : null}
      {refusals.length ? (
        <div role="alert" className="rounded-md border border-destructive p-3 text-sm">
          <p className="font-medium text-destructive">{intl.formatMessage(fmm.refusals)}</p>
          <ul className="mt-1 list-disc ps-5">
            {refusals.map((r) => (
              <li key={`${r.rule}-${r.where}`}>{intl.formatMessage(fmmv.refusal, { rule: r.rule, where: r.where })}</li>
            ))}
          </ul>
        </div>
      ) : null}
      {problems.length ? (
        <div role="alert" className="rounded-md border border-destructive p-3 text-sm">
          <p className="font-medium text-destructive">{intl.formatMessage(fmm.problems)}</p>
          <ul className="mt-1 flex list-disc flex-col gap-1 ps-5">
            {problems.map((p, i) => (
              <ProblemItem key={`${p.part}-${String(p.paragraph)}-${String(i)}`} problem={p} />
            ))}
          </ul>
        </div>
      ) : null}
      {warnings.length ? (
        <div className="rounded-md border border-status-warn-fg/40 bg-status-warn-bg p-3 text-sm">
          <p className="font-medium">{intl.formatMessage(fmm.warnings)}</p>
          <ul className="mt-1 flex list-disc flex-col gap-1 ps-5">
            {warnings.map((p, i) => (
              <ProblemItem key={`${p.part}-${String(p.paragraph)}-${String(i)}`} problem={p} />
            ))}
          </ul>
        </div>
      ) : null}
      {draft ? (
        unsaved ? (
          <p className="text-sm text-muted-foreground">{intl.formatMessage(fmm.uploadSaveFirst)}</p>
        ) : (
          <FileUpload
            label={intl.formatMessage(template ? fmm.replace : fmm.upload)}
            hint={intl.formatMessage(fmm.uploadHint)}
            purpose="forum.minutes-template"
            owner={owner}
            accept={DOCX}
            maxBytes={MAX_BYTES}
            onUploaded={() => void refresh()}
          />
        )
      ) : null}
      {tags.length ? (
        <div className="text-sm">
          <p className="font-medium">{intl.formatMessage(fmm.used)}</p>
          <p className="font-mono text-xs text-muted-foreground" dir="ltr">
            {tags.map(written).join(' ')}
          </p>
        </div>
      ) : null}
      <details className="text-sm">
        <summary className="flex min-h-11 cursor-pointer items-center font-medium">
          {intl.formatMessage(fmm.tags)}
        </summary>
        <ul className="mt-2 grid gap-1 sm:grid-cols-2">
          {available.map((t) => (
            <li key={t.tag} className="flex items-center justify-between gap-2 rounded border px-2 py-1">
              <span>
                <span className="font-mono text-xs" dir="ltr">
                  {written(t.tag)}
                </span>
                <span className="block text-xs text-muted-foreground">{t.label}</span>
              </span>
              <Button
                variant="ghost"
                size="icon"
                aria-label={intl.formatMessage(fmmv.copy, { tag: written(t.tag) })}
                onClick={() => {
                  copy(t.tag)
                }}
              >
                <Copy aria-hidden="true" />
              </Button>
            </li>
          ))}
        </ul>
      </details>
      {preview ? (
        <FilePreviewDialog
          fileId={preview.fileId}
          versionId={preview.versionId}
          title={intl.formatMessage(fmm.previewTitle)}
          mediaType="application/pdf"
          onClose={() => {
            setPreview(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

function ProblemItem({ problem }: { problem: MinutesTemplateProblemView }) {
  const intl = useIntl()
  return (
    <li>
      {intl.formatMessage(fmmv.problem, { code: problem.code, tag: problem.tag ? written(problem.tag) : '' })}
      <span className="block text-xs text-muted-foreground">
        {intl.formatMessage(fmmv.where, { part: problem.part, paragraph: problem.paragraph, excerpt: problem.excerpt })}
        {problem.suggestion ? (
          <span className="ms-1">
            {intl.formatMessage(fmmv.suggestion, { suggestion: written(problem.suggestion) })}
          </span>
        ) : null}
        {problem.note ? <span className="ms-1">{problem.note}</span> : null}
      </span>
    </li>
  )
}
