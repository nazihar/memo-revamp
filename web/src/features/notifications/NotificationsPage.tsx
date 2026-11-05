import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useIntl } from 'react-intl'
import {
  getMyUnreadCountQueryKey,
  listMyNotificationsInfiniteOptions,
  listMyNotificationsInfiniteQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { markAllNotificationsRead, markNotificationRead } from '@/api/generated/sdk.gen'
import type { NotificationView } from '@/api/generated/types.gen'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { nm, nmv, notificationText } from './notification-messages'

const PAGE = { query: { limit: 20 } }

/** `/notifications` (design E06 §8): my notifications, newest first; activating one marks it read and follows its link. */
export function NotificationsPage() {
  const intl = useIntl()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const list = useInfiniteQuery({
    ...listMyNotificationsInfiniteOptions(PAGE),
    initialPageParam: PAGE,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
  const items = list.data?.pages.flatMap((page) => page.items) ?? []
  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: listMyNotificationsInfiniteQueryKey(PAGE) })
    await queryClient.invalidateQueries({ queryKey: getMyUnreadCountQueryKey() })
  }
  const open = async (item: NotificationView) => {
    if (!item.read) {
      await markNotificationRead({ path: { id: item.id }, throwOnError: true })
      await refresh()
    }
    if (item.link) await navigate({ to: item.link })
  }
  const markAll = async () => {
    await markAllNotificationsRead({ throwOnError: true })
    await refresh()
  }
  const unread = items.some((item) => !item.read)

  return (
    <section aria-labelledby="notifications-title">
      <PageHeader
        title={<span id="notifications-title">{intl.formatMessage(nm.title)}</span>}
        description={intl.formatMessage(nm.lead)}
        actions={
          unread ? (
            <Button variant="outline" onClick={() => void markAll()}>
              {intl.formatMessage(nm.markAll)}
            </Button>
          ) : null
        }
      />
      {list.isPending ? (
        <LoadingState />
      ) : list.isError ? (
        <ErrorState onRetry={() => void list.refetch()} />
      ) : items.length === 0 ? (
        <EmptyState title={intl.formatMessage(nm.empty)} />
      ) : (
        <>
          <ul className="flex flex-col divide-y rounded-md border">
            {items.map((item) => (
              <li key={item.id}>
                <button
                  type="button"
                  onClick={() => void open(item)}
                  className={cn(
                    'flex min-h-14 w-full items-start gap-3 px-4 py-3 text-start hover:bg-accent focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-ring',
                    !item.read && 'bg-accent/40',
                  )}
                >
                  <span
                    aria-hidden="true"
                    className={cn('mt-2 size-2 shrink-0 rounded-full', item.read ? 'bg-transparent' : 'bg-primary')}
                  />
                  <span className="flex min-w-0 flex-col gap-0.5">
                    <span className={cn('text-sm', !item.read && 'font-semibold')}>
                      {item.read ? (
                        notificationText(intl, item.kind, item.params)
                      ) : (
                        <>
                          <span aria-hidden="true">{notificationText(intl, item.kind, item.params)}</span>
                          <span className="sr-only">
                            {intl.formatMessage(nmv.unreadItem, {
                              text: notificationText(intl, item.kind, item.params),
                            })}
                          </span>
                        </>
                      )}
                    </span>
                    <time dateTime={item.createdAt} className="text-xs text-muted-foreground">
                      {intl.formatDate(item.createdAt, { dateStyle: 'medium', timeStyle: 'short' })}
                    </time>
                  </span>
                </button>
              </li>
            ))}
          </ul>
          {list.hasNextPage ? (
            <div className="mt-4 flex justify-center">
              <Button variant="outline" disabled={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>
                {intl.formatMessage(nm.loadMore)}
              </Button>
            </div>
          ) : null}
        </>
      )}
    </section>
  )
}
