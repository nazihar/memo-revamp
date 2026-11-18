import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useIntl, type MessageDescriptor } from 'react-intl'
import { listForumsOptions } from '@/api/generated/@tanstack/react-query.gen'
import {
  acceptForumSubmission,
  declineForumSubmission,
  deferForumSubmission,
  editForumSubmission,
  referForumSubmission,
  returnForumSubmission,
} from '@/api/generated/sdk.gen'
import type { SubmissionPolicy } from '@/api/generated/types.gen'
import { Field } from '@/components/ui/field'
import { Input, Select, Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { usePlaceOnAgenda } from '@/features/meetings/agenda/PlaceOnAgenda'
import { useTerms } from '@/features/terms/terms'
import type { Picked } from '@/features/org/PeoplePicker'
import { FormDialog } from './FormDialog'
import { PickerField } from './MemberSheet'
import { fm, fmv } from './messages'
import type { Submission } from './papers'
import { pm } from './papers-messages'
import { today } from './shared'
import { sm, smt, smv } from './submission-messages'
import { refreshPapers, triageFieldProblems, triageRefusal, type TriageAction } from './submissions'

type Purpose = Submission['purpose']
const PURPOSES: Purpose[] = ['DECISION', 'DISCUSSION', 'INFORMATION']

/** The paper's version as the server's ETag spells it; every triage call sends it (`If-Match`). */
function ifMatch(s: Submission) {
  return { 'If-Match': `"${String(s.version)}"` }
}

/**
 * The triage dialogs of flow M1, one per action. Each says in plain words what happens to the memo; return, decline,
 * refer and defer need a reason, checked here and by the server (`REASON_REQUIRED`). Every call sends `If-Match`, so a
 * paper someone else triaged meanwhile is refused (412, or `WRONG_STATE`) and the lists reload.
 */
export function TriageDialog({
  action,
  submission,
  policy,
  onClose,
}: {
  action: TriageAction
  submission: Submission
  /** The committee type's paper policy, for the deadline sentence under the target date. */
  policy?: SubmissionPolicy
  onClose: () => void
}) {
  const intl = useIntl()
  const terms = useTerms()
  const toast = useToast()
  const queryClient = useQueryClient()
  const forums = useQuery({ ...listForumsOptions(), enabled: action === 'refer' })
  const [reason, setReason] = useState('')
  const [targetDate, setTargetDate] = useState(
    action === 'accept' || action === 'cover' ? (submission.targetDate ?? '') : '',
  )
  const [forumId, setForumId] = useState('')
  const [purpose, setPurpose] = useState<Purpose>(submission.purpose)
  const [resolution, setResolution] = useState(submission.resolutionSought ?? '')
  const [presenter, setPresenter] = useState<Picked | undefined>(
    submission.presenter ? { id: submission.presenter.id, label: submission.presenter.name } : undefined,
  )
  const [errors, setErrors] = useState<Record<string, string>>({})
  // Flow O6 (E03 T7): Accept can also place the paper on the agenda of a coming meeting.
  const onAgenda = usePlaceOnAgenda(submission.forumId, action === 'accept')

  const needsReason = action === 'defer' || action === 'return' || action === 'decline' || action === 'refer'
  const path = { forumId: submission.forumId, id: submission.id }

  const run = async (call: () => Promise<unknown>, done: MessageDescriptor): Promise<string | undefined> => {
    const missing: Record<string, string> = {}
    if (needsReason && !reason.trim()) missing.reason = intl.formatMessage(sm.required)
    if (action === 'refer' && !forumId) missing.forumId = intl.formatMessage(fm.required)
    setErrors(missing)
    if (Object.keys(missing).length) return undefined
    try {
      await call()
      await refreshPapers(queryClient)
      toast.show(intl.formatMessage(done), 'success')
      onClose()
      return undefined
    } catch (problem) {
      const fields = triageFieldProblems(intl, terms, problem)
      setErrors(fields)
      if (Object.keys(fields).length) return undefined
      await refreshPapers(queryClient)
      return triageRefusal(intl, terms, problem)
    }
  }

  const acceptAndPlace = async (): Promise<string | undefined> => {
    const refused = await onAgenda.place(submission.id)
    await refreshPapers(queryClient)
    if (refused) return refused
    toast.show(onAgenda.done, 'success')
    onClose()
    return undefined
  }

  const text = reason.trim()
  const date = targetDate || null
  const submit = (): Promise<string | undefined> => {
    switch (action) {
      case 'accept':
        if (onAgenda.chosen) return acceptAndPlace()
        return run(
          () =>
            acceptForumSubmission({
              path,
              headers: ifMatch(submission),
              body: { targetDate: date },
              throwOnError: true,
            }),
          sm.doneAccepted,
        )
      case 'defer':
        return run(
          () =>
            deferForumSubmission({
              path,
              headers: ifMatch(submission),
              body: { reason: text, targetDate: date },
              throwOnError: true,
            }),
          sm.doneDeferred,
        )
      case 'return':
        return run(
          () =>
            returnForumSubmission({ path, headers: ifMatch(submission), body: { reason: text }, throwOnError: true }),
          sm.doneReturned,
        )
      case 'decline':
        return run(
          () =>
            declineForumSubmission({ path, headers: ifMatch(submission), body: { reason: text }, throwOnError: true }),
          sm.doneDeclined,
        )
      case 'refer':
        return run(
          () =>
            referForumSubmission({
              path,
              headers: ifMatch(submission),
              body: { forumId, reason: text },
              throwOnError: true,
            }),
          sm.doneReferred,
        )
      case 'cover':
        return run(
          () =>
            editForumSubmission({
              path,
              headers: ifMatch(submission),
              body: {
                purpose,
                resolutionSought: resolution.trim() || null,
                presenterId: presenter?.id ?? null,
                targetDate: date,
              },
              throwOnError: true,
            }),
          sm.doneSaved,
        )
    }
  }

  const infoOnly = !submission.waits && action !== 'cover' && action !== 'accept' && action !== 'defer'
  const leads: Record<TriageAction, string> = {
    accept: intl.formatMessage(smt.acceptLead, terms),
    defer: intl.formatMessage(smt.deferLead, terms),
    return: intl.formatMessage(smt.returnLead, terms),
    decline: intl.formatMessage(smt.declineLead, terms),
    refer: intl.formatMessage(smt.referLead, terms),
    cover: intl.formatMessage(sm.coverLead),
  }
  const titles: Record<TriageAction, string> = {
    accept: intl.formatMessage(sm.acceptTitle),
    defer: intl.formatMessage(sm.deferTitle),
    return: intl.formatMessage(sm.returnTitle),
    decline: intl.formatMessage(sm.declineTitle),
    refer: intl.formatMessage(smt.referTitle, terms),
    cover: intl.formatMessage(sm.coverTitle),
  }
  const submits: Record<TriageAction, string> = {
    accept: intl.formatMessage(sm.acceptSubmit),
    defer: intl.formatMessage(sm.deferSubmit),
    return: intl.formatMessage(sm.returnSubmit),
    decline: intl.formatMessage(sm.declineSubmit),
    refer: intl.formatMessage(sm.referSubmit),
    cover: intl.formatMessage(sm.save),
  }
  const memo = submission.memo
    ? `${submission.memo.reference ?? ''} ${submission.memo.subject}`.trim()
    : intl.formatMessage(pm.restricted)
  const others = (forums.data ?? []).filter((f) => f.status === 'ACTIVE' && f.id !== submission.forumId)

  return (
    <FormDialog
      title={titles[action]}
      description={
        <span className="flex flex-col gap-1">
          <span dir="auto" className="font-medium text-foreground">
            {memo}
          </span>
          <span>{infoOnly ? intl.formatMessage(sm.infoOnlyLead) : leads[action]}</span>
        </span>
      }
      submitLabel={action === 'accept' && onAgenda.chosen ? onAgenda.submitLabel : submits[action]}
      destructive={action === 'decline'}
      onSubmit={submit}
      onClose={onClose}
    >
      {action === 'cover' ? (
        <>
          <Field label={intl.formatMessage(sm.purpose)} required>
            {(p) => (
              <Select
                {...p}
                value={purpose}
                onChange={(e) => {
                  setPurpose(e.target.value as Purpose)
                }}
              >
                {PURPOSES.map((x) => (
                  <option key={x} value={x}>
                    {intl.formatMessage(pm[x])}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field
            label={intl.formatMessage(sm.resolutionSought)}
            hint={intl.formatMessage(smt.resolutionHint, terms)}
            optionalLabel={intl.formatMessage(fm.optional)}
            error={errors.resolutionSought}
          >
            {(p) => (
              <Textarea
                {...p}
                rows={3}
                maxLength={2000}
                value={resolution}
                onChange={(e) => {
                  setResolution(e.target.value)
                }}
              />
            )}
          </Field>
          <PickerField
            label={intl.formatMessage(sm.presenter)}
            optional
            kind="user"
            value={presenter}
            onChange={setPresenter}
            error={errors.presenterId}
          />
        </>
      ) : null}
      {action === 'refer' ? (
        <Field label={intl.formatMessage(smt.referTo, terms)} required error={errors.forumId}>
          {(p) => (
            <Select
              {...p}
              value={forumId}
              onChange={(e) => {
                setForumId(e.target.value)
              }}
            >
              <option value="">{intl.formatMessage(sm.choose)}</option>
              {others.map((f) => (
                <option key={f.id} value={f.id}>
                  {intl.formatMessage(fmv.nameWithCode, { name: f.name, code: f.code })}
                </option>
              ))}
            </Select>
          )}
        </Field>
      ) : null}
      {onAgenda.field}
      {(action === 'accept' && !onAgenda.chosen) || action === 'defer' || action === 'cover' ? (
        <Field
          label={intl.formatMessage(sm.targetDate)}
          optionalLabel={intl.formatMessage(fm.optional)}
          hint={
            policy && action !== 'defer'
              ? intl.formatMessage(smv.deadline, {
                  days: policy.deadlineWorkingDays ?? 5,
                  mode: policy.lateMode ?? 'CHAIR_APPROVAL',
                })
              : undefined
          }
          error={errors.targetDate}
        >
          {(p) => (
            <Input
              {...p}
              type="date"
              min={today()}
              className="w-48"
              value={targetDate}
              onChange={(e) => {
                setTargetDate(e.target.value)
              }}
            />
          )}
        </Field>
      ) : null}
      {needsReason ? (
        <Field
          label={intl.formatMessage(sm.reason)}
          hint={intl.formatMessage(action === 'return' ? sm.returnHint : sm.reasonHint)}
          required
          error={errors.reason}
        >
          {(p) => (
            <Textarea
              {...p}
              rows={3}
              maxLength={2000}
              value={reason}
              onChange={(e) => {
                setReason(e.target.value)
              }}
            />
          )}
        </Field>
      ) : null}
    </FormDialog>
  )
}
