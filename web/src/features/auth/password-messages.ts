import { defineMessages, type IntlShape } from 'react-intl'

const messages = defineMessages({
  TOO_SHORT: { id: 'password.tooShort', defaultMessage: 'Use at least 12 characters.' },
  TOO_LONG: { id: 'password.tooLong', defaultMessage: 'Use at most 128 characters.' },
  BREACHED: {
    id: 'password.breached',
    defaultMessage: 'This password appears in known data breaches. Choose another one.',
  },
  CONTAINS_IDENTITY: {
    id: 'password.containsIdentity',
    defaultMessage: 'Do not use your name or email in the password.',
  },
  INCORRECT: { id: 'password.incorrect', defaultMessage: 'The current password is not correct.' },
  other: { id: 'password.other', defaultMessage: 'Choose another password.' },
})

/** The user-facing text for a password problem code from the API (`errors[].code`). */
export function passwordProblemMessage(intl: IntlShape, code: string | undefined): string {
  const known = code && code in messages ? messages[code as keyof typeof messages] : messages.other
  return intl.formatMessage(known)
}
