import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Copy, Download } from 'lucide-react'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { getMemoRenditionOptions, getMemoRenditionQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { regenerateMemoRendition, retryMemoRendition } from '@/api/generated/sdk.gen'
import type { MemoView, RecordVersionView } from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { holds, useMyAccess } from '@/features/access/access'
import { useWithStepUp } from '@/features/auth/step-up-context'

const m = defineMessages({
  title: { id: 'memo.record.title', defaultMessage: 'Final record' },
  pending: {
    id: 'memo.record.pending',
    defaultMessage:
      'The final record is being prepared. It combines the approved document, the approvals, the note sheet and the attachments in one PDF/A file.',
  },
  failed: {
    id: 'memo.record.failed',
    defaultMessage: 'The final record could not be made. Administrators have been told.',
  },
  download: { id: 'memo.record.download', defaultMessage: 'Download' },
  retry: { id: 'memo.record.retry', defaultMessage: 'Retry' },
  regenerate: { id: 'memo.record.regenerate', defaultMessage: 'Make a new version' },
  englishOnly: { id: 'memo.record.englishOnly', defaultMessage: 'The record itself is in English.' },
  valid: { id: 'memo.record.valid', defaultMessage: 'Valid PDF/A' },
  invalid: { id: 'memo.record.invalid', defaultMessage: 'Not valid PDF/A' },
  copy: { id: 'memo.record.copy', defaultMessage: 'Copy the SHA-256' },
  copied: { id: 'memo.record.copied', defaultMessage: 'SHA-256 copied.' },
  earlier: { id: 'memo.record.earlier', defaultMessage: 'Earlier versions' },
  dialogTitle: { id: 'memo.record.dialogTitle', defaultMessage: 'Make a new version of the final record' },
  dialogLead: {
    id: 'memo.record.dialogLead',
    defaultMessage: 'The current version stays as it is and can still be opened. Say why a new version is needed.',
  },
  reason: { id: 'memo.record.reason', defaultMessage: 'Reason' },
  cancel: { id: 'memo.record.cancel', defaultMessage: 'Cancel' },
  done: { id: 'memo.record.done', defaultMessage: 'A new version is being made.' },
  sha: { id: 'memo.record.sha', defaultMessage: 'SHA-256' },
  pdfa: { id: 'memo.record.pdfa', defaultMessage: 'PDF/A' },
})

const mv = defineMessages<{
  ready: { version: number; pages: number }
  made: { time: string }
  notCleared: { version: number; classification: string }
  checked: { validator: string; profile: string }
  failedRules: { count: number }
  flags: { rasterised: number; placeholders: number }
  madeWith: { renderer: string }
  replaced: { version: number; time: string; reason: string }
  makeVersion: { version: number }
  downloadVersion: { version: number }
}>({
  ready: {
    id: 'memo.record.ready',
    defaultMessage: 'Version {version, number} · {pages, plural, one {# page} other {# pages}} · PDF/A',
  },
  made: { id: 'memo.record.made', defaultMessage: 'Made {time}' },
  notCleared: {
    id: 'memo.record.notCleared',
    defaultMessage: 'Version {version, number} · Classified {classification}. You are not cleared to open it.',
  },
  checked: { id: 'memo.record.checked', defaultMessage: '{validator}, profile {profile}' },
  failedRules: {
    id: 'memo.record.failedRules',
    defaultMessage: '{count, plural, one {# rule failed} other {# rules failed}}',
  },
  flags: {
    id: 'memo.record.flags',
    defaultMessage:
      '{rasterised, plural, =0 {} one {# page without text} other {# pages without text}}{rasterised, select, 0 {} other { · }}{placeholders, plural, =0 {no attachment kept as an original} one {# attachment kept as an original} other {# attachments kept as originals}}',
  },
  madeWith: { id: 'memo.record.madeWith', defaultMessage: 'Made with {renderer}' },
  replaced: { id: 'memo.record.replaced', defaultMessage: 'v{version, number} · {time}{reason}' },
  makeVersion: { id: 'memo.record.makeVersion', defaultMessage: 'Make version {version, number}' },
  downloadVersion: { id: 'memo.record.downloadVersion', defaultMessage: 'Download version {version, number}' },
})

function contentUrl(v: RecordVersionView): string | undefined {
  return v.fileId && v.versionId ? `/api/v1/files/${v.fileId}/versions/${v.versionId}/content` : undefined
}

/**
 * The memo's final record (ADR-0053, design E04 flow G): being prepared (polled), ready to download, not cleared, or
 * failed; auditors see its hash and validation; holders of `memo.rendition.regenerate` can retry or make a new version.
 */
export function FinalRecord({
  memo,
  onProblem,
}: {
  memo: MemoView
  onProblem: (problem: Problem | undefined) => void
}) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const access = useMyAccess()
  const withStepUp = useWithStepUp()
  const [regenerating, setRegenerating] = useState(false)
  const record = useQuery({
    ...getMemoRenditionOptions({ path: { id: memo.id } }),
    refetchInterval: (query) => (query.state.data?.state === 'PENDING' ? POLL_MS : false),
  })
  const keeper = holds(access.data, 'memo.rendition.regenerate')
  const refresh = () => queryClient.invalidateQueries({ queryKey: getMemoRenditionQueryKey({ path: { id: memo.id } }) })

  const retry = async () => {
    try {
      await retryMemoRendition({ path: { id: memo.id }, throwOnError: true })
      await refresh()
    } catch (problem) {
      onProblem(isProblem(problem) ? problem : undefined)
    }
  }

  const data = record.data
  if (!data || data.state === 'NONE') return null
  const current = data.current
  const earlier = data.versions.filter((v) => v.state === 'READY' && v.version !== current?.version)
  // An earlier version is listed with the reason it was replaced: the reason given for the next version.
  const replacedBecause = (version: number) => {
    const next = data.versions
      .filter((v) => v.version > version)
      .reduce<(typeof data.versions)[number] | undefined>((a, v) => (!a || v.version < a.version ? v : a), undefined)
    return next?.reason ? ` · ${next.reason}` : ''
  }
  const time = (at: string) => intl.formatDate(at, { dateStyle: 'medium', timeStyle: 'short' })

  return (
    <section aria-labelledby="final-record" className="flex flex-col gap-3 rounded-md border p-4">
      <h2 id="final-record" className="text-base font-semibold">
        {intl.formatMessage(m.title)}
      </h2>
      {data.state === 'PENDING' ? (
        <p role="status" className="text-sm">
          {intl.formatMessage(m.pending)}
        </p>
      ) : null}
      {data.state === 'FAILED' && !current ? (
        <div className="flex flex-wrap items-center gap-2">
          <p role="alert" className="text-sm">
            {intl.formatMessage(m.failed)}
          </p>
          {keeper ? (
            <Button variant="outline" onClick={() => void retry()}>
              {intl.formatMessage(m.retry)}
            </Button>
          ) : null}
        </div>
      ) : null}
      {current ? <CurrentRecord version={current} time={time} /> : null}
      {current && keeper && data.state !== 'PENDING' ? (
        <div>
          <Button
            variant="outline"
            onClick={() => {
              setRegenerating(true)
            }}
          >
            {intl.formatMessage(m.regenerate)}
          </Button>
        </div>
      ) : null}
      {earlier.length > 0 ? (
        <div className="text-sm">
          <h3 className="font-medium">{intl.formatMessage(m.earlier)}</h3>
          <ul className="mt-1 flex flex-col gap-1">
            {earlier.map((v) => (
              <li key={v.version} className="flex flex-wrap items-center justify-between gap-2 text-muted-foreground">
                <span>
                  {intl.formatMessage(mv.replaced, {
                    version: v.version,
                    time: time(v.createdAt),
                    reason: replacedBecause(v.version),
                  })}
                </span>
                {contentUrl(v) ? (
                  <Button variant="ghost" asChild>
                    <a
                      href={contentUrl(v)}
                      download
                      aria-label={intl.formatMessage(mv.downloadVersion, { version: v.version })}
                    >
                      <Download aria-hidden="true" />
                    </a>
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      {intl.locale !== 'en' ? (
        <p className="text-xs text-muted-foreground">{intl.formatMessage(m.englishOnly)}</p>
      ) : null}
      {regenerating ? (
        <RegenerateDialog
          next={(data.versions[0]?.version ?? 0) + 1}
          onClose={() => {
            setRegenerating(false)
          }}
          onSubmit={async (reason) => {
            try {
              await withStepUp(() =>
                regenerateMemoRendition({ path: { id: memo.id }, body: { reason }, throwOnError: true }),
              )
              setRegenerating(false)
              toast.show(intl.formatMessage(m.done), 'success')
              await refresh()
            } catch (problem) {
              onProblem(isProblem(problem) ? problem : undefined)
            }
          }}
        />
      ) : null}
    </section>
  )
}

function CurrentRecord({ version, time }: { version: RecordVersionView; time: (at: string) => string }) {
  const intl = useIntl()
  const toast = useToast()
  const url = contentUrl(version)
  const audit = version.audit
  if (!version.readable) {
    return (
      <p className="text-sm">
        {intl.formatMessage(mv.notCleared, { version: version.version, classification: version.classification ?? '' })}
      </p>
    )
  }
  return (
    <div className="flex flex-col gap-2 text-sm">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span>
          {intl.formatMessage(mv.ready, { version: version.version, pages: version.pages ?? 0 })}
          {version.finishedAt ? (
            <span className="block text-xs text-muted-foreground">
              {intl.formatMessage(mv.made, { time: time(version.finishedAt) })}
            </span>
          ) : null}
        </span>
        {url ? (
          <Button asChild>
            <a href={url} download>
              <Download aria-hidden="true" />
              {intl.formatMessage(m.download)}
            </a>
          </Button>
        ) : null}
      </div>
      {audit ? (
        <dl className="grid gap-1 rounded-md bg-muted/40 p-3 text-xs sm:grid-cols-[auto_1fr]">
          <dt className="font-medium">{intl.formatMessage(m.sha)}</dt>
          <dd className="flex items-center gap-1 font-mono break-all">
            {audit.sha256}
            <Button
              variant="ghost"
              size="icon"
              aria-label={intl.formatMessage(m.copy)}
              onClick={() => {
                void navigator.clipboard.writeText(audit.sha256).then(() => {
                  toast.show(intl.formatMessage(m.copied), 'success')
                })
              }}
            >
              <Copy aria-hidden="true" />
            </Button>
          </dd>
          <dt className="font-medium">{intl.formatMessage(m.pdfa)}</dt>
          <dd className="flex flex-wrap items-center gap-2">
            <Badge tone={audit.compliant ? 'ok' : 'danger'}>
              {intl.formatMessage(audit.compliant ? m.valid : m.invalid)}
            </Badge>
            <span>{intl.formatMessage(mv.checked, { validator: audit.validator, profile: audit.profile })}</span>
            {audit.compliant ? null : (
              <span>{intl.formatMessage(mv.failedRules, { count: Object.keys(audit.failedRules).length })}</span>
            )}
          </dd>
          <dd className="sm:col-start-2">
            {intl.formatMessage(mv.flags, { rasterised: audit.rasterisedPages, placeholders: audit.placeholders })}
          </dd>
          <dd className="text-muted-foreground sm:col-start-2">
            {intl.formatMessage(mv.madeWith, { renderer: audit.renderer })}
          </dd>
        </dl>
      ) : null}
    </div>
  )
}

function RegenerateDialog({
  next,
  onClose,
  onSubmit,
}: {
  next: number
  onClose: () => void
  onSubmit: (reason: string) => Promise<void>
}) {
  const intl = useIntl()
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(m.dialogTitle)}
      description={intl.formatMessage(m.dialogLead)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(m.cancel)}
          </Button>
          <Button
            disabled={busy || !reason.trim()}
            onClick={() => {
              setBusy(true)
              void onSubmit(reason.trim()).finally(() => {
                setBusy(false)
              })
            }}
          >
            {intl.formatMessage(mv.makeVersion, { version: next })}
          </Button>
        </>
      }
    >
      <Field label={intl.formatMessage(m.reason)} required>
        {(p) => (
          <Textarea
            {...p}
            maxLength={500}
            rows={3}
            value={reason}
            onChange={(e) => {
              setReason(e.target.value)
            }}
          />
        )}
      </Field>
    </Dialog>
  )
}

const POLL_MS = 10_000
