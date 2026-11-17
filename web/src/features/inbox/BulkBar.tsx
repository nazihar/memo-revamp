import type { ReactNode } from 'react'
import { useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/form-controls'
import { failuresOf, summaryOf, type BulkRun } from './bulk'
import { im, imv } from './inbox-messages'

/**
 * The bulk bar (flows I and J): shown while something is selected. On phones it sits at the bottom of the screen, as
 * the memo's action bar does; from the medium breakpoint it sits above the list.
 */
export function BulkBar({ count, onClear, children }: { count: number; onClear: () => void; children: ReactNode }) {
  const intl = useIntl()
  if (count === 0) return null
  return (
    <div
      role="toolbar"
      aria-label={intl.formatMessage(im.bulkActions)}
      className="fixed inset-x-0 bottom-0 z-10 flex flex-wrap items-center gap-2 border-t bg-card px-4 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] md:static md:mb-3 md:rounded-md md:border md:px-3 md:py-2"
    >
      <span className="me-auto text-sm font-medium">{intl.formatMessage(imv.selected, { count })}</span>
      {children}
      <Button variant="ghost" className="min-h-11" onClick={onClear}>
        {intl.formatMessage(im.clearSelection)}
      </Button>
    </div>
  )
}

/** Room under a list on phones while the bulk bar shows, so the fixed bar never covers the last row. */
export function BulkSpacer({ count }: { count: number }) {
  return count > 0 ? <div aria-hidden="true" className="h-24 md:hidden" /> : null
}

/** The result of the last bulk action, per item, announced politely. */
export function BulkResults({ run, onDismiss }: { run: BulkRun | undefined; onDismiss: () => void }) {
  const intl = useIntl()
  const failures = run ? failuresOf(intl, run) : []
  return (
    <div role="status" aria-live="polite" aria-atomic="true">
      {run ? (
        <div className="mb-3 flex flex-col gap-2 rounded-md border bg-card p-3 text-sm">
          <p className="font-medium">{summaryOf(intl, run)}</p>
          {failures.length > 0 ? (
            <ul aria-label={intl.formatMessage(im.notDone)} className="list-disc ps-5 text-muted-foreground">
              {failures.map((f) => (
                <li key={f.id}>{f.text}</li>
              ))}
            </ul>
          ) : null}
          <div>
            <Button variant="outline" onClick={onDismiss}>
              {intl.formatMessage(im.dismiss)}
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  )
}

/** "Select all shown", checked when every shown item is selected and mixed when some are. */
export function SelectAll({
  total,
  selected,
  onChange,
}: {
  total: number
  selected: number
  onChange: (on: boolean) => void
}) {
  const intl = useIntl()
  if (total === 0) return null
  return (
    <label className="flex min-h-11 items-center gap-3 px-4 text-sm">
      <Checkbox
        ref={(el) => {
          if (el) el.indeterminate = selected > 0 && selected < total
        }}
        checked={selected === total}
        onChange={(e) => {
          onChange(e.target.checked)
        }}
      />
      {intl.formatMessage(im.selectAll)}
    </label>
  )
}
