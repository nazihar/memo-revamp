import { Ellipsis } from 'lucide-react'
import { useIntl } from 'react-intl'
import type { ForumResponse, MemberResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { useSession } from '@/features/auth/session'
import type { MemberAction } from './ForumPage'
import { fm, fmv } from './messages'
import { canActOn, formatDay, memberName } from './shared'

/** The name cell: the person, or a position seat with whoever holds it (or "Vacant"). */
export function NameCell({ member }: { member: MemberResponse }) {
  const intl = useIntl()
  if (member.kind === 'POSITION') {
    return (
      <span className="flex flex-col">
        <span className="font-medium">{memberName(intl, member)}</span>
        <span className="text-xs text-muted-foreground">
          {member.person
            ? intl.formatMessage(fmv.seatHolder, { name: member.person.name })
            : intl.formatMessage(fm.vacant)}
        </span>
      </span>
    )
  }
  return <span className="font-medium">{memberName(intl, member)}</span>
}

/** "2nd term, to 30 Jun 2028", "Ex officio" for a position seat without an end, and how soon it ends. */
export function TermCell({ member }: { member: MemberResponse }) {
  const intl = useIntl()
  const term =
    member.termEndsOn != null
      ? intl.formatMessage(fmv.term, { n: member.termNumber, date: formatDay(intl, member.termEndsOn) })
      : member.kind === 'POSITION'
        ? intl.formatMessage(fm.exOfficio)
        : intl.formatMessage(fmv.termOpen, { n: member.termNumber })
  return (
    <span className="flex flex-col">
      <span>{term}</span>
      {member.termEndsInDays != null ? (
        <span className="text-xs font-medium text-status-warn-fg">
          {intl.formatMessage(fmv.endsIn, { days: member.termEndsInDays })}
        </span>
      ) : null}
    </span>
  )
}

/**
 * The row menu (flow K2): Renew term, Change role, Edit, End membership, and Withdraw for a row that has not started.
 * Shown only on rows the user may change; ended rows have none.
 */
export function RowMenu({
  forum,
  member,
  onAction,
}: {
  forum: ForumResponse
  member: MemberResponse
  onAction: (action: MemberAction, member: MemberResponse) => void
}) {
  const intl = useIntl()
  const session = useSession()
  if (member.state === 'ENDED' || forum.status !== 'ACTIVE') return null
  if (!canActOn(forum, member, session.data?.user?.id)) return null
  const upcoming = member.state === 'UPCOMING'
  const actions: MemberAction[] = upcoming
    ? ['edit', 'withdraw']
    : [...(member.termEndsOn ? (['renew'] as const) : []), 'changeRole', 'edit', 'end']
  const labels: Record<MemberAction, string> = {
    renew: intl.formatMessage(fm.renew),
    changeRole: intl.formatMessage(fm.changeRole),
    edit: intl.formatMessage(fm.editMember),
    end: intl.formatMessage(fm.end),
    withdraw: intl.formatMessage(fm.withdraw),
  }
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          aria-label={intl.formatMessage(fmv.rowActions, { name: memberName(intl, member) })}
        >
          <Ellipsis aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {actions.map((action) => (
          <DropdownMenuItem
            key={action}
            className="min-h-11"
            onSelect={() => {
              onAction(action, member)
            }}
          >
            {labels[action]}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
