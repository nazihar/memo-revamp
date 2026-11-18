import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { changeForumMemberRole, endForumMember, renewForumMember, withdrawForumMember } from '@/api/generated/sdk.gen'
import type { ForumPolicyResponse, ForumResponse, MemberResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { AlertDialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select, Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useTerms } from '@/features/terms/terms'
import { FormDialog } from './FormDialog'
import { fm, fmv } from './messages'
import {
  ADMIN_ROLES,
  END_REASONS,
  ROLES,
  addDays,
  fieldProblems,
  formatDay,
  memberName,
  refreshForums,
  refusalMessage,
  secretaryOnly,
  termEnd,
  today,
  votesByDefault,
  type EndReason,
  type Role,
} from './shared'

interface Props {
  forum: ForumResponse
  member: MemberResponse
  onClose: () => void
}

/** Runs a member change, reloads the committee, and turns a refusal into words for the form. */
function useChange() {
  const intl = useIntl()
  const terms = useTerms()
  const toast = useToast()
  const queryClient = useQueryClient()
  return async (
    call: () => Promise<unknown>,
    onFields: (fields: Record<string, string>) => void,
    onDone: () => void,
  ): Promise<string | undefined> => {
    try {
      await call()
      await refreshForums(queryClient)
      toast.show(intl.formatMessage(fm.saved), 'success')
      onDone()
      return undefined
    } catch (problem) {
      if (isProblem(problem) && problem.status === 412) await refreshForums(queryClient)
      const fields = fieldProblems(intl, terms, problem)
      onFields(fields)
      return Object.keys(fields).length ? undefined : refusalMessage(intl, terms, problem)
    }
  }
}

/**
 * Renew (flow K4): "Term 3 starts on 1 Jul 2028, ending …". The next term starts the day after this one ends, as a new
 * row; this row is kept. Passing the type's most-consecutive-terms limit warns, never blocks.
 */
export function RenewDialog({ forum, member, policy, onClose }: Props & { policy?: ForumPolicyResponse }) {
  const intl = useIntl()
  const toast = useToast()
  const change = useChange()
  const start = member.termEndsOn ? addDays(member.termEndsOn, 1) : today()
  const months = policy?.termPolicy.termMonths ?? undefined
  const max = policy?.termPolicy.maxTerms ?? undefined
  const next = member.termNumber + 1
  const [ends, setEnds] = useState(months ? termEnd(start, months) : '')
  const [authority, setAuthority] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})

  const submit = () =>
    change(
      async () => {
        const renewed = await renewForumMember({
          path: { forumId: forum.id, memberId: member.id },
          body: { ...(ends ? { termEndsOn: ends } : {}), ...(authority.trim() ? { authority: authority.trim() } : {}) },
          throwOnError: true,
        })
        if (renewed.data.beyondMaxTerms && max) toast.show(intl.formatMessage(fmv.renewedBeyond, { max }))
      },
      setErrors,
      onClose,
    )

  return (
    <FormDialog
      title={intl.formatMessage(fmv.renewTitle, { name: memberName(intl, member) })}
      description={intl.formatMessage(fmv.renewLead, { n: next, date: formatDay(intl, start) })}
      submitLabel={intl.formatMessage(fm.renewSubmit)}
      onSubmit={submit}
      onClose={onClose}
    >
      {max && next > max ? (
        <p
          role="note"
          className="rounded-md border border-status-warn-fg/30 bg-status-warn-bg p-3 text-sm text-status-warn-fg"
        >
          {intl.formatMessage(fmv.renewBeyond, { n: next, max })}
        </p>
      ) : null}
      <Field
        label={intl.formatMessage(fm.renewEnds)}
        hint={months ? intl.formatMessage(fmv.termFromType, { months }) : intl.formatMessage(fm.termEndsOpenHint)}
        error={errors.termEndsOn}
      >
        {(p) => (
          <Input
            {...p}
            type="date"
            className="w-48"
            value={ends}
            onChange={(e) => {
              setEnds(e.target.value)
            }}
          />
        )}
      </Field>
      <Field
        label={intl.formatMessage(fm.authority)}
        hint={intl.formatMessage(fm.authorityHint)}
        optionalLabel={intl.formatMessage(fm.optional)}
        error={errors.authority}
      >
        {(p) => (
          <Input
            {...p}
            maxLength={500}
            value={authority}
            onChange={(e) => {
              setAuthority(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}

/** Change role (flow K4): the new role, its first day and whether it votes. The term continues on a new row. */
export function ChangeRoleDialog({ forum, member, onClose }: Props) {
  const intl = useIntl()
  const change = useChange()
  const limited = secretaryOnly(forum)
  const roles = ROLES.filter((r) => r !== member.role && (!limited || !ADMIN_ROLES.includes(r)))
  const earliest = addDays(member.startsOn, 1)
  const [role, setRole] = useState<Role>(roles[0] ?? 'MEMBER')
  const [from, setFrom] = useState(today() < earliest ? earliest : today())
  const [voting, setVoting] = useState(votesByDefault(roles[0] ?? 'MEMBER'))
  const [errors, setErrors] = useState<Record<string, string>>({})

  const submit = () =>
    change(
      () =>
        changeForumMemberRole({
          path: { forumId: forum.id, memberId: member.id },
          body: { role, voting, ...(from ? { from } : {}) },
          throwOnError: true,
        }),
      setErrors,
      onClose,
    )

  return (
    <FormDialog
      title={intl.formatMessage(fmv.changeRoleTitle, { name: memberName(intl, member) })}
      description={intl.formatMessage(fm.changeRoleLead)}
      submitLabel={intl.formatMessage(fm.changeRoleSubmit)}
      onSubmit={submit}
      onClose={onClose}
    >
      <Field
        label={intl.formatMessage(fm.newRole)}
        hint={limited ? intl.formatMessage(fm.secretaryHint) : undefined}
        error={errors.role}
      >
        {(p) => (
          <Select
            {...p}
            className="w-64"
            value={role}
            onChange={(e) => {
              const next = e.target.value as Role
              setRole(next)
              setVoting(votesByDefault(next))
            }}
          >
            {roles.map((r) => (
              <option key={r} value={r}>
                {intl.formatMessage(fm[r])}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Field label={intl.formatMessage(fm.newRoleFrom)} error={errors.from}>
        {(p) => (
          <Input
            {...p}
            type="date"
            className="w-48"
            min={earliest}
            value={from}
            onChange={(e) => {
              setFrom(e.target.value)
            }}
          />
        )}
      </Field>
      <div className="flex flex-col gap-1.5">
        <label className="flex min-h-11 items-center gap-3 text-sm font-medium">
          <Checkbox
            className="size-5"
            aria-describedby="change-role-votes-hint"
            checked={voting}
            onChange={(e) => {
              setVoting(e.target.checked)
            }}
          />
          {intl.formatMessage(fm.votes)}
        </label>
        <p id="change-role-votes-hint" className="text-xs text-muted-foreground">
          {intl.formatMessage(fm.votesHint)}
        </p>
      </div>
    </FormDialog>
  )
}

/** End (flow K4): the last day and why (Resigned, Term completed, Removed, Other with words). The row is kept. */
export function EndDialog({ forum, member, onClose }: Props) {
  const intl = useIntl()
  const change = useChange()
  const [on, setOn] = useState(today() < member.startsOn ? member.startsOn : today())
  const [reason, setReason] = useState<EndReason>('RESIGNED')
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})

  const submit = () => {
    if (reason === 'OTHER' && !note.trim()) {
      setErrors({ note: intl.formatMessage(fm.required) })
      return Promise.resolve(undefined)
    }
    setErrors({})
    return change(
      () =>
        endForumMember({
          path: { forumId: forum.id, memberId: member.id },
          headers: { 'If-Match': `"${String(member.version)}"` },
          body: { reason, ...(on ? { endedOn: on } : {}), ...(note.trim() ? { note: note.trim() } : {}) },
          throwOnError: true,
        }),
      setErrors,
      onClose,
    )
  }

  return (
    <FormDialog
      title={intl.formatMessage(fmv.endTitle, { name: memberName(intl, member) })}
      description={intl.formatMessage(fm.endLead)}
      submitLabel={intl.formatMessage(fm.endSubmit)}
      destructive
      onSubmit={submit}
      onClose={onClose}
    >
      <Field label={intl.formatMessage(fm.lastDay)} error={errors.endedOn}>
        {(p) => (
          <Input
            {...p}
            type="date"
            className="w-48"
            min={member.startsOn}
            value={on}
            onChange={(e) => {
              setOn(e.target.value)
            }}
          />
        )}
      </Field>
      <fieldset className="flex flex-col gap-1">
        <legend className="mb-1 text-sm font-medium">{intl.formatMessage(fm.reason)}</legend>
        {END_REASONS.map((r) => (
          <label key={r} className="flex min-h-11 items-center gap-3 text-sm">
            <input
              type="radio"
              name="end-reason"
              className="size-5 accent-primary"
              checked={reason === r}
              onChange={() => {
                setReason(r)
              }}
            />
            {intl.formatMessage(fm[r])}
          </label>
        ))}
      </fieldset>
      <Field
        label={intl.formatMessage(fm.note)}
        hint={intl.formatMessage(fm.noteHint)}
        required={reason === 'OTHER'}
        optionalLabel={intl.formatMessage(fm.optional)}
        error={errors.note}
      >
        {(p) => (
          <Textarea
            {...p}
            rows={3}
            maxLength={500}
            value={note}
            onChange={(e) => {
              setNote(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}

/** Withdraw (row menu of an upcoming row): asks first; the row stays in the history as withdrawn. */
export function WithdrawDialog({ forum, member, onClose }: Props) {
  const intl = useIntl()
  const terms = useTerms()
  const toast = useToast()
  const queryClient = useQueryClient()
  const withdraw = async () => {
    try {
      await withdrawForumMember({ path: { forumId: forum.id, memberId: member.id }, throwOnError: true })
      await refreshForums(queryClient)
      toast.show(intl.formatMessage(fm.saved), 'success')
    } catch (problem) {
      toast.show(refusalMessage(intl, terms, problem), 'error')
    }
  }
  return (
    <AlertDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(fmv.withdrawTitle, { name: memberName(intl, member) })}
      description={intl.formatMessage(fm.withdrawLead)}
      confirmLabel={intl.formatMessage(fm.withdraw)}
      cancelLabel={intl.formatMessage(fm.cancel)}
      destructive
      onConfirm={() => void withdraw()}
    />
  )
}
