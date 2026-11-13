import { defineMessages } from 'react-intl'

/** The route editor's words for positions and groups (design E04 §4.7, ux-review flow 6). */
export const rm = defineMessages({
  kind: { id: 'memo.route.kind', defaultMessage: 'Add' },
  person: { id: 'memo.route.kind.person', defaultMessage: 'A person' },
  position: { id: 'memo.route.kind.position', defaultMessage: 'A position' },
  group: { id: 'memo.route.kind.group', defaultMessage: 'A group' },
  positionPicker: { id: 'memo.route.position', defaultMessage: 'Position' },
  positionHint: {
    id: 'memo.route.position.hint',
    defaultMessage: 'Whoever holds the position when the memo reaches it approves.',
  },
  mode: { id: 'memo.route.group.mode', defaultMessage: 'The group decides when' },
  all: { id: 'memo.route.group.all', defaultMessage: 'all of them approve' },
  any: { id: 'memo.route.group.any', defaultMessage: 'any one of them approves' },
  memberKind: { id: 'memo.route.group.memberKind', defaultMessage: 'Member is' },
  member: { id: 'memo.route.group.member', defaultMessage: 'Member' },
  addMember: { id: 'memo.route.group.addMember', defaultMessage: 'Add member' },
  addGroup: { id: 'memo.route.group.add', defaultMessage: 'Add group' },
  groupNeeds: { id: 'memo.route.group.needs', defaultMessage: 'A group needs at least two members.' },
  members: { id: 'memo.route.group.members', defaultMessage: 'Members' },
})

export const rmv = defineMessages<{
  groupLabel: { mode: string; names: string }
  positionLabel: { title: string; holder: string }
  positionVacant: { title: string }
  removeMember: { name: string }
}>({
  groupLabel: {
    id: 'memo.route.group.label',
    defaultMessage: '{mode, select, ALL {All of} other {Any of}}: {names}',
  },
  positionLabel: { id: 'memo.route.position.label', defaultMessage: '{title} (now {holder})' },
  positionVacant: { id: 'memo.route.position.vacant', defaultMessage: '{title} (vacant)' },
  removeMember: { id: 'memo.route.group.removeMember', defaultMessage: 'Remove {name} from the group' },
})
