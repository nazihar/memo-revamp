import { defineMessages } from 'react-intl'

/** The Approvals dashboard (flow K); it loads with `/dashboards` (the `dashboard` catalogue part). */
export const dm = defineMessages({
  title: { id: 'dashboard.title', defaultMessage: 'Approvals dashboard' },
  lead: {
    id: 'dashboard.lead',
    defaultMessage:
      'How approvals are moving: what is waiting and for how long, how long decisions take, and the flow per week.',
  },
  scope: { id: 'dashboard.scope', defaultMessage: 'Scope' },
  scopeMe: { id: 'dashboard.scope.me', defaultMessage: 'Me' },
  period: { id: 'dashboard.period', defaultMessage: 'Period' },
  forbidden: {
    id: 'dashboard.forbidden',
    defaultMessage: 'You may not see the figures of this unit. Choose Me or a unit you oversee.',
  },
  showMine: { id: 'dashboard.showMine', defaultMessage: 'Show my figures' },
  showTable: { id: 'dashboard.showTable', defaultMessage: 'Show as table' },

  ageTitle: { id: 'dashboard.age.title', defaultMessage: 'Pending by age' },
  ageNone: { id: 'dashboard.age.none', defaultMessage: 'Nothing is pending.' },
  colAge: { id: 'dashboard.age.colAge', defaultMessage: 'Waiting (working days)' },
  colTasks: { id: 'dashboard.age.colTasks', defaultMessage: 'Tasks' },
  bucket0: { id: 'dashboard.age.bucket0', defaultMessage: '0–2 days' },
  bucket3: { id: 'dashboard.age.bucket3', defaultMessage: '3–5 days' },
  bucket6: { id: 'dashboard.age.bucket6', defaultMessage: '6–10 days' },
  bucket10: { id: 'dashboard.age.bucket10', defaultMessage: 'Over 10 days' },

  overdueTitle: { id: 'dashboard.overdue.title', defaultMessage: 'Overdue' },
  viewList: { id: 'dashboard.overdue.viewList', defaultMessage: 'View list' },

  personTitle: { id: 'dashboard.person.title', defaultMessage: 'Pending by person' },
  personNone: { id: 'dashboard.person.none', defaultMessage: 'Nobody in this scope has pending tasks.' },
  colPerson: { id: 'dashboard.person.colPerson', defaultMessage: 'Person' },
  colPending: { id: 'dashboard.person.colPending', defaultMessage: 'Pending' },
  colOverdue: { id: 'dashboard.person.colOverdue', defaultMessage: 'Overdue' },

  cycleTitle: { id: 'dashboard.cycle.title', defaultMessage: 'Cycle time by memo type' },
  cycleNone: { id: 'dashboard.cycle.none', defaultMessage: 'No memo was decided in this period.' },
  colType: { id: 'dashboard.cycle.colType', defaultMessage: 'Memo type' },
  colDecided: { id: 'dashboard.cycle.colDecided', defaultMessage: 'Decided' },
  colMedian: { id: 'dashboard.cycle.colMedian', defaultMessage: 'Median (days)' },
  colP90: { id: 'dashboard.cycle.colP90', defaultMessage: 'P90 (days)' },
  seriesMedian: { id: 'dashboard.cycle.median', defaultMessage: 'Median' },
  seriesP90: { id: 'dashboard.cycle.p90', defaultMessage: 'P90' },

  throughputTitle: { id: 'dashboard.throughput.title', defaultMessage: 'Throughput per week' },
  colWeek: { id: 'dashboard.throughput.colWeek', defaultMessage: 'Week of' },
  seriesCreated: { id: 'dashboard.throughput.created', defaultMessage: 'Created' },
  seriesCompleted: { id: 'dashboard.throughput.completed', defaultMessage: 'Completed' },
})

/** Messages with values. */
export const dmv = defineMessages<{
  weeks: { count: number }
  showing: { scope: string; from: string; to: string }
  unit: { number: number }
  bucket: { bucket: string }
  ageSummary: { total: number; older: number }
  overdueCount: { count: number }
  overdueSummary: { count: number }
  personSummary: { people: number; top: string; count: number }
  personLabel: { pending: number; overdue: number }
  cycleSummary: { types: number; type: string; days: string }
  seriesValue: { series: string; value: string }
  throughputSummary: { created: number; completed: number; weeks: number }
}>({
  weeks: { id: 'dashboard.period.weeks', defaultMessage: '{count, plural, one {Last week} other {Last # weeks}}' },
  showing: { id: 'dashboard.showing', defaultMessage: 'Showing: {scope}, {from} – {to}' },
  unit: { id: 'dashboard.scope.unit', defaultMessage: 'Unit {number}' },
  bucket: { id: 'dashboard.age.bucket', defaultMessage: '{bucket} days' },
  ageSummary: {
    id: 'dashboard.age.summary',
    defaultMessage:
      '{total, plural, one {# task is pending} other {# tasks are pending}}; {older, plural, =0 {none has} one {# has} other {# have}} waited more than 5 working days.',
  },
  overdueCount: { id: 'dashboard.overdue.count', defaultMessage: '{count, plural, one {# task} other {# tasks}}' },
  overdueSummary: {
    id: 'dashboard.overdue.summary',
    defaultMessage:
      '{count, plural, =0 {Nothing is past its due time.} one {# task is past its due time.} other {# tasks are past their due time.}}',
  },
  personSummary: {
    id: 'dashboard.person.summary',
    defaultMessage:
      '{people, plural, one {# person has pending tasks} other {# people have pending tasks}}; the most has {top}, with {count}.',
  },
  personLabel: {
    id: 'dashboard.person.label',
    defaultMessage: '{pending} ({overdue, plural, =0 {none overdue} other {# overdue}})',
  },
  cycleSummary: {
    id: 'dashboard.cycle.summary',
    defaultMessage:
      '{types, plural, one {Memos of # type were decided} other {Memos of # types were decided}}; the longest median is {type}, at {days} days.',
  },
  seriesValue: { id: 'dashboard.cycle.value', defaultMessage: '{series} {value}' },
  throughputSummary: {
    id: 'dashboard.throughput.summary',
    defaultMessage:
      '{created, plural, one {# memo created} other {# memos created}} and {completed, plural, one {# completed} other {# completed}} in {weeks, plural, one {# week} other {# weeks}}.',
  },
})
