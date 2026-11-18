import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  listClassificationLevelsOptions,
  listForumsOptions,
  listForumTypesOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import { cloneForum, createForum, dissolveForum, updateForum } from '@/api/generated/sdk.gen'
import type { ForumRequest, ForumResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select, Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { FormDialog } from './FormDialog'
import { fm, fmt, fmv } from './messages'
import { fieldProblems, refreshForums, refusalMessage, suggestKey, today } from './shared'

interface Names {
  name: string
  shortName: string
  code: string
  key: string
}

/** Name, short name, code and (for a new committee) the workflow key, suggested from the name until it is typed. */
function NameFields({
  value,
  onChange,
  errors,
  withKey,
}: {
  value: Names
  onChange: (next: Names) => void
  errors: Record<string, string>
  withKey: boolean
}) {
  const intl = useIntl()
  const terms = useTerms()
  const [keyTyped, setKeyTyped] = useState(false)
  return (
    <>
      <Field label={intl.formatMessage(fm.name)} required error={errors.name}>
        {(p) => (
          <Input
            {...p}
            maxLength={200}
            value={value.name}
            onChange={(e) => {
              const name = e.target.value
              onChange({ ...value, name, ...(withKey && !keyTyped ? { key: suggestKey(name) } : {}) })
            }}
          />
        )}
      </Field>
      <Field
        label={intl.formatMessage(fm.shortName)}
        hint={intl.formatMessage(fm.shortNameHint)}
        required
        error={errors.shortName}
      >
        {(p) => (
          <Input
            {...p}
            maxLength={40}
            value={value.shortName}
            onChange={(e) => {
              onChange({ ...value, shortName: e.target.value })
            }}
          />
        )}
      </Field>
      <Field label={intl.formatMessage(fm.code)} hint={intl.formatMessage(fm.codeHint)} required error={errors.code}>
        {(p) => (
          <Input
            {...p}
            maxLength={10}
            dir="ltr"
            className="w-40 uppercase"
            value={value.code}
            onChange={(e) => {
              onChange({ ...value, code: e.target.value.toUpperCase() })
            }}
          />
        )}
      </Field>
      {withKey ? (
        <Field
          label={intl.formatMessage(fm.key)}
          hint={intl.formatMessage(fmt.keyHint, terms)}
          required
          error={errors.key}
        >
          {(p) => (
            <Input
              {...p}
              maxLength={100}
              dir="ltr"
              value={value.key}
              onChange={(e) => {
                setKeyTyped(true)
                onChange({ ...value, key: e.target.value.toLowerCase() })
              }}
            />
          )}
        </Field>
      ) : null}
    </>
  )
}

function missing(intl: ReturnType<typeof useIntl>, names: Names, withKey: boolean): Record<string, string> {
  const required = intl.formatMessage(fm.required)
  const fields: (keyof Names)[] = withKey ? ['name', 'shortName', 'code', 'key'] : ['name', 'shortName', 'code']
  return Object.fromEntries(fields.filter((field) => !names[field].trim()).map((field) => [field, required]))
}

/** Reports to (another committee, optional) and classification (the type's default unless chosen). */
function PlacementFields({
  self,
  parentId,
  classificationId,
  onParent,
  onClassification,
  errors,
}: {
  self?: string
  parentId: string
  classificationId: string
  onParent: (id: string) => void
  onClassification: (id: string) => void
  errors: Record<string, string>
}) {
  const intl = useIntl()
  const forums = useQuery(listForumsOptions())
  const levels = useQuery(listClassificationLevelsOptions())
  return (
    <>
      <Field
        label={intl.formatMessage(fm.reportsTo)}
        optionalLabel={intl.formatMessage(fm.optional)}
        error={errors.parentId}
      >
        {(p) => (
          <Select
            {...p}
            value={parentId}
            onChange={(e) => {
              onParent(e.target.value)
            }}
          >
            <option value="">{intl.formatMessage(fm.reportsToNone)}</option>
            {(forums.data ?? [])
              .filter((f) => f.id !== self && f.status === 'ACTIVE')
              .map((f) => (
                <option key={f.id} value={f.id}>
                  {intl.formatMessage(fmv.nameWithCode, { name: f.name, code: f.code })}
                </option>
              ))}
          </Select>
        )}
      </Field>
      <Field label={intl.formatMessage(fm.classification)} error={errors.classificationId}>
        {(p) => (
          <Select
            {...p}
            value={classificationId}
            onChange={(e) => {
              onClassification(e.target.value)
            }}
          >
            <option value="">{intl.formatMessage(fm.classificationDefault)}</option>
            {(levels.data ?? []).map((l) => (
              <option key={l.id} value={l.id}>
                {l.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
    </>
  )
}

/** "New committee" (flow K1): type, name, short name, code, workflow key, reports to and classification. */
export function NewForumDialog({ onClose }: { onClose: () => void }) {
  const intl = useIntl()
  const terms = useTerms()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const types = useQuery(listForumTypesOptions())
  const [typeId, setTypeId] = useState('')
  const [names, setNames] = useState<Names>({ name: '', shortName: '', code: '', key: '' })
  const [parentId, setParentId] = useState('')
  const [classificationId, setClassificationId] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [idempotencyKey] = useState(newIdempotencyKey)
  const usable = (types.data ?? []).filter((t) => t.active && t.publishedVersion != null)

  const submit = async () => {
    const found = missing(intl, names, true)
    if (!typeId) found.typeId = intl.formatMessage(fm.required)
    setErrors(found)
    if (Object.keys(found).length) return undefined
    const body: ForumRequest = {
      typeId,
      name: names.name.trim(),
      shortName: names.shortName.trim(),
      code: names.code.trim(),
      key: names.key.trim(),
      ...(parentId ? { parentId } : {}),
      ...(classificationId ? { classificationId } : {}),
    }
    try {
      const created = await createForum({ headers: { 'Idempotency-Key': idempotencyKey }, body, throwOnError: true })
      await refreshForums(queryClient)
      await navigate({ to: '/committees/$forumId', params: { forumId: created.data.id } })
      return undefined
    } catch (problem) {
      const fields = fieldProblems(intl, terms, problem)
      setErrors(fields)
      return Object.keys(fields).length ? undefined : refusalMessage(intl, terms, problem)
    }
  }

  return (
    <FormDialog
      title={intl.formatMessage(fmt.add, terms)}
      submitLabel={intl.formatMessage(fm.create)}
      onSubmit={submit}
      onClose={onClose}
    >
      <Field label={intl.formatMessage(fm.type)} required error={errors.typeId}>
        {(p) => (
          <Select
            {...p}
            value={typeId}
            onChange={(e) => {
              setTypeId(e.target.value)
            }}
          >
            <option value="">{intl.formatMessage(fm.chooseType)}</option>
            {usable.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <NameFields value={names} onChange={setNames} errors={errors} withKey />
      <PlacementFields
        parentId={parentId}
        classificationId={classificationId}
        onParent={setParentId}
        onClassification={setClassificationId}
        errors={errors}
      />
    </FormDialog>
  )
}

/** "Edit" (K2 menu): names, code, reports to and classification; the key and type never change. */
export function EditForumDialog({ forum, onClose }: { forum: ForumResponse; onClose: () => void }) {
  const intl = useIntl()
  const terms = useTerms()
  const toast = useToast()
  const queryClient = useQueryClient()
  const [names, setNames] = useState<Names>({
    name: forum.name,
    shortName: forum.shortName,
    code: forum.code,
    key: forum.key,
  })
  const [parentId, setParentId] = useState(forum.parentId ?? '')
  const [classificationId, setClassificationId] = useState(forum.classificationId ?? '')
  const [errors, setErrors] = useState<Record<string, string>>({})

  const submit = async () => {
    const found = missing(intl, names, false)
    setErrors(found)
    if (Object.keys(found).length) return undefined
    try {
      await updateForum({
        path: { forumId: forum.id },
        headers: { 'If-Match': `"${String(forum.version)}"` },
        body: {
          key: forum.key,
          typeId: forum.typeId,
          name: names.name.trim(),
          shortName: names.shortName.trim(),
          code: names.code.trim(),
          ...(parentId ? { parentId } : {}),
          ...(classificationId ? { classificationId } : {}),
        },
        throwOnError: true,
      })
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
      title={intl.formatMessage(fmv.editForumTitle, { name: forum.name })}
      submitLabel={intl.formatMessage(fm.save)}
      onSubmit={submit}
      onClose={onClose}
    >
      <NameFields value={names} onChange={setNames} errors={errors} withKey={false} />
      <PlacementFields
        self={forum.id}
        parentId={parentId}
        classificationId={classificationId}
        onParent={setParentId}
        onClassification={setClassificationId}
        errors={errors}
      />
      <p className="text-sm text-muted-foreground" dir="auto">
        {intl.formatMessage(fmv.fixedValues, { key: forum.key, type: forum.typeName })}
      </p>
    </FormDialog>
  )
}

/** "Clone" (flow K5): a new name, short name, code and key; optionally the current members from a date. */
export function CloneForumDialog({ forum, onClose }: { forum: ForumResponse; onClose: () => void }) {
  const intl = useIntl()
  const terms = useTerms()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [names, setNames] = useState<Names>({ name: '', shortName: '', code: '', key: '' })
  const [withMembers, setWithMembers] = useState(true)
  const [startsOn, setStartsOn] = useState(today())
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [idempotencyKey] = useState(newIdempotencyKey)

  const submit = async () => {
    const found = missing(intl, names, true)
    setErrors(found)
    if (Object.keys(found).length) return undefined
    try {
      const created = await cloneForum({
        path: { forumId: forum.id },
        headers: { 'Idempotency-Key': idempotencyKey },
        body: {
          name: names.name.trim(),
          shortName: names.shortName.trim(),
          code: names.code.trim(),
          key: names.key.trim(),
          withMembers,
          ...(withMembers && startsOn ? { startsOn } : {}),
        },
        throwOnError: true,
      })
      await refreshForums(queryClient)
      onClose()
      await navigate({ to: '/committees/$forumId', params: { forumId: created.data.id } })
      return undefined
    } catch (problem) {
      const fields = fieldProblems(intl, terms, problem)
      setErrors(fields)
      return Object.keys(fields).length ? undefined : refusalMessage(intl, terms, problem)
    }
  }

  return (
    <FormDialog
      title={intl.formatMessage(fmv.cloneTitle, { name: forum.name })}
      description={intl.formatMessage(fmt.cloneLead, terms)}
      submitLabel={intl.formatMessage(fm.cloneSubmit)}
      onSubmit={submit}
      onClose={onClose}
    >
      <NameFields value={names} onChange={setNames} errors={errors} withKey />
      <label className="flex min-h-11 items-center gap-3 text-sm">
        <Checkbox
          className="size-5"
          checked={withMembers}
          onChange={(e) => {
            setWithMembers(e.target.checked)
          }}
        />
        {intl.formatMessage(fm.copyMembers)}
      </label>
      {withMembers ? (
        <Field label={intl.formatMessage(fm.copyFrom)} hint={intl.formatMessage(fm.copyHint)}>
          {(p) => (
            <Input
              {...p}
              type="date"
              className="w-48"
              value={startsOn}
              onChange={(e) => {
                setStartsOn(e.target.value)
              }}
            />
          )}
        </Field>
      ) : null}
    </FormDialog>
  )
}

/** "Dissolve" (K2 menu): the date and a reason; the history stays and there is no way back. */
export function DissolveForumDialog({ forum, onClose }: { forum: ForumResponse; onClose: () => void }) {
  const intl = useIntl()
  const terms = useTerms()
  const toast = useToast()
  const queryClient = useQueryClient()
  const [on, setOn] = useState(today())
  const [reason, setReason] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})

  const submit = async () => {
    if (!reason.trim()) {
      setErrors({ reason: intl.formatMessage(fm.required) })
      return undefined
    }
    setErrors({})
    try {
      await dissolveForum({
        path: { forumId: forum.id },
        headers: { 'If-Match': `"${String(forum.version)}"` },
        body: { reason: reason.trim(), ...(on ? { dissolvedOn: on } : {}) },
        throwOnError: true,
      })
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
      title={intl.formatMessage(fmv.dissolveTitle, { name: forum.name })}
      description={intl.formatMessage(fmt.dissolveLead, terms)}
      submitLabel={intl.formatMessage(fm.dissolveSubmit)}
      destructive
      onSubmit={submit}
      onClose={onClose}
    >
      <Field label={intl.formatMessage(fm.dissolvedOn)}>
        {(p) => (
          <Input
            {...p}
            type="date"
            className="w-48"
            value={on}
            onChange={(e) => {
              setOn(e.target.value)
            }}
          />
        )}
      </Field>
      <Field label={intl.formatMessage(fm.dissolveReason)} required error={errors.reason}>
        {(p) => (
          <Textarea
            {...p}
            rows={3}
            maxLength={500}
            value={reason}
            onChange={(e) => {
              setReason(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}
