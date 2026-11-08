import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getTenantPreferencesOptions,
  getTenantPreferencesQueryKey,
  getTermsOptions,
  getTermsQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { updateTenantPreferences, updateTerms } from '@/api/generated/sdk.gen'
import type { Terms, TenantPreferencesView } from '@/api/generated/types.gen'
import { problemType, PROBLEM, type Problem } from '@/api/http'
import { FormActions, FormGrid } from '@/components/page/FormPage'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { TERMS, type TermKey } from '@/features/terms/terms'
import { am, amv } from './admin-messages'

const LANGUAGES = ['en', 'ar'] as const
type Language = (typeof LANGUAGES)[number]

/** Languages whose messages agree with a term's gender (ADR-0032). */
const GENDERED: readonly Language[] = ['ar']

const termLabels = { unit: am.termUnit, position: am.termPosition, memo: am.termMemo } satisfies Record<
  TermKey,
  unknown
>

function nativeName(code: string): string {
  return new Intl.DisplayNames([code], { type: 'language' }).of(code) ?? code
}

/** `/admin/language` (design E07 §7): default language and time zone, and the organisation's own terms per language. */
export function LanguagePage() {
  const intl = useIntl()
  const preferences = useQuery(getTenantPreferencesOptions())
  const [language, setLanguage] = useState<Language>('en')
  const terms = useQuery(getTermsOptions({ query: { locale: language } }))
  return (
    <section aria-labelledby="language-title" className="flex flex-col gap-8">
      <PageHeader
        title={<span id="language-title">{intl.formatMessage(am.languageTitle)}</span>}
        description={intl.formatMessage(am.languageLead)}
      />
      {preferences.isPending ? (
        <LoadingState rows={2} />
      ) : preferences.isError ? (
        <ErrorState onRetry={() => void preferences.refetch()} />
      ) : (
        <PreferencesForm key={preferences.data.version} view={preferences.data} />
      )}
      <section aria-labelledby="terms-title" className="flex max-w-3xl flex-col gap-3">
        <h2 id="terms-title" className="text-lg font-semibold">
          {intl.formatMessage(am.termsTitle)}
        </h2>
        <p className="text-sm text-muted-foreground">{intl.formatMessage(am.termsLead)}</p>
        <div className="max-w-xs">
          <Field label={intl.formatMessage(am.termsLanguage)}>
            {(p) => (
              <Select
                {...p}
                value={language}
                onChange={(e) => {
                  setLanguage(e.target.value as Language)
                }}
              >
                {LANGUAGES.map((code) => (
                  <option key={code} value={code}>
                    {nativeName(code)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </div>
        {terms.isPending ? (
          <LoadingState rows={3} />
        ) : terms.isError ? (
          <ErrorState onRetry={() => void terms.refetch()} />
        ) : (
          <TermsForm key={`${language}-${String(terms.data.version)}`} language={language} view={terms.data} />
        )}
      </section>
    </section>
  )
}

function PreferencesForm({ view }: { view: TenantPreferencesView }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const [locale, setLocale] = useState(view.preferences.locale)
  const [timeZone, setTimeZone] = useState(view.preferences.timeZone)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const save = async () => {
    setErrors({})
    try {
      await updateTenantPreferences({
        headers: { 'If-Match': `"${String(view.version)}"` },
        body: { locale, timeZone: timeZone.trim() },
        throwOnError: true,
      })
      toast.show(intl.formatMessage(am.saved))
    } catch (err) {
      setErrors(Object.fromEntries(((err as Problem).errors ?? []).map((p) => [p.field, p.message])))
      toast.show(intl.formatMessage(problemType(err) === PROBLEM.conflict ? am.stale : am.invalid), 'error')
    } finally {
      await queryClient.invalidateQueries({ queryKey: getTenantPreferencesQueryKey() })
    }
  }
  return (
    <form
      noValidate
      className="flex max-w-2xl flex-col gap-4"
      onSubmit={(e) => {
        e.preventDefault()
        void save()
      }}
    >
      <FormGrid>
        <Field label={intl.formatMessage(am.defaultLanguage)} required error={errors.locale}>
          {(p) => (
            <Select
              {...p}
              value={locale}
              onChange={(e) => {
                setLocale(e.target.value)
              }}
            >
              {LANGUAGES.map((code) => (
                <option key={code} value={code}>
                  {nativeName(code)}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label={intl.formatMessage(am.timeZone)} required error={errors.timeZone}>
          {(p) => (
            <Input
              {...p}
              value={timeZone}
              onChange={(e) => {
                setTimeZone(e.target.value)
              }}
            />
          )}
        </Field>
      </FormGrid>
      <FormActions>
        <Button type="submit">{intl.formatMessage(am.save)}</Button>
      </FormActions>
    </form>
  )
}

interface Row {
  one: string
  other: string
  gender: string
}

function TermsForm({ language, view }: { language: Language; view: Terms }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const gendered = GENDERED.includes(language)
  const [rows, setRows] = useState<Record<TermKey, Row>>(
    () =>
      Object.fromEntries(
        TERMS.map((term) => {
          const saved = view.terms.find((t) => t.term === term)
          return [term, { one: saved?.one ?? '', other: saved?.other ?? '', gender: saved?.gender ?? 'masculine' }]
        }),
      ) as Record<TermKey, Row>,
  )
  const [errors, setErrors] = useState<Record<string, string>>({})
  const filled = TERMS.filter((t) => rows[t].one.trim() !== '' || rows[t].other.trim() !== '')
  const set = (term: TermKey, key: keyof Row) => (e: { target: { value: string } }) => {
    setRows((r) => ({ ...r, [term]: { ...r[term], [key]: e.target.value } }))
  }
  const save = async () => {
    setErrors({})
    try {
      await updateTerms({
        headers: { 'If-Match': `"${String(view.version)}"` },
        body: {
          locale: language,
          terms: filled.map((term) => ({
            term,
            one: rows[term].one.trim(),
            other: rows[term].other.trim(),
            gender: gendered ? rows[term].gender : undefined,
          })),
        },
        throwOnError: true,
      })
      toast.show(intl.formatMessage(am.saved))
    } catch (err) {
      // Field names are terms[i].one for the i-th filled row: map them back to the term.
      const problems = (err as Problem).errors ?? []
      setErrors(
        Object.fromEntries(
          problems.map((p) => {
            const index = /terms\[(\d+)]/.exec(p.field)?.[1]
            const term = index === undefined ? undefined : filled[Number(index)]
            return [term ?? p.field, p.message]
          }),
        ),
      )
      toast.show(intl.formatMessage(problemType(err) === PROBLEM.conflict ? am.stale : am.invalid), 'error')
    } finally {
      await queryClient.invalidateQueries({ queryKey: getTermsQueryKey({ query: { locale: language } }) })
    }
  }
  return (
    <form
      noValidate
      className="flex flex-col gap-4"
      onSubmit={(e) => {
        e.preventDefault()
        void save()
      }}
    >
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <caption className="sr-only">{intl.formatMessage(am.termsCaption)}</caption>
          <thead>
            <tr className="text-start text-muted-foreground">
              <th scope="col" className="py-2 pe-3 text-start font-medium">
                {intl.formatMessage(am.colTerm)}
              </th>
              <th scope="col" className="py-2 pe-3 text-start font-medium">
                {intl.formatMessage(am.colOne)}
              </th>
              <th scope="col" className="py-2 pe-3 text-start font-medium">
                {intl.formatMessage(am.colOther)}
              </th>
              {gendered ? (
                <th scope="col" className="py-2 text-start font-medium">
                  {intl.formatMessage(am.colGender)}
                </th>
              ) : null}
            </tr>
          </thead>
          <tbody>
            {TERMS.map((term) => {
              const label = intl.formatMessage(termLabels[term])
              return (
                <tr key={term} className="border-t align-top">
                  <th scope="row" className="py-2 pe-3 text-start font-medium">
                    {label}
                    {errors[term] ? (
                      <p role="alert" className="mt-1 text-xs font-normal text-destructive">
                        {errors[term]}
                      </p>
                    ) : null}
                  </th>
                  <td className="py-2 pe-3">
                    <Input
                      aria-label={intl.formatMessage(amv.termCell, {
                        term: label,
                        column: intl.formatMessage(am.colOne),
                      })}
                      value={rows[term].one}
                      maxLength={40}
                      onChange={set(term, 'one')}
                    />
                  </td>
                  <td className="py-2 pe-3">
                    <Input
                      aria-label={intl.formatMessage(amv.termCell, {
                        term: label,
                        column: intl.formatMessage(am.colOther),
                      })}
                      value={rows[term].other}
                      maxLength={40}
                      onChange={set(term, 'other')}
                    />
                  </td>
                  {gendered ? (
                    <td className="py-2">
                      <Select
                        aria-label={intl.formatMessage(amv.termCell, {
                          term: label,
                          column: intl.formatMessage(am.colGender),
                        })}
                        value={rows[term].gender}
                        onChange={set(term, 'gender')}
                      >
                        <option value="masculine">{intl.formatMessage(am.masculine)}</option>
                        <option value="feminine">{intl.formatMessage(am.feminine)}</option>
                      </Select>
                    </td>
                  ) : null}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <FormActions>
        <Button type="submit">{intl.formatMessage(am.saveTerms)}</Button>
      </FormActions>
    </form>
  )
}
