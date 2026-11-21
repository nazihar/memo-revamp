import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react'
import { getMyDeclarationsOptions, getMyDeclarationsQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import type { DeclarationResponse, MemberAgendaResponse } from '@/api/generated/types.gen'
import { DeclarationsContext as Context, type DeclarationsValue } from './context'
import { DeclareSheet } from './DeclareSheet'
import { restoreFocus } from './focus'
import { MyDeclarationSheet } from './MyDeclarationSheet'
import { ReviewSheet } from './ReviewSheet'
import { declaring } from './words'

type Sheet =
  | { kind: 'declare'; itemId: string; name: string; opener: HTMLElement | null }
  | { kind: 'mine'; declaration: DeclarationResponse; name: string; opener: HTMLElement | null }

/**
 * Declaring an interest on the meeting page (Phase 4 E04 T6, flow S): reads the reader's declarations and the items to
 * review, and holds the three sheets — declare (S2), review (S3) and My declaration (S4) — so that the agenda's items,
 * the Declarations panel and the review list open the same ones. After every change the agenda and the reader's
 * declarations are read again, since a declaration withholds the item at once (ADR-0062).
 */
export function DeclarationsProvider({
  agenda,
  enabled,
  children,
}: {
  agenda: MemberAgendaResponse
  enabled: boolean
  children: ReactNode
}) {
  const meetingId = agenda.meetingId
  const queryClient = useQueryClient()
  const mine = useQuery({ ...getMyDeclarationsOptions({ path: { meetingId } }), enabled })
  const [sheet, setSheet] = useState<Sheet | null>(null)
  const [reviewing, setReviewing] = useState(false)
  const reviewOpener = useRef<HTMLElement | null>(null)

  const refresh = useCallback(async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: getMyDeclarationsQueryKey({ path: { meetingId } }) }),
      queryClient.invalidateQueries({
        predicate: (query) => {
          const k = query.queryKey[0] as { _id?: string; path?: { meetingId?: string } } | undefined
          return k?._id === 'getMeetingAgenda' && k.path?.meetingId === meetingId
        },
      }),
    ])
  }, [queryClient, meetingId])

  const value = useMemo<DeclarationsValue>(
    () => ({
      meetingId,
      timeZone: agenda.timeZone,
      startsAt: agenda.startsAt,
      open: declaring(agenda.status),
      mine: mine.data,
      declare: (item, opener) => {
        setSheet({ kind: 'declare', ...item, opener })
      },
      show: (declaration, name, opener) => {
        setSheet({ kind: 'mine', declaration, name, opener })
      },
      review: (opener) => {
        reviewOpener.current = opener
        setReviewing(true)
      },
    }),
    [meetingId, agenda.timeZone, agenda.startsAt, agenda.status, mine.data],
  )

  if (!enabled) return <>{children}</>

  const closeSheet = () => {
    const opener = sheet?.opener ?? null
    setSheet(null)
    restoreFocus(opener)
  }
  const closeReview = () => {
    setReviewing(false)
    restoreFocus(reviewOpener.current)
  }
  // The sheet reads the declaration as last loaded, so a change made elsewhere (or a 412) shows its latest version.
  const current =
    sheet?.kind === 'mine'
      ? (mine.data?.declarations.find((d) => d.id === sheet.declaration.id) ?? sheet.declaration)
      : undefined

  return (
    <Context.Provider value={value}>
      {children}
      {reviewing && mine.data ? (
        <ReviewSheet
          mine={mine.data}
          startsAt={agenda.startsAt}
          timeZone={agenda.timeZone}
          onDeclare={value.declare}
          onShow={value.show}
          refresh={refresh}
          onConfirmed={(answer) => {
            queryClient.setQueryData(getMyDeclarationsQueryKey({ path: { meetingId } }), answer)
            closeReview()
          }}
          onClose={closeReview}
        />
      ) : null}
      {sheet?.kind === 'declare' ? (
        <DeclareSheet
          meetingId={meetingId}
          itemId={sheet.itemId}
          name={sheet.name}
          refresh={refresh}
          onDone={closeSheet}
          onClose={closeSheet}
        />
      ) : null}
      {sheet?.kind === 'mine' && current ? (
        <MyDeclarationSheet
          declaration={current}
          name={sheet.name}
          open={value.open}
          timeZone={agenda.timeZone}
          refresh={refresh}
          onClose={closeSheet}
        />
      ) : null}
    </Context.Provider>
  )
}
