import { Link } from '@tanstack/react-router'
import { ScrollText } from 'lucide-react'
import { defineMessages, useIntl, type NoMessageValues } from 'react-intl'
import type { ForumResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { PERMISSION, holds, useMyAccess } from '@/features/access/access'
import { useTerms } from '@/features/terms/terms'
import { hashOf } from './register-params'

/**
 * The register's entry points on other pages (design E08 §1.6). Their words are in the small `registerLinks` catalogue
 * part, which loads with the committee pages (and with the page that places "In the register", which adds its route to the part), so those pages do not load the whole register's part.
 */
const lm = defineMessages<{
  ofCommittee: Record<string, string>
  inTheRegister: NoMessageValues
  inTheRegisterNamed: { number: string }
}>({
  ofCommittee: {
    id: 'registerLinks.ofCommittee',
    defaultMessage: '{forumGender, select, other {Decisions of this {forum}}}',
  },
  inTheRegister: { id: 'registerLinks.inTheRegister', defaultMessage: 'In the register' },
  inTheRegisterNamed: { id: 'registerLinks.inTheRegisterNamed', defaultMessage: 'Resolution {number} in the register' },
})

/** The seats that give the register (design §1.3): officers and members in office, not invitees or observers. */
const REGISTER_ROLES = new Set<ForumResponse['myRole']>(['SECRETARY', 'CHAIR', 'VICE_CHAIR', 'MEMBER'])

/**
 * "Decisions of this committee" on a committee's page: the register filtered to it. Shown to the committee's officers
 * and members and to oversight (`forum.read`, `forums.manage`); the server decides what the register shows.
 */
export function CommitteeDecisionsLink({ forum }: { forum: ForumResponse }) {
  const intl = useIntl()
  const terms = useTerms()
  const access = useMyAccess()
  const sees =
    forum.canManage ||
    REGISTER_ROLES.has(forum.myRole) ||
    holds(access.data, 'forum.read') ||
    holds(access.data, PERMISSION.forumsManage)
  if (!sees) return null
  return (
    <Button variant="outline" className="min-h-11" asChild>
      <Link to="/decisions" search={{ committee: forum.id }}>
        <ScrollText aria-hidden="true" />
        {intl.formatMessage(lm.ofCommittee, terms)}
      </Link>
    </Button>
  )
}

/**
 * "In the register" for a resolution (design §1.6): the register filtered to its committee with its number as the words,
 * so the number lookup finds its row. For E06's resolution page and the decisions on the meeting page.
 */
export function InTheRegisterLink({
  forumId,
  resolutionNumber,
  named = false,
  className,
}: {
  forumId: string
  resolutionNumber: string
  /** Names the number in the link, where the page around it does not. */
  named?: boolean
  className?: string
}) {
  const intl = useIntl()
  return (
    <Link
      to="/decisions"
      search={{ committee: forumId }}
      hash={hashOf(resolutionNumber)}
      className={
        className ??
        'inline-flex min-h-11 items-center gap-1.5 text-primary underline underline-offset-4 hover:decoration-2'
      }
    >
      <ScrollText aria-hidden="true" className="size-4 shrink-0" />
      {named
        ? intl.formatMessage(lm.inTheRegisterNamed, { number: resolutionNumber })
        : intl.formatMessage(lm.inTheRegister)}
    </Link>
  )
}
