import { defineMessages, type NoMessageValues } from 'react-intl'
import type { TermValues } from '@/features/terms/terms'

export const accountMessages = defineMessages<{
  trigger: { name: string }
  security: NoMessageValues
  notifications: NoMessageValues
  forums: TermValues
  meetings: NoMessageValues
  signOut: NoMessageValues
  signIn: NoMessageValues
}>({
  trigger: { id: 'account.trigger', defaultMessage: 'Account: {name}' },
  security: { id: 'account.security', defaultMessage: 'My security' },
  notifications: { id: 'account.notifications', defaultMessage: 'Notification settings' },
  forums: { id: 'nav.myForums', defaultMessage: 'My {forums}' },
  meetings: { id: 'nav.myMeetings', defaultMessage: 'My meetings' },
  signOut: { id: 'account.signOut', defaultMessage: 'Sign out' },
  signIn: { id: 'account.signIn', defaultMessage: 'Sign in' },
})
