import type { IntlShape } from 'react-intl'
import { mmv } from './memo-messages'

/** Whether [dueAt] has passed (design E05 §4.4); the server keeps the due time, the browser's clock decides "now". */
export function isOverdue(dueAt: string | null | undefined, now: Date = new Date()): boolean {
  return dueAt != null && new Date(dueAt).getTime() < now.getTime()
}

/** "Due 12 Nov, 15:00" or "Overdue since 12 Nov, 15:00". */
export function dueLabel(intl: IntlShape, dueAt: string, now: Date = new Date()): string {
  const time = intl.formatDate(dueAt, { dateStyle: 'medium', timeStyle: 'short' })
  return intl.formatMessage(isOverdue(dueAt, now) ? mmv.overdueSince : mmv.dueAt, { time })
}
