import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { compareMemoFileVersionsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { VersionText } from '@/api/generated/types.gen'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Select } from '@/components/ui/form-controls'
import { compareTexts, type DiffResult, type DiffSpan } from './version-diff'

const m = defineMessages({
  title: { id: 'memo.compare.title', defaultMessage: 'Compare versions' },
  from: { id: 'memo.compare.from', defaultMessage: 'Older version' },
  to: { id: 'memo.compare.to', defaultMessage: 'Newer version' },
  working: { id: 'memo.compare.working', defaultMessage: 'Comparing…' },
  noText: {
    id: 'memo.compare.noText',
    defaultMessage: 'The text of this version is not available yet, or cannot be read from this kind of file.',
  },
  tooDifferent: {
    id: 'memo.compare.tooDifferent',
    defaultMessage: 'These versions are too different to mark the changes; both texts are shown side by side.',
  },
  truncated: { id: 'memo.compare.truncated', defaultMessage: 'Only the beginning of a very long text is compared.' },
  legend: {
    id: 'memo.compare.legend',
    defaultMessage: 'Removed text is struck through in the older version; added text is underlined in the newer one.',
  },
})

const counts = defineMessages<{ changes: { count: number }; version: { number: number; name: string; who: string } }>({
  changes: {
    id: 'memo.compare.changes',
    defaultMessage: '{count, plural, =0 {No changes} one {# change} other {# changes}}',
  },
  version: { id: 'memo.compare.version', defaultMessage: 'Version {number} · {name} · {who}' },
})

/** Runs the comparison in a worker when the browser has one (design E02 §3), else directly. */
function useDiff(older: string | undefined, newer: string | undefined): DiffResult | undefined {
  const [done, setDone] = useState<{ older: string; newer: string; result: DiffResult }>()
  const direct = useMemo(
    () =>
      typeof Worker === 'undefined' && older !== undefined && newer !== undefined
        ? compareTexts(older, newer)
        : undefined,
    [older, newer],
  )
  useEffect(() => {
    if (older === undefined || newer === undefined || typeof Worker === 'undefined') return
    const worker = new Worker(new URL('./version-diff.worker.ts', import.meta.url), { type: 'module' })
    worker.onmessage = (event: MessageEvent<DiffResult>) => {
      setDone({ older, newer, result: event.data })
      worker.terminate()
    }
    worker.postMessage({ older, newer })
    return () => {
      worker.terminate()
    }
  }, [older, newer])
  return direct ?? (done && done.older === older && done.newer === newer ? done.result : undefined)
}

function Spans({ spans, side }: { spans: DiffSpan[] | null; side: 'left' | 'right' }) {
  if (!spans) return null
  return (
    <>
      {spans.map((s, i) =>
        s.changed ? (
          side === 'left' ? (
            <del key={i} className="bg-status-danger-bg text-status-danger-fg">
              {s.text}
            </del>
          ) : (
            <ins key={i} className="bg-status-ok-bg text-status-ok-fg">
              {s.text}
            </ins>
          )
        ) : (
          <span key={i}>{s.text}</span>
        ),
      )}
    </>
  )
}

/**
 * Two versions of a document side by side (design E02 §3): removed text struck through on the left, added text underlined
 * on the right, each version named with who uploaded it. The server serves the texts; the browser compares them.
 */
export function CompareVersions({
  memoId,
  fileId,
  versions,
  onClose,
}: {
  memoId: string
  fileId: string
  versions: number
  onClose: () => void
}) {
  const intl = useIntl()
  const [from, setFrom] = useState(Math.max(1, versions - 1))
  const [to, setTo] = useState(versions)
  const compared = useQuery(compareMemoFileVersionsOptions({ path: { id: memoId, fileId }, query: { from, to } }))
  const older = compared.data?.from
  const newer = compared.data?.to
  const diff = useDiff(older?.text ?? undefined, newer?.text ?? undefined)
  const numbers = Array.from({ length: versions }, (_, i) => i + 1)
  const label = (v: VersionText | undefined) =>
    v ? intl.formatMessage(counts.version, { number: v.number, name: v.name, who: v.uploadedBy?.name ?? '' }) : ''

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(m.title)}
      className="max-md:h-dvh max-md:max-h-none max-md:w-screen max-md:max-w-none max-md:rounded-none md:max-w-6xl"
    >
      <div className="flex flex-wrap gap-4">
        <Field label={intl.formatMessage(m.from)} className="w-40">
          {(p) => (
            <Select
              {...p}
              value={from}
              onChange={(e) => {
                setFrom(Number(e.target.value))
              }}
            >
              {numbers.map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label={intl.formatMessage(m.to)} className="w-40">
          {(p) => (
            <Select
              {...p}
              value={to}
              onChange={(e) => {
                setTo(Number(e.target.value))
              }}
            >
              {numbers.map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </div>
      {older && newer && (older.state !== 'DONE' || newer.state !== 'DONE') ? (
        <p role="status" className="mt-3 text-sm">
          {intl.formatMessage(m.noText)}
        </p>
      ) : null}
      {diff ? (
        <>
          <p role="status" className="mt-3 text-sm">
            {diff.compared
              ? intl.formatMessage(counts.changes, { count: diff.changes })
              : intl.formatMessage(m.tooDifferent)}
          </p>
          {older?.truncated || newer?.truncated ? <p className="text-sm">{intl.formatMessage(m.truncated)}</p> : null}
          <p className="text-xs text-muted-foreground">{intl.formatMessage(m.legend)}</p>
          <div className="mt-2 overflow-x-auto">
            <table className="w-full table-fixed border-collapse text-sm" aria-label={intl.formatMessage(m.title)}>
              <thead>
                <tr>
                  <th scope="col" className="border-b p-2 text-start font-medium">
                    {label(older)}
                  </th>
                  <th scope="col" className="border-b p-2 text-start font-medium">
                    {label(newer)}
                  </th>
                </tr>
              </thead>
              <tbody>
                {diff.rows.map((row, i) => (
                  <tr key={i} className={row.changed ? 'bg-muted/40' : undefined}>
                    <td className="align-top p-2 whitespace-pre-wrap break-words">
                      <Spans spans={row.left} side="left" />
                    </td>
                    <td className="align-top p-2 whitespace-pre-wrap break-words">
                      <Spans spans={row.right} side="right" />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      ) : older?.text != null && newer?.text != null ? (
        <p role="status" className="mt-3 text-sm">
          {intl.formatMessage(m.working)}
        </p>
      ) : null}
    </Dialog>
  )
}
