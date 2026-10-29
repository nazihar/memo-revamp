import { defineMessages, useIntl } from 'react-intl'

const messages = defineMessages({
  label: { id: 'recoveryCodes.label', defaultMessage: 'Recovery codes' },
})

/** Recovery codes in a readable grid, left-to-right in every locale (codes are not words). */
export function RecoveryCodeList({ codes }: { codes: string[] }) {
  const intl = useIntl()
  return (
    <ul
      aria-label={intl.formatMessage(messages.label)}
      dir="ltr"
      className="grid grid-cols-2 gap-2 rounded-md border bg-muted p-3 font-mono text-sm"
    >
      {codes.map((code) => (
        <li key={code} className="text-center tracking-wider">
          {code}
        </li>
      ))}
    </ul>
  )
}
