import { createIntl } from 'react-intl'
import { describe, expect, it } from 'vitest'
import { describeAssignee, describeDuration, describeSla, describeWho, targetName } from './describe'

const intl = createIntl({ locale: 'en', messages: {}, onError: () => undefined })
const labels = { p1: 'Head of Credit', u1: 'Retail Banking', r1: 'Credit officer', a1: 'Amina Rahman' }

describe('describing a definition in words', () => {
  it('puts durations into words, in working time or calendar time', () => {
    expect(describeDuration(intl, 'P2D', true)).toBe('2 working days')
    expect(describeDuration(intl, 'PT4H', true)).toBe('4 working hours')
    expect(describeDuration(intl, 'P1DT2H30M', false)).toBe('1 day, 2 hours, 30 minutes')
    expect(describeDuration(intl, 'PT1M', false)).toBe('1 minute')
    for (const odd of ['P', 'PT', 'P2W', '2D', 'PT4H2D']) expect(describeDuration(intl, odd, true)).toBe(odd)
  })

  it('names who a step goes to, with ids replaced by their names', () => {
    expect(describeAssignee(intl, labels, { kind: 'person', userId: 'a1' })).toBe('Amina Rahman')
    expect(describeAssignee(intl, labels, { kind: 'position', positionId: 'p1' })).toBe('The holder of Head of Credit')
    expect(describeAssignee(intl, labels, { kind: 'supervisor', levels: 2 })).toBe(
      'The initiator’s supervisor, 2 levels up',
    )
    expect(describeAssignee(intl, labels, { kind: 'unitHead', unit: 'u1', up: 0 })).toBe('The head of Retail Banking')
    expect(describeAssignee(intl, labels, { kind: 'role', roleId: 'r1' })).toBe(
      'Credit officer in the initiator’s unit or above',
    )
    expect(describeAssignee(intl, labels, { kind: 'queue', queue: 'credit-desk' })).toBe('The queue credit-desk')
    expect(describeAssignee(intl, labels, { kind: 'person', userId: 'gone' })).toBe('Unknown (gone)')
    expect(
      describeWho(intl, labels, {
        id: 'c',
        type: 'parallel',
        mode: 'n-of-m',
        n: 2,
        assignees: [{ kind: 'person', userId: 'a1' }, { kind: 'position', positionId: 'p1' }, { kind: 'initiator' }],
      }),
    ).toBe('2 of Amina Rahman, The holder of Head of Credit, and The initiator')
  })

  it('puts a deadline into words: due, reminders and escalation', () => {
    expect(
      describeSla(intl, labels, {
        due: 'P2D',
        remind: { before: 'PT4H', after: ['P1D'] },
        escalate: { after: 'P1D', action: 'nextLevel' },
      }),
    ).toBe(
      'Due in 2 working days; reminder 4 working hours before; reminders 1 working day after; moves to the next level 1 working day late',
    )
    expect(describeSla(intl, labels, { due: 'PT8H', workingTime: false })).toBe('Due in 8 hours')
  })

  it('names where a connection leads', () => {
    const steps = [{ id: 'a', type: 'approve' as const, name: 'Director' }]
    expect(targetName(intl, steps, 'a')).toBe('Director')
    expect(targetName(intl, steps, 'end')).toBe('End')
    expect(targetName(intl, steps, 'b')).toBe('Unknown (b)')
    expect(targetName(intl, steps, undefined)).toBe('Nowhere yet')
  })
})
