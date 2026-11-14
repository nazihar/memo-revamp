import { useQuery } from '@tanstack/react-query'
import { useIntl } from 'react-intl'
import { listClassificationLevelsOptions, listMemoTypesOptions } from '@/api/generated/@tanstack/react-query.gen'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { useMyAccess } from '@/features/access/access'
import { SUBJECT_MAX, type FormErrors, type MemoFieldValues } from './memo-fields'
import { mm } from './memo-messages'

/**
 * Subject, type and classification (design E01 §5), shared by the create page and the edit dialog. Only levels up to
 * the user's clearance are offered; the server also refuses levels below the type's default and names the field.
 */
export function MemoFields({
  values,
  onChange,
  errors,
  classificationLocked,
  showType = true,
}: {
  values: MemoFieldValues
  onChange: (values: MemoFieldValues) => void
  errors: FormErrors
  classificationLocked?: boolean
  /** False when the page chooses the type itself (the create page). */
  showType?: boolean
}) {
  const intl = useIntl()
  const types = useQuery(listMemoTypesOptions())
  const levels = useQuery(listClassificationLevelsOptions())
  const access = useMyAccess()
  const clearance = access.data?.clearance.rank ?? 0
  return (
    <div className="flex flex-col gap-4">
      <Field label={intl.formatMessage(mm.subject)} required error={errors.subject}>
        {(p) => (
          <Input
            {...p}
            name="subject"
            maxLength={SUBJECT_MAX}
            value={values.subject}
            onChange={(e) => {
              onChange({ ...values, subject: e.target.value })
            }}
          />
        )}
      </Field>
      {showType ? (
        <Field label={intl.formatMessage(mm.type)} required error={errors.typeId}>
          {(p) => (
            <Select
              {...p}
              name="typeId"
              value={values.typeId}
              onChange={(e) => {
                onChange({ ...values, typeId: e.target.value })
              }}
            >
              {(types.data ?? []).map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
      ) : null}
      <Field
        label={intl.formatMessage(mm.classification)}
        hint={intl.formatMessage(mm.classificationHint)}
        error={errors.classificationId}
      >
        {(p) => (
          <Select
            {...p}
            name="classificationId"
            disabled={classificationLocked}
            value={values.classificationId}
            onChange={(e) => {
              onChange({ ...values, classificationId: e.target.value })
            }}
          >
            <option value="">{intl.formatMessage(mm.typeDefault)}</option>
            {(levels.data ?? [])
              .filter((l) => l.rank <= clearance)
              .map((l) => (
                <option key={l.id} value={l.id}>
                  {l.name}
                </option>
              ))}
          </Select>
        )}
      </Field>
    </div>
  )
}
