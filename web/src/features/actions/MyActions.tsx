import { useInfiniteQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useIntl } from 'react-intl'
import { getMyActionsInfiniteOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { ActionSummary } from '@/api/generated/types.gen'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { useTerms } from '@/features/terms/terms'
import { cn } from '@/lib/utils'
import { am } from './messages'
import { RagBadge, RagKey } from './RagBadge'
import { dueWords, referenceWords } from './words'

/** The lists of "My actions" (flow AK, design §1.14 T5): to do, to verify and those I am involved in. */
export type MyActionsList = 'mine' | 'verify' | 'involved'

const TAB = { mine: 'MINE', verify: 'TO_VERIFY', involved: 'CONTRIBUTING' } as const
const LISTS: readonly MyActionsList[] = ['mine', 'verify', 'involved']

/**
 * "My actions" in the inbox (flow AK): To do, To verify and I'm involved, each with its count from the same rule as its
 * rows; rows by due date (the overdue first, as they are due earliest), each with its colour in words and icon, the
 * committee, and the due date with where it started; the key below. Empty lists say what would appear.
 */
export function MyActions({ list = 'mine', onList }: { list?: MyActionsList; onList: (list: MyActionsList) => void }) {
  const intl = useIntl()
  const terms = useTerms()
  const page = { query: { tab: TAB[list], limit: 25 } }
  const actions = useInfiniteQuery({
    ...getMyActionsInfiniteOptions(page),
    initialPageParam: page,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
  const counts = actions.data?.pages[0]?.counts
  const items = actions.data?.pages.flatMap((p) => p.items) ?? []
  const label = (l: MyActionsList) =>
    intl.formatMessage(l === 'mine' ? am.listMine : l === 'verify' ? am.listVerify : am.listInvolved, {
      count: counts ? (l === 'mine' ? counts.mine : l === 'verify' ? counts.toVerify : counts.contributing) : 0,
    })
  const empty = list === 'mine' ? am.emptyMine : list === 'verify' ? am.emptyVerify : am.emptyInvolved

  return (
    <div>
      <div role="group" aria-label={intl.formatMessage(am.listsLabel)} className="mb-4 flex flex-wrap gap-2">
        {LISTS.map((l) => (
          <Button
            key={l}
            type="button"
            variant={l === list ? 'default' : 'outline'}
            aria-pressed={l === list}
            className="min-h-11"
            onClick={() => {
              onList(l)
            }}
          >
            {label(l)}
          </Button>
        ))}
      </div>
      {actions.isPending ? (
        <LoadingState />
      ) : actions.isError ? (
        <ErrorState onRetry={() => void actions.refetch()} />
      ) : items.length === 0 ? (
        <EmptyState title={intl.formatMessage(empty, terms)} />
      ) : (
        <>
          <ul className="flex flex-col divide-y rounded-md border">
            {items.map((a) => (
              <li key={a.id}>
                <ActionRow action={a} />
              </li>
            ))}
          </ul>
          {actions.hasNextPage ? (
            <div className="mt-4 flex justify-center">
              <Button
                variant="outline"
                className="min-h-11"
                disabled={actions.isFetchingNextPage}
                onClick={() => void actions.fetchNextPage()}
              >
                {intl.formatMessage(am.loadMore)}
              </Button>
            </div>
          ) : null}
        </>
      )}
      <RagKey />
    </div>
  )
}

/** One action: its colour in words, reference and title, and "Board Risk Committee · due 28 Nov (originally 14 Nov, revised once)". */
function ActionRow({ action: a }: { action: ActionSummary }) {
  const intl = useIntl()
  const reference = referenceWords(intl, a.reference)
  return (
    <Link
      to="/actions/$actionId"
      params={{ actionId: a.id }}
      className={cn(
        'flex min-h-14 w-full flex-col gap-2 px-4 py-3 text-start hover:bg-accent md:flex-row md:items-center md:gap-4',
        'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-ring',
      )}
    >
      <RagBadge rag={a.rag} className="md:w-44" />
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="font-medium">
          <span className="tabular-nums">{reference}</span> <span>{a.title}</span>
        </span>
        <span className="text-sm text-muted-foreground">
          {intl.formatMessage(am.rowLine, { committee: a.committee, due: dueWords(intl, a) })}
        </span>
      </span>
      <span aria-hidden="true" className="text-sm font-medium text-primary underline-offset-4">
        {intl.formatMessage(am.open)}
      </span>
    </Link>
  )
}
