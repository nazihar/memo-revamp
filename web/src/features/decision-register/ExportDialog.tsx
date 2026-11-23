import { Download, FileSpreadsheet, FileText } from 'lucide-react'
import { useId, useState } from 'react'
import { useIntl, type IntlShape } from 'react-intl'
import type { DecisionFacetsView, RegisterScopeView } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { useToast } from '@/components/ui/toast-context'
import { useTerms, type TermValues } from '@/features/terms/terms'
import { MAX_ROWS, requestExport, saveFile, type ExportFormat, type ExportOutcome } from './export'
import { drm } from './messages'
import { filtersBody, type RegisterSearch } from './register-params'
import { chipsOf, chipWords, committeeName, dayWords, todayIso } from './words'

/**
 * Export decisions (flow AT): the count the search found, the filters in words, the format with its limit, what the file
 * holds. More rows than the format holds keeps Download disabled and says how to narrow; the server's refusals (too
 * many rows, five exports an hour, a committee the reader may not export) are said in words.
 */
export function ExportDialog({
  open,
  onClose,
  search,
  q,
  total,
  capped,
  facets,
  scope,
}: {
  open: boolean
  onClose: () => void
  search: RegisterSearch
  q: string
  total: number
  capped: boolean
  facets?: DecisionFacetsView
  scope: RegisterScopeView
}) {
  const intl = useIntl()
  const terms = useTerms()
  const toast = useToast()
  const formatId = useId()
  const [format, setFormat] = useState<ExportFormat>('XLSX')
  const [busy, setBusy] = useState(false)
  const [refusal, setRefusal] = useState<ExportOutcome>()

  const tooMany = !capped && total > MAX_ROWS[format]
  const overPdf = capped && format === 'PDF'
  const blocked = tooMany || overPdf
  const blockedWords = blocked
    ? overPdf
      ? intl.formatMessage(drm.exportTooManyMore, { count: intl.formatNumber(total), format })
      : intl.formatMessage(format === 'PDF' ? drm.exportTooManyPdf : drm.exportTooManyXlsx, {
          count: intl.formatNumber(total),
        })
    : undefined
  const refusalWords = refusal ? outcomeWords(intl, refusal, format, terms) : undefined

  const download = async () => {
    setBusy(true)
    setRefusal(undefined)
    const outcome = await requestExport({ ...filtersBody(search, q), format })
    setBusy(false)
    if (outcome.kind === 'file') {
      saveFile(outcome.blob, `decision-register-${todayIso()}.${format === 'PDF' ? 'pdf' : 'xlsx'}`)
      toast.show(intl.formatMessage(drm.downloaded), 'success')
      onClose()
      return
    }
    setRefusal(outcome)
  }

  const formats: { value: ExportFormat; label: string; hint: string; icon: typeof FileText }[] = [
    {
      value: 'XLSX',
      label: intl.formatMessage(drm.formatXlsx),
      hint: intl.formatMessage(drm.formatXlsxHint),
      icon: FileSpreadsheet,
    },
    {
      value: 'PDF',
      label: intl.formatMessage(drm.formatPdf),
      hint: intl.formatMessage(drm.formatPdfHint),
      icon: FileText,
    },
  ]

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
      title={intl.formatMessage(drm.exportTitle)}
      footer={
        <>
          <Button variant="outline" className="min-h-11" onClick={onClose}>
            {intl.formatMessage(drm.cancel)}
          </Button>
          <Button
            className="min-h-11"
            disabled={blocked || busy}
            aria-describedby={blockedWords ? `${formatId}-blocked` : undefined}
            onClick={() => void download()}
          >
            <Download aria-hidden="true" />
            {intl.formatMessage(busy ? drm.preparing : drm.download)}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4 text-sm">
        <div>
          <p className="font-medium">
            {capped
              ? intl.formatMessage(drm.exportCountCapped, { count: intl.formatNumber(total) })
              : intl.formatMessage(drm.exportCount, { count: total })}
          </p>
          <p className="text-muted-foreground">{filterSummary(intl, search, q, scope, facets, terms)}</p>
        </div>
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 font-semibold">{intl.formatMessage(drm.format)}</legend>
          {formats.map((f) => (
            <div
              key={f.value}
              className="flex items-start gap-3 rounded-md border px-3 py-1 has-[:checked]:border-primary"
            >
              <input
                id={`${formatId}-${f.value}-radio`}
                type="radio"
                name={`${formatId}-format`}
                value={f.value}
                checked={format === f.value}
                aria-describedby={`${formatId}-${f.value}`}
                className="mt-3.5 size-4 accent-primary"
                onChange={() => {
                  setFormat(f.value)
                  setRefusal(undefined)
                }}
              />
              <div className="flex min-w-0 flex-1 flex-col pb-2">
                <label
                  htmlFor={`${formatId}-${f.value}-radio`}
                  className="flex min-h-11 cursor-pointer items-center gap-1.5 font-medium"
                >
                  <f.icon aria-hidden="true" className="size-4" />
                  {f.label}
                </label>
                <p id={`${formatId}-${f.value}`} className="text-muted-foreground">
                  {f.hint}
                </p>
              </div>
            </div>
          ))}
        </fieldset>
        <p>{intl.formatMessage(drm.exportAbout, terms)}</p>
        <p>{intl.formatMessage(drm.exportRestricted)}</p>
        {blockedWords ? (
          <p
            id={`${formatId}-blocked`}
            role="status"
            className="rounded-md border border-destructive/40 p-3 font-medium"
          >
            {blockedWords}
          </p>
        ) : null}
        {refusalWords ? (
          <p role="alert" className="rounded-md border border-destructive/40 p-3 font-medium">
            {refusalWords}
          </p>
        ) : null}
      </div>
    </Dialog>
  )
}

function outcomeWords(intl: IntlShape, outcome: ExportOutcome, format: ExportFormat, terms: TermValues) {
  switch (outcome.kind) {
    case 'file':
      return undefined
    case 'tooMany':
      if (outcome.rows !== undefined) {
        return intl.formatMessage(format === 'PDF' ? drm.exportTooManyPdf : drm.exportTooManyXlsx, {
          count: intl.formatNumber(outcome.rows),
        })
      }
      return intl.formatMessage(drm.exportTooManyMore, {
        count: intl.formatNumber(outcome.moreThan ?? outcome.maximum ?? MAX_ROWS[format]),
        format,
      })
    case 'rateLimited':
      return outcome.retryAt
        ? intl.formatMessage(drm.exportRateLimitedAt, {
            time: intl.formatTime(outcome.retryAt, { hour: '2-digit', minute: '2-digit' }),
          })
        : intl.formatMessage(drm.exportRateLimited)
    case 'forbidden':
      return intl.formatMessage(drm.exportForbidden, terms)
    case 'outOfScope':
      return intl.formatMessage(drm.exportOutOfScope, terms)
    case 'failed':
      return intl.formatMessage(drm.exportFailed)
  }
}

/** "Board Risk Committee, Credit Committee · 1 Jan 2026 – 31 Dec 2026 · Outcome: Approved" (flow AT). */
function filterSummary(
  intl: IntlShape,
  search: RegisterSearch,
  q: string,
  scope: RegisterScopeView,
  facets: DecisionFacetsView | undefined,
  terms: TermValues,
): string {
  const committees = search.committee
    ? (committeeName(intl, scope, search.committee) ?? search.committee)
    : facets && facets.forums.length > 0 && facets.forums.length <= 5
      ? intl.formatList(facets.forums.map((f) => f.name))
      : facets && facets.forums.length > 5
        ? intl.formatMessage(drm.exportSomeCommittees, { ...terms, count: facets.forums.length })
        : intl.formatMessage(drm.exportAllCommittees, terms)
  const dates =
    search.from && search.to
      ? intl.formatMessage(drm.exportDates, { from: dayWords(intl, search.from), to: dayWords(intl, search.to) })
      : search.from
        ? intl.formatMessage(drm.exportFromOnly, { from: dayWords(intl, search.from) })
        : search.to
          ? intl.formatMessage(drm.exportToOnly, { to: dayWords(intl, search.to) })
          : intl.formatMessage(drm.exportAllDates)
  const others = chipsOf(intl, search, scope, terms)
    .filter((c) => c.key !== 'committee' && c.key !== 'from' && c.key !== 'to')
    .map((c) => chipWords(intl, c))
  const words = q.trim() ? [intl.formatMessage(drm.exportWords, { words: q.trim() })] : []
  return [committees, dates, ...others, ...words].join(' · ')
}
