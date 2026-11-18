import { useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { useIntl } from 'react-intl'
import { addForumMember, editForumMember } from '@/api/generated/sdk.gen'
import type { ForumPolicyResponse, ForumResponse, MemberResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { FormDialog } from './FormDialog'
import { fm, fmv } from './messages'
import {
  ADMIN_ROLES,
  ROLES,
  fieldProblems,
  memberName,
  refreshForums,
  refusalMessage,
  secretaryOnly,
  termEnd,
  today,
  votesByDefault,
  type Role,
} from './shared'

/** A labelled people or position picker: the picker is a combobox, so it gets its label through `id`. */
export function PickerField({
  label,
  hint,
  error,
  optional,
  kind,
  value,
  onChange,
}: {
  label: string
  hint?: string
  error?: string
  optional?: boolean
  kind: 'user' | 'position'
  value?: Picked
  onChange: (picked: Picked | undefined) => void
}) {
  const intl = useIntl()
  const id = useId()
  const hintId = hint ? `${id}-hint` : undefined
  const errorId = error ? `${id}-error` : undefined
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
        {optional ? (
          <span className="font-normal text-muted-foreground"> {intl.formatMessage(fm.optional)}</span>
        ) : null}
      </label>
      <PeoplePicker
        id={id}
        kind={kind}
        value={value}
        onChange={onChange}
        invalid={Boolean(error)}
        describedBy={[hintId, errorId].filter(Boolean).join(' ') || undefined}
      />
      {hint ? (
        <p id={hintId} className="text-xs text-muted-foreground">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} className="text-xs font-medium text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  )
}

/**
 * "Add member" (flow K3), a side sheet (full screen on a phone): a person or a position, the role, whether they vote
 * (from the role), the type's attributes, an alternate, the term and the authority. With [member] it edits that row
 * instead: vote, attributes, alternate, planned term end and authority (If-Match on the row's version). A secretary is
 * not offered Chair or Secretary, and is told why.
 */
export function MemberSheet({
  forum,
  policy,
  member,
  onClose,
}: {
  forum: ForumResponse
  policy?: ForumPolicyResponse
  member?: MemberResponse
  onClose: () => void
}) {
  const intl = useIntl()
  const terms = useTerms()
  const toast = useToast()
  const queryClient = useQueryClient()
  const limited = secretaryOnly(forum)
  const roles = ROLES.filter((r) => !limited || !ADMIN_ROLES.includes(r))
  const months = policy?.termPolicy.termMonths ?? undefined
  const typeAttributes = (policy?.memberAttributes ?? []).flatMap((a) =>
    a.key ? [{ key: a.key, label: a.label ?? a.key }] : [],
  )
  const [kind, setKind] = useState<'PERSON' | 'POSITION'>('PERSON')
  const [person, setPerson] = useState<Picked>()
  const [position, setPosition] = useState<Picked>()
  const [role, setRole] = useState<Role>(member?.role ?? 'MEMBER')
  const [voting, setVoting] = useState(member?.voting ?? true)
  const [attributes, setAttributes] = useState<string[]>(member?.attributes ?? [])
  const [alternate, setAlternate] = useState<Picked | undefined>(
    member?.alternate ? { id: member.alternate.id, label: member.alternate.name } : undefined,
  )
  const [startsOn, setStartsOn] = useState(today())
  const [termEndsOn, setTermEndsOn] = useState(
    member ? (member.termEndsOn ?? '') : months ? termEnd(today(), months) : '',
  )
  const [endTyped, setEndTyped] = useState(Boolean(member))
  const [authority, setAuthority] = useState(member?.authority ?? '')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [idempotencyKey] = useState(newIdempotencyKey)

  const changeStart = (day: string) => {
    setStartsOn(day)
    if (!endTyped && months && day) setTermEndsOn(termEnd(day, months))
  }

  const submit = async () => {
    const found: Record<string, string> = {}
    if (!member && kind === 'PERSON' && !person) found.userId = intl.formatMessage(fm.pickPerson)
    if (!member && kind === 'POSITION' && !position) found.positionId = intl.formatMessage(fm.pickPosition)
    if (!member && !startsOn) found.startsOn = intl.formatMessage(fm.required)
    setErrors(found)
    if (Object.keys(found).length) return undefined
    const common = {
      voting,
      attributes,
      ...(alternate ? { alternateUserId: alternate.id } : {}),
      ...(termEndsOn ? { termEndsOn } : {}),
      ...(authority.trim() ? { authority: authority.trim() } : {}),
    }
    try {
      if (member) {
        await editForumMember({
          path: { forumId: forum.id, memberId: member.id },
          headers: { 'If-Match': `"${String(member.version)}"` },
          body: common,
          throwOnError: true,
        })
      } else {
        await addForumMember({
          path: { forumId: forum.id },
          headers: { 'Idempotency-Key': idempotencyKey },
          body: {
            ...(kind === 'PERSON' ? { userId: person?.id } : { positionId: position?.id }),
            role,
            startsOn,
            ...common,
          },
          throwOnError: true,
        })
      }
      await refreshForums(queryClient)
      toast.show(intl.formatMessage(fm.saved), 'success')
      onClose()
      return undefined
    } catch (problem) {
      if (isProblem(problem) && problem.status === 412) await refreshForums(queryClient)
      const fields = fieldProblems(intl, terms, problem)
      setErrors(fields)
      return Object.keys(fields).length ? undefined : refusalMessage(intl, terms, problem)
    }
  }

  return (
    <FormDialog
      side
      title={
        member
          ? intl.formatMessage(fmv.editTitle, { name: memberName(intl, member) })
          : intl.formatMessage(fmv.addTitle, { name: forum.name })
      }
      submitLabel={intl.formatMessage(member ? fm.save : fm.addMember)}
      onSubmit={submit}
      onClose={onClose}
    >
      {member ? null : (
        <>
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-sm font-medium">{intl.formatMessage(fm.who)}</legend>
            {(['PERSON', 'POSITION'] as const).map((k) => (
              <label key={k} className="flex min-h-11 items-center gap-3 text-sm">
                <input
                  type="radio"
                  name="member-kind"
                  className="size-5 accent-primary"
                  checked={kind === k}
                  onChange={() => {
                    setKind(k)
                  }}
                />
                {intl.formatMessage(k === 'PERSON' ? fm.whoPerson : fm.whoPosition)}
              </label>
            ))}
          </fieldset>
          {kind === 'PERSON' ? (
            <PickerField
              label={intl.formatMessage(fm.person)}
              kind="user"
              value={person}
              onChange={setPerson}
              error={errors.userId}
            />
          ) : (
            <PickerField
              label={intl.formatMessage(fm.position)}
              hint={intl.formatMessage(fm.positionHint)}
              kind="position"
              value={position}
              onChange={setPosition}
              error={errors.positionId}
            />
          )}
          <Field
            label={intl.formatMessage(fm.role)}
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
        </>
      )}
      <div className="flex flex-col gap-1.5">
        <label className="flex min-h-11 items-center gap-3 text-sm font-medium">
          <Checkbox
            className="size-5"
            aria-describedby="member-votes-hint"
            checked={voting}
            onChange={(e) => {
              setVoting(e.target.checked)
            }}
          />
          {intl.formatMessage(fm.votes)}
        </label>
        <p id="member-votes-hint" className="text-xs text-muted-foreground">
          {intl.formatMessage(fm.votesHint)}
        </p>
      </div>
      {typeAttributes.length ? (
        <fieldset className="flex flex-col gap-1" aria-describedby="member-attributes-hint">
          <legend className="mb-1 text-sm font-medium">{intl.formatMessage(fm.attributes)}</legend>
          {typeAttributes.map((a) => (
            <label key={a.key} className="flex min-h-11 items-center gap-3 text-sm">
              <Checkbox
                className="size-5"
                checked={attributes.includes(a.key)}
                onChange={(e) => {
                  setAttributes(e.target.checked ? [...attributes, a.key] : attributes.filter((k) => k !== a.key))
                }}
              />
              {a.label}
            </label>
          ))}
          <p id="member-attributes-hint" className="text-xs text-muted-foreground">
            {intl.formatMessage(fm.attributesHint)}
          </p>
          {errors.attributes ? <p className="text-xs font-medium text-destructive">{errors.attributes}</p> : null}
        </fieldset>
      ) : null}
      <PickerField
        label={intl.formatMessage(fm.alternate)}
        hint={intl.formatMessage(fm.alternateHint)}
        optional
        kind="user"
        value={alternate}
        onChange={setAlternate}
        error={errors.alternateUserId}
      />
      <div className="flex flex-wrap gap-4">
        {member ? null : (
          <Field label={intl.formatMessage(fm.from)} required error={errors.startsOn}>
            {(p) => (
              <Input
                {...p}
                type="date"
                className="w-48"
                value={startsOn}
                onChange={(e) => {
                  changeStart(e.target.value)
                }}
              />
            )}
          </Field>
        )}
        <Field
          label={intl.formatMessage(fm.termEnds)}
          hint={
            months && !member
              ? intl.formatMessage(fmv.termFromType, { months })
              : intl.formatMessage(fm.termEndsOpenHint)
          }
          error={errors.termEndsOn}
        >
          {(p) => (
            <Input
              {...p}
              type="date"
              className="w-48"
              value={termEndsOn}
              onChange={(e) => {
                setEndTyped(true)
                setTermEndsOn(e.target.value)
              }}
            />
          )}
        </Field>
      </div>
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
