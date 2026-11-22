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

describe('action items (design E07 §2.3)', () => {
  const action = { committee: 'Board Risk Committee', reference: 'BRC A14/2026', date: '2027-03-12' }

  it('name the committee, the reference and the date, never the action', () => {
    expect(notificationText(english, 'ACTION_ASSIGNED', action)).toBe(
      'Board Risk Committee has given you an action, due 2027-03-12 (BRC A14/2026)',
    )
    expect(notificationText(english, 'ACTION_SOURCE_CORRECTED', action)).toBe(
      'The decision that action BRC A14/2026 of Board Risk Committee came from was corrected',
    )
    expect(templateLabel(english, 'ACTION_ASSIGNED')).toBe('Committee action assigned')
    expect(notificationText(arabic, 'ACTION_ASSIGNED', action)).toContain('BRC A14/2026')
  })

  it('tell the holder how a follow-up memo was decided, without the memo (design E07 §1.11)', () => {
    expect(notificationText(english, 'ACTION_MEMO_DECIDED', { ...action, outcome: 'APPROVED' })).toBe(
      'The follow-up memo of action BRC A14/2026 (Board Risk Committee) was approved. You can now mark the action as done.',
    )
    expect(notificationText(english, 'ACTION_MEMO_DECIDED', { ...action, outcome: 'REJECTED' })).toBe(
      'The follow-up memo of action BRC A14/2026 (Board Risk Committee) was rejected',
    )
    expect(notificationText(arabic, 'ACTION_MEMO_DECIDED', { ...action, outcome: 'APPROVED' })).toContain(
      'BRC A14/2026',
    )
  })
})

describe('action progress, dates and endings (design E07 §2.3)', () => {
  const action = { committee: 'Board Risk Committee', reference: 'BRC A14/2026', date: '2027-01-20' }

  it('name the committee, the reference and the date, never the action', () => {
    expect(notificationText(english, 'ACTION_DUE_DECIDED', { ...action, decision: 'AGREED' })).toBe(
      'Your request to move action BRC A14/2026 of Board Risk Committee to 2027-01-20 was agreed',
    )
    expect(notificationText(english, 'ACTION_DUE_DECIDED', { ...action, decision: 'DECLINED' })).toContain(
      'was declined',
    )
    expect(notificationText(english, 'ACTION_DUE_CHANGED', action)).toBe(
      'Action BRC A14/2026 of Board Risk Committee is now due 2027-01-20',
    )
    expect(notificationText(english, 'ACTION_CANCELLED', action)).toBe(
      'Action BRC A14/2026 of Board Risk Committee was cancelled',
    )
    expect(templateLabel(english, 'ACTION_RISK_ACCEPTED')).toBe('Committee action risk accepted')
    expect(notificationText(arabic, 'ACTION_OWNER_CHANGED', action)).toContain('BRC A14/2026')
  })
})

describe('action reminders, escalation and closure (design E07 §1.8, §1.9)', () => {
  const action = { committee: 'Board Risk Committee', reference: 'BRC A9/2026', date: '2026-11-28' }

  it('name the committee, the reference and the date, never the action', () => {
    expect(notificationText(english, 'ACTION_DUE_SOON', action)).toBe(
      'Action BRC A9/2026 of Board Risk Committee is due soon, on 2026-11-28',
    )
    expect(notificationText(english, 'ACTION_OVERDUE', { ...action, stage: 'DUE' })).toBe(
      'Action BRC A9/2026 of Board Risk Committee, due 2026-11-28, is overdue',
    )
    expect(notificationText(english, 'ACTION_OVERDUE', { ...action, stage: 'ESCALATED' })).toContain(
      'is overdue and was escalated',
    )
    expect(notificationText(english, 'ACTION_OVERDUE', { ...action, stage: 'REMINDER' })).toContain('is still overdue')
    expect(notificationText(english, 'ACTION_ESCALATED', { ...action, holder: 'A. Karim (Head of Treasury)' })).toBe(
      'Action BRC A9/2026 of Board Risk Committee, held by A. Karim (Head of Treasury), is overdue since 2026-11-28',
    )
    expect(notificationText(english, 'ACTION_TO_VERIFY', action)).toBe(
      'Action BRC A9/2026 of Board Risk Committee was marked as done: please verify it',
    )
    expect(notificationText(english, 'ACTION_SENT_BACK', action)).toContain('was sent back to you')
    expect(notificationText(english, 'ACTION_CLOSED', action)).toContain('was verified and closed')
    expect(notificationText(english, 'ACTION_NOT_RUNNING', action)).toContain('has no running workflow')
    expect(templateLabel(english, 'ACTIONS_DIGEST')).toBe('Daily digest of committee actions')
    expect(templateLabel(english, 'ACTION_ESCALATED')).toBe('Committee action escalated')
    expect(notificationText(arabic, 'ACTION_OVERDUE', { ...action, stage: 'ESCALATED' })).toContain('صُعّد')
    expect(notificationText(arabic, 'ACTION_TO_VERIFY', action)).toContain('BRC A9/2026')
  })
})
