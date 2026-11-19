import { useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'
import { PANEL_TITLE_ID, useDeclarations } from './context'
import { dm, dmv } from './messages'
import { declarationItem, isOpen, standingLine } from './words'

/**
 * The Declarations panel above the agenda (flow S1): while items wait for review, "Please read the agenda and tell us
 * about any interest. 9 items to review [Review and declare]" ("2 new items to review" after a confirmation); once
 * confirmed, which revision was confirmed; and the member's own declarations in this meeting, each with "My
 * declaration". Nothing at all when there is nothing to say.
 */
export function DeclarationsPanel() {
  const intl = useIntl()
  const context = useDeclarations()
  const mine = context?.mine
  if (!context || !mine) return null
  const declarations = mine.declarations.filter(isOpen)
  const asking = context.open && mine.toReview.length > 0
  if (!asking && !declarations.length && mine.reviewedRevision == null) return null
  const count = mine.toReview.length
  return (
    <section
      aria-labelledby={PANEL_TITLE_ID}
      className="flex flex-col gap-3 rounded-md border bg-card p-4 text-lg print:hidden"
    >
      <h2 id={PANEL_TITLE_ID} tabIndex={-1} className="text-xl font-semibold">
        {intl.formatMessage(dm.panelTitle)}
      </h2>
      {asking ? (
        <>
          <p>{intl.formatMessage(dm.prompt)}</p>
          <p className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <span className="font-medium">
              {intl.formatMessage(mine.reviewedRevision == null ? dmv.toReview : dmv.newToReview, { count })}
            </span>
            <Button
              className="h-auto min-h-11 whitespace-normal text-base"
              onClick={(e) => {
                context.review(e.currentTarget)
              }}
            >
              {intl.formatMessage(dm.review)}
            </Button>
          </p>
        </>
      ) : mine.reviewedRevision != null ? (
        <p>{intl.formatMessage(dmv.reviewed, { revision: mine.reviewedRevision })}</p>
      ) : null}
      {declarations.length ? (
        <>
          <h3 className="font-semibold">{intl.formatMessage(dm.yours)}</h3>
          <ul className="flex flex-col gap-2">
            {declarations.map((d) => {
              const name = declarationItem(intl, d)
              return (
                <li key={d.id} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
                  <span>{standingLine(intl, d.state, name)}</span>
                  <Button
                    variant="outline"
                    className="h-auto min-h-11 whitespace-normal text-base"
                    aria-label={intl.formatMessage(dmv.mineNamed, { item: name })}
                    onClick={(e) => {
                      context.show(d, name, e.currentTarget)
                    }}
                  >
                    {intl.formatMessage(dm.myDeclaration)}
                  </Button>
                </li>
              )
            })}
          </ul>
        </>
      ) : null}
    </section>
  )
}
