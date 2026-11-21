import { createIntl } from 'react-intl'
import { describe, expect, it } from 'vitest'

import ar from '../../i18n/messages/ar.json'
import { notificationText, templateLabel } from './notification-messages'

const english = createIntl({ locale: 'en', messages: {}, onError: () => undefined })
const arabic = createIntl({ locale: 'ar', messages: ar })

const meeting = { committee: 'Board Risk Committee', date: '2026-12-28', item: '5' }

describe('declaration notifications (ADR-0062)', () => {
  it('name the meeting and the item number, and say whether it was made or withdrawn', () => {
    expect(notificationText(english, 'MEETING_DECLARATION_MADE', { ...meeting, change: 'DECLARED' })).toBe(
      'An interest was declared in item 5 of Board Risk Committee on 2026-12-28',
    )
    expect(notificationText(english, 'MEETING_DECLARATION_MADE', { ...meeting, change: 'WITHDRAWN' })).toBe(
      'A declaration of interest in item 5 of Board Risk Committee on 2026-12-28 was withdrawn',
    )
    expect(notificationText(english, 'MEETING_DECLARATION_RECORDED', { ...meeting, change: 'RECORDED' })).toBe(
      'A declaration of interest in item 5 of Board Risk Committee on 2026-12-28 was recorded for you',
    )
    expect(notificationText(english, 'MEETING_RULING_NEEDED', meeting)).toBe(
      'A declaration of interest in item 5 of Board Risk Committee on 2026-12-28 needs your ruling',
    )
    expect(notificationText(english, 'MEETING_DECLARATION_RULED', { ...meeting, outcome: 'TAKE_PART' })).toBe(
      'The chair ruled that you may take part in item 5 of Board Risk Committee on 2026-12-28',
    )
    expect(notificationText(english, 'MEETING_DECLARATIONS_DUE', meeting)).toBe(
      'Please declare any interests in the agenda for Board Risk Committee on 2026-12-28',
    )
    expect(templateLabel(english, 'MEETING_RULING_NEEDED')).toBe('Ruling on a declaration needed')
  })

  it('read in Arabic', () => {
    expect(notificationText(arabic, 'MEETING_DECLARATION_RULED', { ...meeting, outcome: 'LEAVE' })).toContain(
      'تغادر القاعة',
    )
    expect(notificationText(arabic, 'MEETING_RULING_NEEDED', meeting)).toContain('ينتظر قرارك')
  })
})

describe('meeting reminders (design E05 §9)', () => {
  it('ask for the answer and name the items to review only when they apply', () => {
    expect(notificationText(english, 'MEETING_REMINDER', { ...meeting, unanswered: 'false', toReview: '0' })).toBe(
      'Board Risk Committee meets on 2026-12-28',
    )
    expect(notificationText(english, 'MEETING_REMINDER', { ...meeting, unanswered: 'true', toReview: '2' })).toBe(
      'Board Risk Committee meets on 2026-12-28. Please tell us whether you will attend. You still have 2 items to review for declarations',
    )
    expect(notificationText(english, 'MEETING_REMINDER', { ...meeting, toReview: '1' })).toBe(
      'Board Risk Committee meets on 2026-12-28. You still have 1 item to review for declarations',
    )
    expect(notificationText(english, 'MEETING_REPLY_REMINDER', meeting)).toBe(
      'Replies for Board Risk Committee on 2026-12-28 are due today: please tell us whether you will attend',
    )
    expect(notificationText(english, 'MEETING_QUORUM_AT_RISK', meeting)).toBe(
      'From the replies so far, the meeting of Board Risk Committee on 2026-12-28 or some of its items may not be quorate',
    )
    expect(templateLabel(english, 'MEETING_REMINDER')).toBe('Meeting reminder')
  })

  it('read in Arabic', () => {
    expect(notificationText(arabic, 'MEETING_REMINDER', { ...meeting, unanswered: 'true', toReview: '2' })).toContain(
      'بندان',
    )
    expect(notificationText(arabic, 'MEETING_QUORUM_AT_RISK', meeting)).toContain('قد لا يكتمل نصاب')
  })
})
