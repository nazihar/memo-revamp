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
import { useTerms } from '@/features/terms/terms'
import { CloneForumDialog, DissolveForumDialog, EditForumDialog } from './ForumDialogs'
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
/** The page's tabs; `?tab=submissions` opens the papers (notifications, a paper's page). */
export type ForumTab = 'members' | 'submissions' | 'history' | 'policy'

type Open =
  { kind: 'add' } | { kind: 'clone' | 'editForum' | 'dissolve' } | { kind: MemberAction; member: MemberResponse }

/**
 * `/committees/$forumId` (flow K2, ADR-0056): the committee's summary, its members on a chosen date with the quorum
 * that day, every term of office in History, and the type's published policy to read. Administrators and the
 * committee's secretaries add and change members; administrators also clone, edit and dissolve.
 */
export function ForumPage({ forumId, tab }: { forumId: string; tab?: ForumTab }) {
  const intl = useIntl()
  const terms = useTerms()
  const forum = useQuery(getForumOptions({ path: { forumId } }))
  const [on, setOn] = useState(today())
  const policy = useQuery({ ...getForumPolicyOptions({ path: { forumId }, query: { on } }), enabled: Boolean(on) })
  const [open, setOpen] = useState<Open>()
  const officer = forum.data ? seesPapers(forum.data) : false
  const papers = useQuery({ ...openPapersOptions(forumId), enabled: officer })
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

  return (
    <section aria-labelledby="forum-title" className="flex flex-col gap-4">
      <PageHeader
        className="mb-0"
        title={<span id="forum-title">{intl.formatMessage(fmv.nameWithCode, { name: f.name, code: f.code })}</span>}
        status={<Badge tone={f.status === 'ACTIVE' ? 'ok' : 'neutral'}>{intl.formatMessage(fm[f.status])}</Badge>}
        description={<Summary forum={f} policy={policy.data} />}
        actions={
          <>
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
      <Tabs defaultValue={tab === 'submissions' && !officer ? 'members' : (tab ?? 'members')}>
        <TabsList aria-label={intl.formatMessage(fmt.tabs, terms)}>
          <TabsTrigger value="members">{intl.formatMessage(fm.tabMembers)}</TabsTrigger>
          {officer ? (
            <TabsTrigger value="submissions">
              {papers.data?.length
                ? intl.formatMessage(smv.tabCount, { count: papers.data.length })
                : intl.formatMessage(sm.tab)}
            </TabsTrigger>
          ) : null}
          <TabsTrigger value="history">{intl.formatMessage(fm.tabHistory)}</TabsTrigger>
          <TabsTrigger value="policy">{intl.formatMessage(fm.tabPolicy)}</TabsTrigger>
        </TabsList>
        <TabsContent value="members" className="pt-4">
          <MembersTab forum={f} policy={policy.data} on={on} onDate={setOn} onAction={act} />
        </TabsContent>
        {officer ? (
          <TabsContent value="submissions" className="pt-4">
            <SubmissionsTab forum={f} policy={policy.data} />
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
