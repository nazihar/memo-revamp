import { defineMessages } from 'react-intl'

/**
 * The inbox's Watching and Team tabs, the bulk bar and its results (Phase 3 E05 flows I and J). They load with `/inbox`
 * (the `inbox` catalogue part).
 */
export const im = defineMessages({
  tabWatching: { id: 'inbox.tab.watching', defaultMessage: 'Watching' },
  tabTeam: { id: 'inbox.tab.team', defaultMessage: 'Team' },
  emptyWatching: {
    id: 'inbox.watching.empty',
    defaultMessage: 'You follow no memos. Open a memo and choose Follow to get its news here.',
  },
  emptyTeam: { id: 'inbox.team.empty', defaultMessage: 'No task matches.' },
  noTeam: {
    id: 'inbox.team.none',
    defaultMessage: 'The team view is for unit heads and people given it. You do not oversee a unit.',
  },
  sort: { id: 'inbox.sort', defaultMessage: 'Sort' },
  sortAssigned: { id: 'inbox.sort.assigned', defaultMessage: 'Assigned' },
  sortDue: { id: 'inbox.sort.due', defaultMessage: 'Due' },
  selectAll: { id: 'inbox.selectAll', defaultMessage: 'Select all shown' },
  bulkActions: { id: 'inbox.bulk.label', defaultMessage: 'Actions on the selected items' },
  clearSelection: { id: 'inbox.bulk.clear', defaultMessage: 'Clear selection' },
  claim: { id: 'inbox.bulk.claim', defaultMessage: 'Claim' },
  release: { id: 'inbox.bulk.release', defaultMessage: 'Release' },
  watch: { id: 'inbox.bulk.watch', defaultMessage: 'Watch' },
  unwatch: { id: 'inbox.bulk.unwatch', defaultMessage: 'Unfollow' },
  reassign: { id: 'inbox.bulk.reassign', defaultMessage: 'Reassign…' },
  failed: { id: 'inbox.bulk.failed', defaultMessage: 'That did not work. Try again.' },
  forbiddenAll: { id: 'inbox.bulk.forbidden', defaultMessage: 'You may not do this.' },
  itemCHANGED: { id: 'inbox.bulk.item.changed', defaultMessage: 'Not changed: it was decided or moved meanwhile' },
  itemFORBIDDEN: { id: 'inbox.bulk.item.forbidden', defaultMessage: 'Not allowed' },
  itemNOT_FOUND: { id: 'inbox.bulk.item.notFound', defaultMessage: 'No longer found' },
  notDone: { id: 'inbox.bulk.notDone', defaultMessage: 'Not done' },
  dismiss: { id: 'inbox.bulk.dismiss', defaultMessage: 'Dismiss' },

  reasonMANUAL: { id: 'inbox.watching.reason.manual', defaultMessage: 'You follow this' },
  reasonINITIATED: { id: 'inbox.watching.reason.initiated', defaultMessage: 'You started it' },
  reasonACTED: { id: 'inbox.watching.reason.acted', defaultMessage: 'You acted on it' },
  reasonMENTIONED: { id: 'inbox.watching.reason.mentioned', defaultMessage: 'You were mentioned' },

  filterTeam: { id: 'inbox.team.filter.team', defaultMessage: 'Team' },
  filterAllTeams: { id: 'inbox.team.filter.allTeams', defaultMessage: 'All the units I oversee' },
  filterStatus: { id: 'inbox.team.filter.status', defaultMessage: 'Status' },
  statusPending: { id: 'inbox.team.status.pending', defaultMessage: 'Pending' },
  statusOverdue: { id: 'inbox.team.status.overdue', defaultMessage: 'Overdue' },
  filterPerson: { id: 'inbox.team.filter.person', defaultMessage: 'Person' },
  noDue: { id: 'inbox.team.noDue', defaultMessage: 'No due time' },

  reassignTitle: { id: 'inbox.reassign.title', defaultMessage: 'Reassign the selected tasks' },
  reassignTo: { id: 'inbox.reassign.to', defaultMessage: 'New person' },
  reassignReason: { id: 'inbox.reassign.reason', defaultMessage: 'Reason' },
  reassignReasonHint: {
    id: 'inbox.reassign.reasonHint',
    defaultMessage: 'Both people are told, and the reason goes into the memo’s history.',
  },
  reassignPersonRequired: { id: 'inbox.reassign.personRequired', defaultMessage: 'Choose the new person.' },
  reassignReasonRequired: { id: 'inbox.reassign.reasonRequired', defaultMessage: 'A reason is required.' },
  reassignConfirm: { id: 'inbox.reassign.confirm', defaultMessage: 'Reassign' },
  cancel: { id: 'inbox.cancel', defaultMessage: 'Cancel' },
})

/** Messages with values. */
export const imv = defineMessages<{
  selected: { count: number }
  selectItem: { name: string }
  doneCLAIM: { count: number }
  doneRELEASE: { count: number }
  doneWATCH: { count: number }
  doneUNWATCH: { count: number }
  doneREASSIGN: { count: number }
  countCHANGED: { count: number }
  countFORBIDDEN: { count: number }
  countNOT_FOUND: { count: number }
  itemResult: { name: string; result: string }
  since: { time: string }
  teamCount: { count: number; overdue: number }
  holderStep: { name: string; step: string }
  referenceUnit: { reference: string; unit: string }
  waiting: { days: number }
  reassignLead: { count: number }
}>({
  selected: { id: 'inbox.bulk.selected', defaultMessage: '{count, plural, one {# selected} other {# selected}}' },
  selectItem: { id: 'inbox.select', defaultMessage: 'Select {name}' },
  doneCLAIM: { id: 'inbox.bulk.done.claim', defaultMessage: '{count, plural, one {# claimed} other {# claimed}}' },
  doneRELEASE: {
    id: 'inbox.bulk.done.release',
    defaultMessage: '{count, plural, one {# released} other {# released}}',
  },
  doneWATCH: { id: 'inbox.bulk.done.watch', defaultMessage: '{count, plural, one {# followed} other {# followed}}' },
  doneUNWATCH: {
    id: 'inbox.bulk.done.unwatch',
    defaultMessage: '{count, plural, one {# unfollowed} other {# unfollowed}}',
  },
  doneREASSIGN: {
    id: 'inbox.bulk.done.reassign',
    defaultMessage: '{count, plural, one {# reassigned} other {# reassigned}}',
  },
  countCHANGED: {
    id: 'inbox.bulk.count.changed',
    defaultMessage: '{count, plural, one {# not changed} other {# not changed}}: it was decided or moved meanwhile',
  },
  countFORBIDDEN: {
    id: 'inbox.bulk.count.forbidden',
    defaultMessage: '{count, plural, one {# not allowed} other {# not allowed}}',
  },
  countNOT_FOUND: {
    id: 'inbox.bulk.count.notFound',
    defaultMessage: '{count, plural, one {# no longer found} other {# no longer found}}',
  },
  itemResult: { id: 'inbox.bulk.itemResult', defaultMessage: '{name}: {result}' },
  since: { id: 'inbox.watching.since', defaultMessage: 'Following since {time}' },
  teamCount: {
    id: 'inbox.team.count',
    defaultMessage:
      '{count, plural, one {# task} other {# tasks}} · {overdue, plural, =0 {none overdue} other {# overdue}}',
  },
  holderStep: { id: 'inbox.team.holderStep', defaultMessage: '{name} · {step}' },
  referenceUnit: { id: 'inbox.team.referenceUnit', defaultMessage: '{reference} · {unit}' },
  waiting: {
    id: 'inbox.team.waiting',
    defaultMessage:
      '{days, plural, =0 {Waiting since today} one {Waiting # working day} other {Waiting # working days}}',
  },
  reassignLead: {
    id: 'inbox.reassign.lead',
    defaultMessage:
      '{count, plural, one {The task goes} other {The # tasks go}} to the person you choose. You confirm it is you before it is done.',
  },
})
