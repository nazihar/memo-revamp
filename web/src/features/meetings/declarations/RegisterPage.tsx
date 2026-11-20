import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { FileSpreadsheet } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useIntl } from 'react-intl'
import {
  listDeclarationsInfiniteOptions,
  listForumMembersOptions,
  listForumsOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import { exportDeclarations } from '@/api/generated/sdk.gen'
import type { DeclarationRegisterRowResponse, ListDeclarationsData } from '@/api/generated/types.gen'
import { isProblem, problemType, PROBLEM } from '@/api/http'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { FormGrid } from '@/components/page/FormPage'
import { PageHeader } from '@/components/page/PageHeader'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { holds, useMyAccess } from '@/features/access/access'
import { om, omv } from './officer-messages'
import { itemLabel, originWords, rulingWords, stateWords } from './officers'
import { gm, gmv } from './register-messages'

type State = DeclarationRegisterRowResponse['state']
type Query = NonNullable<ListDeclarationsData['query']>

const STATES: State[] = ['RECUSED', 'AWAITING_RULING', 'TAKING_PART', 'WITHDRAWN']
const OFFICER_SEATS = new Set(['SECRETARY', 'CHAIR', 'VICE_CHAIR'])
const DAY = /^\d{4}-\d{2}-\d{2}$/

/** The meeting's day (a local date of the meeting's zone), as the reader's locale writes it. */
const dayOf = (locale: string, day: string) =>
  new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' }).format(
    new Date(`${day}T12:00:00Z`),
  )

/**
 * `/declarations` (Phase 4 E04 T8, flow U): the register of declarations of interest across committees, for auditors
 * with oversight access, administrators, and each committee's secretaries and chairs (the server decides; anyone else
 * reads why). Filters by committee, dates, member and state; 50 rows a page with "Load more" (keyset); each row's
 * details say how it was made and decided; "Export to Excel" downloads the rows under the filters (rate-limited and
 * audited by the server). Titles and interests the reader may not see read "Restricted item" and "(restricted item)".
 */
export function RegisterPage({ forumId: initialForum }: { forumId?: string | undefined }) {
  const intl = useIntl()
  const toast = useToast()
  const access = useMyAccess()
  const forums = useQuery(listForumsOptions())
  const [forumId, setForumId] = useState(initialForum ?? '')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [userId, setUserId] = useState('')
  const [state, setState] = useState<State | ''>('')
  const [exporting, setExporting] = useState(false)
  const members = useQuery({
    ...listForumMembersOptions({ path: { forumId }, query: { history: true } }),
    enabled: forumId !== '',
  })

  const overseer = holds(access.data, 'forum.read') || holds(access.data, 'forums.manage')
  const committees = (forums.data ?? [])
    .filter((f) => overseer || (f.myRole !== undefined && OFFICER_SEATS.has(f.myRole)) || f.id === forumId)
    .sort((a, b) => a.name.localeCompare(b.name, intl.locale))
  const people = useMemo(() => {
    const map = new Map<string, string>()
    for (const m of members.data ?? []) if (m.person) map.set(m.person.id, m.person.name)
    return [...map.entries()].sort((a, b) => a[1].localeCompare(b[1], intl.locale))
  }, [members.data, intl.locale])

  const query: Query = {
    ...(forumId ? { forumId } : {}),
    ...(DAY.test(from) ? { from } : {}),
    ...(DAY.test(to) ? { to } : {}),
    ...(userId ? { userId } : {}),
    ...(state ? { state } : {}),
  }
  const register = useInfiniteQuery({
    ...listDeclarationsInfiniteOptions({ query }),
    initialPageParam: { query },
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
  const rows = register.data?.pages.flatMap((p) => p.items) ?? []
  const refused = register.isError && isProblem(register.error) ? register.error.status : undefined

  const download = async () => {
    setExporting(true)
    try {
      const { data } = await exportDeclarations({ query, parseAs: 'blob', throwOnError: true })
      const url = URL.createObjectURL(data)
      const link = document.createElement('a')
      link.href = url
      link.download = `declarations-${new Date().toISOString().slice(0, 10)}.xlsx`
      link.click()
      URL.revokeObjectURL(url)
      toast.show(intl.formatMessage(gm.exported), 'success')
    } catch (problem) {
      const limited = problemType(problem) === PROBLEM.rateLimited || (isProblem(problem) && problem.status === 429)
      const forbidden = isProblem(problem) && problem.status === 403
      toast.show(intl.formatMessage(limited ? gm.tooMany : forbidden ? gm.forbidden : gm.exportFailed), 'error')
    } finally {
      setExporting(false)
    }
  }

  const columns: DataTableColumn<DeclarationRegisterRowResponse>[] = [
    {
      id: 'meeting',
      header: intl.formatMessage(gm.colMeeting),
      cell: ({ row: { original: r } }) => (
        <span className="flex flex-col">
          <span className="font-medium">
            {r.meetingLabel ?? intl.formatMessage(gmv.meetingOf, { committee: r.committee })}
          </span>
          <span className="text-muted-foreground">{dayOf(intl.locale, r.meetingDate)}</span>
        </span>
      ),
    },
    {
      id: 'item',
      header: intl.formatMessage(gm.colItem),
      cell: ({ row: { original: r } }) => <span dir="auto">{itemLabel(intl, r.itemNumber, r.itemTitle)}</span>,
    },
    {
      id: 'member',
      header: intl.formatMessage(gm.colMember),
      cell: ({ row: { original: r } }) => (
        <span className="flex flex-col">
          <span className="font-medium">{r.user.name}</span>
          <span className="text-muted-foreground">{originWords(intl, r)}</span>
        </span>
      ),
    },
    { id: 'what', header: intl.formatMessage(gm.colWhat), cell: ({ row }) => stateWords(intl, row.original.state) },
    {
      id: 'interest',
      header: intl.formatMessage(gm.colInterest),
      cell: ({ row: { original: r } }) =>
        r.nature ? (
          <span dir="auto" className="line-clamp-3 whitespace-pre-line">
            {r.nature}
          </span>
        ) : (
          <span className="text-muted-foreground">{intl.formatMessage(gm.restrictedInterest)}</span>
        ),
    },
    {
      id: 'decision',
      header: intl.formatMessage(gm.colDecision),
      cell: ({ row: { original: r } }) => (
        <span className="flex flex-col gap-1">
          <span>{r.ruling ? rulingWords(intl, r.ruling) : intl.formatMessage(gm.noDecision)}</span>
          <Details row={r} />
        </span>
      ),
    },
  ]

  return (
    <section aria-labelledby="declarations-register-title" className="flex flex-col gap-4">
      <PageHeader
        title={<span id="declarations-register-title">{intl.formatMessage(gm.title)}</span>}
        description={intl.formatMessage(gm.lead)}
        actions={
          <Button className="min-h-11" disabled={exporting || refused === 403} onClick={() => void download()}>
            <FileSpreadsheet aria-hidden="true" />
            {intl.formatMessage(gm.export)}
          </Button>
        }
      />
      <p className="text-sm text-muted-foreground">{intl.formatMessage(gm.exportHint)}</p>
      <form
        aria-label={intl.formatMessage(gm.filters)}
        onSubmit={(e) => {
          e.preventDefault()
        }}
      >
        <FormGrid>
          <Field label={intl.formatMessage(gm.committee)}>
            {(p) => (
              <Select
                {...p}
                className="min-h-11"
                value={forumId}
                onChange={(e) => {
                  setForumId(e.target.value)
                  setUserId('')
                }}
              >
                <option value="">{intl.formatMessage(gm.allCommittees)}</option>
                {committees.map((f) => (
                  <option key={f.id} value={f.id}>
                    {f.name}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label={intl.formatMessage(gm.from)}>
            {(p) => (
              <Input
                {...p}
                type="date"
                className="min-h-11"
                value={from}
                onChange={(e) => {
                  setFrom(e.target.value)
                }}
              />
            )}
          </Field>
          <Field label={intl.formatMessage(gm.to)}>
            {(p) => (
              <Input
                {...p}
                type="date"
                className="min-h-11"
                value={to}
                onChange={(e) => {
                  setTo(e.target.value)
                }}
              />
            )}
          </Field>
          <Field label={intl.formatMessage(gm.member)} hint={forumId ? undefined : intl.formatMessage(gm.memberHint)}>
            {(p) => (
              <Select
                {...p}
                className="min-h-11"
                disabled={!forumId}
                value={userId}
                onChange={(e) => {
                  setUserId(e.target.value)
                }}
              >
                <option value="">{intl.formatMessage(gm.anyMember)}</option>
                {people.map(([id, name]) => (
                  <option key={id} value={id}>
                    {name}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label={intl.formatMessage(gm.state)}>
            {(p) => (
              <Select
                {...p}
                className="min-h-11"
                value={state}
                onChange={(e) => {
                  setState(STATES.find((s) => s === e.target.value) ?? '')
                }}
              >
                <option value="">{intl.formatMessage(gm.anyState)}</option>
                {STATES.map((s) => (
                  <option key={s} value={s}>
                    {stateWords(intl, s)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </FormGrid>
      </form>
      {refused === 403 ? (
        <p role="alert" className="rounded-md border p-4 text-lg">
          {intl.formatMessage(gm.forbidden)}
        </p>
      ) : refused === 404 ? (
        <p role="alert" className="rounded-md border p-4 text-lg">
          {intl.formatMessage(gm.notFound)}
        </p>
      ) : (
        <DataTable
          caption={intl.formatMessage(gm.caption)}
          columns={columns}
          rows={rows}
          getRowId={(r) => r.id}
          state={register.isPending ? 'loading' : register.isError ? 'error' : 'ready'}
          error={{ onRetry: () => void register.refetch() }}
          empty={<p className="p-4 text-muted-foreground">{intl.formatMessage(gm.empty)}</p>}
        />
      )}
      {register.hasNextPage ? (
        <Button
          variant="outline"
          className="min-h-11 self-center"
          disabled={register.isFetchingNextPage}
          onClick={() => void register.fetchNextPage()}
        >
          {intl.formatMessage(gm.loadMore)}
        </Button>
      ) : null}
    </section>
  )
}

/** A row's history as the register holds it: how it was made, by whom, and the decision with its note (flow U1). */
function Details({ row: r }: { row: DeclarationRegisterRowResponse }) {
  const intl = useIntl()
  return (
    <details>
      <summary
        className="inline-flex min-h-11 cursor-pointer items-center text-primary"
        aria-label={intl.formatMessage(gmv.details, {
          member: r.user.name,
          item: itemLabel(intl, r.itemNumber, r.itemTitle),
        })}
      >
        {intl.formatMessage(gm.details)}
      </summary>
      <ul className="mt-1 flex max-w-80 flex-col gap-1 text-sm">
        <li>{originWords(intl, r)}</li>
        {r.atMeeting ? <li>{intl.formatMessage(gm.atMeeting)}</li> : null}
        {r.correction ? <li>{intl.formatMessage(om.correction)}</li> : null}
        <li>{stateWords(intl, r.state)}</li>
        {r.ruling ? (
          <li>
            {rulingWords(intl, r.ruling)}
            {r.ruling.note ? <> {intl.formatMessage(omv.rulingNote, { note: r.ruling.note })}</> : null}
          </li>
        ) : null}
      </ul>
    </details>
  )
}
