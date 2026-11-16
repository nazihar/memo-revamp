import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useIntl } from 'react-intl'
import { listWatchingInfiniteOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { WatchingItem } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { referenceOf } from '@/features/memo/memo-fields'
import { useBulk, useSelection } from './bulk'
import { BulkBar, BulkResults, BulkSpacer, SelectAll } from './BulkBar'
import { im, imv } from './inbox-messages'
import { Listing, ROW, SelectableItem } from './Listing'

const REASON = {
  MANUAL: im.reasonMANUAL,
  INITIATED: im.reasonINITIATED,
  ACTED: im.reasonACTED,
  MENTIONED: im.reasonMENTIONED,
}

/** The Watching tab (flow J): memos I follow, newest first, each with why; Unfollow in bulk. */
export function WatchingList() {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const page = { query: { limit: 25 } }
  const list = useInfiniteQuery({
    ...listWatchingInfiniteOptions(page),
    initialPageParam: page,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
  const items = list.data?.pages.flatMap((p) => p.items) ?? []
  const selection = useSelection(items.map((i) => i.memoId))
  const bulk = useBulk(intl, async () => {
    selection.clear()
    await queryClient.invalidateQueries({ queryKey: [{ _id: 'listWatching' }] })
  })
  const nameOf = (item: WatchingItem) => `${referenceOf(intl, item.reference)} · ${item.subject}`

  return (
    <>
      <BulkResults run={bulk.run} onDismiss={bulk.dismiss} />
      <BulkBar count={selection.selected.length} onClear={selection.clear}>
        <Button
          variant="outline"
          className="min-h-11"
          disabled={bulk.busy}
          onClick={() =>
            void bulk.perform(
              'UNWATCH',
              selection.selected,
              Object.fromEntries(items.map((i) => [i.memoId, nameOf(i)])),
            )
          }
        >
          {intl.formatMessage(im.unwatch)}
        </Button>
      </BulkBar>
      <Listing
        pending={list.isPending}
        error={list.isError}
        onRetry={() => void list.refetch()}
        empty={items.length === 0 ? intl.formatMessage(im.emptyWatching) : undefined}
        more={list.hasNextPage ? () => void list.fetchNextPage() : undefined}
        loadingMore={list.isFetchingNextPage}
        header={<SelectAll total={items.length} selected={selection.selected.length} onChange={selection.setAll} />}
      >
        {items.map((item) => (
          <SelectableItem
            key={item.memoId}
            name={nameOf(item)}
            checked={selection.has(item.memoId)}
            onChange={(on) => {
              selection.toggle(item.memoId, on)
            }}
          >
            <Link to="/memos/$memoId" params={{ memoId: item.memoId }} className={ROW}>
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="truncate font-medium">{item.subject}</span>
                <span className="text-xs text-muted-foreground">{referenceOf(intl, item.reference)}</span>
              </span>
              <span className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                <span>{intl.formatMessage(REASON[item.reason])}</span>
                <time dateTime={item.since}>
                  {intl.formatMessage(imv.since, {
                    time: intl.formatDate(item.since, { dateStyle: 'medium', timeStyle: 'short' }),
                  })}
                </time>
              </span>
            </Link>
          </SelectableItem>
        ))}
      </Listing>
      <BulkSpacer count={selection.selected.length} />
    </>
  )
}
