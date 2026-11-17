import { useIntl } from 'react-intl'
import type { MemoDetailView, MemoView } from '@/api/generated/types.gen'
import { detailText, dm } from './memo-details'

function DetailList({ details }: { details: MemoDetailView[] }) {
  const intl = useIntl()
  return (
    <dl className="grid gap-x-4 gap-y-2 text-sm sm:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
      {details.map((d) => (
        <div key={d.key} className="contents">
          <dt className="text-muted-foreground">{d.label}</dt>
          <dd className="break-words whitespace-pre-line">{detailText(intl, d)}</dd>
        </div>
      ))}
    </dl>
  )
}

/**
 * The memo's details (ux-review B3): the values of its type's fields, in form order. A draft whose type dropped fields
 * since it was written says so, and lists those values apart.
 */
export function MemoDetails({ memo }: { memo: MemoView }) {
  const intl = useIntl()
  if (memo.details.length === 0 && memo.staleDetails.length === 0) return null
  return (
    <section aria-labelledby="memo-details" className="rounded-md border p-4">
      <h2 id="memo-details" className="mb-3 text-base font-semibold">
        {intl.formatMessage(dm.details)}
      </h2>
      {memo.staleDetails.length > 0 ? (
        <p role="note" className="mb-3 rounded-md bg-status-warn-bg p-3 text-sm text-status-warn-fg">
          {intl.formatMessage(dm.stale)}
        </p>
      ) : null}
      <DetailList details={memo.details} />
      {memo.staleDetails.length > 0 ? (
        <>
          <h3 className="mt-4 mb-2 text-sm font-semibold">{intl.formatMessage(dm.noLongerOnForm)}</h3>
          <DetailList details={memo.staleDetails} />
        </>
      ) : null}
    </section>
  )
}
