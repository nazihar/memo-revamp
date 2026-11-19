import { Check } from 'lucide-react'
import { useIntl } from 'react-intl'
import { confirmDeclarationReview } from '@/api/generated/sdk.gen'
import type { DeclarationResponse, MyDeclarationsResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { Button } from '@/components/ui/button'
import { useToast } from '@/components/ui/toast-context'
import { FormDialog } from '@/features/forums/FormDialog'
import { dm, dmv } from './messages'
import { declaredWords, isOpen, refusal, reviewItemName } from './words'

/**
 * "Review the agenda for 12 December — declarations of interest" (flow S3): the items to review — every item before a
 * first confirmation, then only those added since — each with "I have an interest" (the declaration sheet), or what
 * the member declared; and "I have declared my interests in this agenda", recorded against the latest revision. If the
 * agenda changed meanwhile (409 `STALE_REVISION`), the new list is read and the member confirms again.
 */
export function ReviewSheet({
  mine,
  startsAt,
  timeZone,
  onDeclare,
  onShow,
  refresh,
  onConfirmed,
  onClose,
}: {
  mine: MyDeclarationsResponse
  startsAt: string
  timeZone: string
  onDeclare: (item: { itemId: string; name: string }, opener: HTMLElement | null) => void
  onShow: (declaration: DeclarationResponse, name: string, opener: HTMLElement | null) => void
  refresh: () => Promise<void>
  onConfirmed: (answer: MyDeclarationsResponse) => void
  onClose: () => void
}) {
  const intl = useIntl()
  const toast = useToast()
  const date = intl.formatDate(startsAt, { day: 'numeric', month: 'long', timeZone })
  const declared = new Map(mine.declarations.filter(isOpen).map((d) => [d.itemId, d]))

  const submit = async (): Promise<string | undefined> => {
    try {
      const answer = await confirmDeclarationReview({
        path: { meetingId: mine.meetingId },
        body: { revision: mine.latestRevision },
        throwOnError: true,
      })
      toast.show(intl.formatMessage(dm.confirmed), 'success')
      onConfirmed(answer.data)
      return undefined
    } catch (problem) {
      if (isProblem(problem) && problem.status === 409) await refresh()
      return refusal(intl, problem)
    }
  }

  return (
    <FormDialog
      side
      large
      title={intl.formatMessage(dmv.reviewTitle, { date })}
      description={intl.formatMessage(dm.reviewExplain)}
      submitLabel={intl.formatMessage(dm.confirm)}
      cancelLabel={intl.formatMessage(dm.close)}
      onSubmit={submit}
      onClose={onClose}
    >
      {mine.reviewedRevision != null ? (
        <p className="text-base">{intl.formatMessage(dmv.reviewSince, { revision: mine.reviewedRevision })}</p>
      ) : null}
      <ul aria-label={intl.formatMessage(dm.reviewList)} className="flex flex-col divide-y border-y">
        {mine.toReview.map((item) => {
          const name = reviewItemName(intl, item)
          const d = declared.get(item.itemId)
          return (
            <li key={item.itemId} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 py-2">
              <span className="text-lg">{name}</span>
              {/* One control per row whatever its state, so focus stays on it once the member has declared. */}
              <Button
                variant="outline"
                className="h-auto min-h-11 whitespace-normal text-base"
                aria-label={
                  d
                    ? intl.formatMessage(dmv.declaredNamed, { what: declaredWords(intl, d.state), item: name })
                    : intl.formatMessage(dmv.haveInterestNamed, { item: name })
                }
                onClick={(e) => {
                  if (d) onShow(d, name, e.currentTarget)
                  else onDeclare({ itemId: item.itemId, name }, e.currentTarget)
                }}
              >
                {d ? (
                  <>
                    <Check aria-hidden="true" className="text-status-ok-fg" />
                    {declaredWords(intl, d.state)}
                  </>
                ) : (
                  intl.formatMessage(dm.haveInterest)
                )}
              </Button>
            </li>
          )
        })}
      </ul>
      <p className="text-base text-muted-foreground">{intl.formatMessage(dm.confirmHint)}</p>
    </FormDialog>
  )
}
