import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Ellipsis, UserPlus } from 'lucide-react'
import { useCallback, useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getForumOptions,
  getForumPolicyOptions,
  listClassificationLevelsOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import type { ForumPolicyResponse, ForumResponse, MemberResponse } from '@/api/generated/types.gen'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/misc'
import { holds, useMyAccess } from '@/features/access/access'
import { ao } from '@/features/actions/officer/messages'
import { Tracker } from '@/features/actions/officer/Tracker'
import { CommitteeDecisionsLink } from '@/features/decision-register/links'
import { MeetingsTab } from '@/features/meetings/MeetingsTab'
import { rsm } from '@/features/resolutions/messages'
import { readsRegister } from '@/features/resolutions/register'
import { ResolutionsTab } from '@/features/resolutions/ResolutionsTab'
import { mm } from '@/features/meetings/messages'
import { useTerms } from '@/features/terms/terms'
import { CloneForumDialog, DissolveForumDialog, EditForumDialog } from './ForumDialogs'
import { FORUM_TABS, type ForumTab } from './forum-tabs'
import { HistoryTab } from './HistoryTab'
import { ChangeRoleDialog, EndDialog, RenewDialog, WithdrawDialog } from './MemberDialogs'
import { MembersTab } from './MembersTab'
import { MemberSheet } from './MemberSheet'
import { fm, fmt, fmv } from './messages'
import { PolicyTab } from './PolicyTab'
import { sm, smv } from './submission-messages'
import { seesPapers } from './submissions'
import { openPapersOptions } from './papers'
import { SubmissionsTab } from './SubmissionsTab'
import { formatDay, today } from './shared'

/** What a row menu or the page menu opened. */
export type MemberAction = 'renew' | 'changeRole' | 'edit' | 'end' | 'withdraw'

type Open =
  { kind: 'add' } | { kind: 'clone' | 'editForum' | 'dissolve' } | { kind: MemberAction; member: MemberResponse }

/**
 * `/committees/$forumId` (flow K2, ADR-0056): the committee's summary, its members on a chosen date with the quorum
 * that day, its meetings (Phase 4 E03, flow N1), every term of office in History, and the type's published policy to read. Administrators and the
 * committee's secretaries add and change members; administrators also clone, edit and dissolve.
 */
export function ForumPage({
  forumId,
  tab,
  onTab,
}: {
  forumId: string
  tab?: ForumTab | undefined
  onTab: (tab: ForumTab) => void
}) {
  const intl = useIntl()
  const terms = useTerms()
  const forum = useQuery(getForumOptions({ path: { forumId } }))
  const [on, setOn] = useState(today())
  const policy = useQuery({ ...getForumPolicyOptions({ path: { forumId }, query: { on } }), enabled: Boolean(on) })
  const [open, setOpen] = useState<Open>()
  const officer = forum.data ? seesPapers(forum.data) : false
  const papers = useQuery({ ...openPapersOptions(forumId), enabled: officer })
  const access = useMyAccess()
  // Stable, so the member tables keep their columns (and an open row menu) across renders.
  const act = useCallback((kind: MemberAction, member: MemberResponse) => {
    setOpen({ kind, member })
  }, [])

  if (forum.isPending) return <LoadingState />
  if (forum.isError) return <ErrorState onRetry={() => void forum.refetch()} />
  const f = forum.data
  const close = () => {
    setOpen(undefined)
  }
  // The resolution register (E06 T10, flow AC1): the officers, forums.manage and forum.read.
  const register = readsRegister(f, holds(access.data, 'forum.read'))
  // The action tracker (E07 T10, flow AN): its officers, members, forum.read and forums.manage; the server decides the rows.
  const tracker = Boolean(f.myRole) || f.canManage || holds(access.data, 'forum.read')

  return (
    <section aria-labelledby="forum-title" className="flex flex-col gap-4">
      <PageHeader
        className="mb-0"
        title={<span id="forum-title">{intl.formatMessage(fmv.nameWithCode, { name: f.name, code: f.code })}</span>}
        status={<Badge tone={f.status === 'ACTIVE' ? 'ok' : 'neutral'}>{intl.formatMessage(fm[f.status])}</Badge>}
        description={<Summary forum={f} policy={policy.data} />}
        actions={
          <>
            <CommitteeDecisionsLink forum={f} />
            {f.canManageMembers ? (
              <Button
                onClick={() => {
                  setOpen({ kind: 'add' })
                }}
              >
                <UserPlus aria-hidden="true" />
                {intl.formatMessage(fm.addMember)}
              </Button>
            ) : null}
            {f.canManage ? (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="outline" size="icon" aria-label={intl.formatMessage(fm.more)}>
                    <Ellipsis aria-hidden="true" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  <DropdownMenuItem
                    onSelect={() => {
                      setOpen({ kind: 'clone' })
                    }}
                  >
                    {intl.formatMessage(fm.clone)}
                  </DropdownMenuItem>
                  {f.status === 'ACTIVE' ? (
                    <>
                      <DropdownMenuItem
                        onSelect={() => {
                          setOpen({ kind: 'editForum' })
                        }}
                      >
                        {intl.formatMessage(fm.edit)}
                      </DropdownMenuItem>
                      <DropdownMenuItem
                        onSelect={() => {
                          setOpen({ kind: 'dissolve' })
                        }}
                      >
                        {intl.formatMessage(fm.dissolve)}
                      </DropdownMenuItem>
                    </>
                  ) : null}
                </DropdownMenuContent>
              </DropdownMenu>
            ) : null}
          </>
        }
      />
      <Link to="/committees" className="w-fit text-sm underline-offset-4 hover:underline">
        {intl.formatMessage(fmt.back, terms)}
      </Link>
      <Tabs
        value={
          tab &&
          FORUM_TABS.includes(tab) &&
          (tab !== 'submissions' || officer) &&
          (tab !== 'resolutions' || register) &&
          (tab !== 'actions' || tracker)
            ? tab
            : 'members'
        }
        onValueChange={(value) => {
          const next = FORUM_TABS.find((t) => t === value)
          if (next) onTab(next)
        }}
      >
        <TabsList aria-label={intl.formatMessage(fmt.tabs, terms)}>
          <TabsTrigger value="members">{intl.formatMessage(fm.tabMembers)}</TabsTrigger>
          <TabsTrigger value="meetings">{intl.formatMessage(mm.tab)}</TabsTrigger>
          {officer ? (
            <TabsTrigger value="submissions">
              {papers.data?.length
                ? intl.formatMessage(smv.tabCount, { count: papers.data.length })
                : intl.formatMessage(sm.tab)}
            </TabsTrigger>
          ) : null}
          {register ? <TabsTrigger value="resolutions">{intl.formatMessage(rsm.tab)}</TabsTrigger> : null}
          {tracker ? <TabsTrigger value="actions">{intl.formatMessage(ao.tab)}</TabsTrigger> : null}
          <TabsTrigger value="history">{intl.formatMessage(fm.tabHistory)}</TabsTrigger>
          <TabsTrigger value="policy">{intl.formatMessage(fm.tabPolicy)}</TabsTrigger>
        </TabsList>
        <TabsContent value="members" className="pt-4">
          <MembersTab
            forum={f}
            policy={policy.data}
            policyPending={policy.isPending}
            on={on}
            onDate={setOn}
            onAction={act}
          />
        </TabsContent>
        <TabsContent value="meetings" className="pt-4">
          <MeetingsTab forum={f} policy={policy.data} />
        </TabsContent>
        {officer ? (
          <TabsContent value="submissions" className="pt-4">
            <SubmissionsTab forum={f} policy={policy.data} />
          </TabsContent>
        ) : null}
        {register ? (
          <TabsContent value="resolutions" className="pt-4">
            <ResolutionsTab forum={f} />
          </TabsContent>
        ) : null}
        {tracker ? (
          <TabsContent value="actions" className="pt-4">
            <Tracker forum={f} />
          </TabsContent>
        ) : null}
        <TabsContent value="history" className="pt-4">
          <HistoryTab forum={f} onAction={act} />
        </TabsContent>
        <TabsContent value="policy" className="pt-4">
          {policy.isPending ? (
            <LoadingState />
          ) : policy.isError ? (
            <ErrorState onRetry={() => void policy.refetch()} />
          ) : (
            <PolicyTab policy={policy.data} />
          )}
        </TabsContent>
      </Tabs>
      {open?.kind === 'add' ? <MemberSheet forum={f} policy={policy.data} onClose={close} /> : null}
      {open?.kind === 'edit' ? (
        <MemberSheet forum={f} policy={policy.data} member={open.member} onClose={close} />
      ) : null}
      {open?.kind === 'renew' ? (
        <RenewDialog forum={f} member={open.member} policy={policy.data} onClose={close} />
      ) : null}
      {open?.kind === 'changeRole' ? <ChangeRoleDialog forum={f} member={open.member} onClose={close} /> : null}
      {open?.kind === 'end' ? <EndDialog forum={f} member={open.member} onClose={close} /> : null}
      {open?.kind === 'withdraw' ? <WithdrawDialog forum={f} member={open.member} onClose={close} /> : null}
      {open?.kind === 'clone' ? <CloneForumDialog forum={f} onClose={close} /> : null}
      {open?.kind === 'editForum' ? <EditForumDialog forum={f} onClose={close} /> : null}
      {open?.kind === 'dissolve' ? <DissolveForumDialog forum={f} onClose={close} /> : null}
    </section>
  )
}

/**
 * "Board committee · Confidential · Reports to: Board", then the secretaries, then how it was dissolved, if it was. The
 * classification is the committee's own, else its type's default.
 */
function Summary({ forum, policy }: { forum: ForumResponse; policy?: ForumPolicyResponse }) {
  const intl = useIntl()
  const levels = useQuery(listClassificationLevelsOptions())
  const names = forum.secretaries.map((s) => s.name)
  const classificationId = forum.classificationId ?? policy?.defaultClassificationId
  const classification = levels.data?.find((l) => l.id === classificationId)?.name
  const line = [
    forum.typeName,
    classification,
    forum.parentName ? intl.formatMessage(fmv.reportsTo, { name: forum.parentName }) : undefined,
  ]
    .filter(Boolean)
    .join(' · ')
  return (
    <span className="flex flex-col gap-1">
      <span>{line}</span>
      <span>
        {names.length
          ? intl.formatMessage(fmv.secretaries, { count: names.length, names: intl.formatList(names) })
          : intl.formatMessage(fm.noSecretary)}
      </span>
      {forum.status === 'DISSOLVED' && forum.dissolvedOn ? (
        <span>
          {intl.formatMessage(fmv.dissolved, {
            date: formatDay(intl, forum.dissolvedOn),
            reason: forum.dissolvedReason ?? '',
          })}
        </span>
      ) : null}
    </span>
  )
}
