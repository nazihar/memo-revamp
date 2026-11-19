import { useIntl } from 'react-intl'
import type { MemberAgendaResponse, MemberItemResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { useDeclarations } from './context'
import { dm, dmv } from './messages'
import { declarable, isOpen, standingLines } from './words'

/**
 * What an agenda item says about the reader's own interest (flow S1): under a recused line, why ("You will be asked to
 * leave the room for this item. Its papers are not shown to you.") and "My declaration"; on an item the reader may
 * declare on, "Declare an interest". The button keeps its place whichever it is, so focus stays on it after a change.
 * A member who sees only "Restricted item" has nothing to declare on (E04 §1.3: they do not see what the item is).
 */
export function ItemDeclaration({ item, name, latest }: { item: MemberItemResponse; name: string; latest: boolean }) {
  const intl = useIntl()
  const context = useDeclarations()
  const mine = isOpen(item.myDeclaration) ? item.myDeclaration : undefined
  const note = mine
    ? mine.state === 'AWAITING_RULING'
      ? dm.lineAwaiting
      : mine.state === 'TAKING_PART'
        ? dm.lineTakingPart
        : dm.lineRecused
    : undefined
  const canDeclare = !mine && !item.recused && context?.open === true && declarable(item)
  const button = context && latest && (mine ?? canDeclare)
  if (!note && !button) return null
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-lg">
      {note ? <p>{intl.formatMessage(note)}</p> : null}
      {button ? (
        <Button
          variant="outline"
          className="h-auto min-h-11 whitespace-normal text-base print:hidden"
          aria-label={intl.formatMessage(mine ? dmv.mineNamed : dmv.declareNamed, { item: name })}
          onClick={(e) => {
            if (mine) context.show(mine, name, e.currentTarget)
            else context.declare({ itemId: item.id, name }, e.currentTarget)
          }}
        >
          {intl.formatMessage(mine ? dm.myDeclaration : dm.declare)}
        </Button>
      ) : null}
    </div>
  )
}

/**
 * The Declarations standing item's list (E04 §1.5): who declared on the items the reader may see and what they will do
 * ("S. Ali — will leave the room for item 5"), never the nature.
 */
export function StandingDeclarations({ agenda }: { agenda: MemberAgendaResponse }) {
  const intl = useIntl()
  const lines = standingLines(intl, agenda)
  if (!lines.length) return null
  return (
    <ul aria-label={intl.formatMessage(dm.standingLabel)} className="flex flex-col gap-1 ps-4 text-lg sm:ps-8">
      {lines.map((line) => (
        <li key={line.key}>{line.text}</li>
      ))}
    </ul>
  )
}
