import { defineMessages } from 'react-intl'

/** The delegation of authority screens (design E04 §3, §5, ux-review flow 5). */
export const dm = defineMessages({
  title: { id: 'workflows.doa.title', defaultMessage: 'Delegation of authority' },
  lead: {
    id: 'workflows.doa.lead',
    defaultMessage:
      'Rules say who must approve which memos, group by group. A new version takes effect on its date once someone other than its author approves it; memos keep the version in force when they started.',
  },
  caption: { id: 'workflows.doa.caption', defaultMessage: 'Versions, newest first' },
  colVersion: { id: 'workflows.doa.col.version', defaultMessage: 'Version' },
  colState: { id: 'workflows.doa.col.state', defaultMessage: 'State' },
  colEffective: { id: 'workflows.doa.col.effective', defaultMessage: 'Takes effect' },
  colSubmitted: { id: 'workflows.doa.col.submitted', defaultMessage: 'Submitted by' },
  colReviewed: { id: 'workflows.doa.col.reviewed', defaultMessage: 'Reviewed by' },
  colActions: { id: 'workflows.doa.col.actions', defaultMessage: 'Actions' },
  empty: { id: 'workflows.doa.empty', defaultMessage: 'No versions yet. Start a draft.' },
  newDraft: { id: 'workflows.doa.newDraft', defaultMessage: 'New draft' },
  open: { id: 'workflows.doa.open', defaultMessage: 'Open' },
  notSet: { id: 'workflows.doa.notSet', defaultMessage: 'Not set' },
  effectiveFrom: { id: 'workflows.doa.effectiveFrom', defaultMessage: 'Takes effect on' },
  effectiveHint: {
    id: 'workflows.doa.effectiveFrom.hint',
    defaultMessage: 'Today or later. Memos started before then keep the version in force at their start.',
  },
  save: { id: 'workflows.doa.save', defaultMessage: 'Save draft' },
  submit: { id: 'workflows.doa.submit', defaultMessage: 'Submit for review' },
  approve: { id: 'workflows.doa.approve', defaultMessage: 'Approve and publish' },
  sendBack: { id: 'workflows.doa.sendBack', defaultMessage: 'Send back' },
  remark: { id: 'workflows.doa.remark', defaultMessage: 'Remark' },
  remarkHint: { id: 'workflows.doa.remark.hint', defaultMessage: 'Required to send back.' },
  rules: { id: 'workflows.doa.rules', defaultMessage: 'Rules' },
  rulesLead: {
    id: 'workflows.doa.rules.lead',
    defaultMessage: 'The first rule that fits a memo applies. Order them from the most specific to the most general.',
  },
  noRules: { id: 'workflows.doa.noRules', defaultMessage: 'No rules yet.' },
  addRule: { id: 'workflows.doa.addRule', defaultMessage: 'Add rule' },
  ruleName: { id: 'workflows.doa.rule.name', defaultMessage: 'Name' },
  memoType: { id: 'workflows.doa.rule.memoType', defaultMessage: 'Memo type' },
  anyMemoType: { id: 'workflows.doa.rule.anyMemoType', defaultMessage: 'Any memo type' },
  unit: { id: 'workflows.doa.rule.unit', defaultMessage: 'Initiator’s unit' },
  anyUnit: { id: 'workflows.doa.rule.anyUnit', defaultMessage: 'Any unit' },
  subtree: { id: 'workflows.doa.rule.subtree', defaultMessage: 'Include the units below it' },
  condition: { id: 'workflows.doa.rule.condition', defaultMessage: 'Condition' },
  conditionHint: {
    id: 'workflows.doa.rule.condition.hint',
    defaultMessage: 'For example item.classificationRank >= 2. Leave empty for every memo.',
  },
  groups: { id: 'workflows.doa.rule.groups', defaultMessage: 'Approval groups, in order' },
  addGroup: { id: 'workflows.doa.rule.addGroup', defaultMessage: 'Add group' },
  needed: { id: 'workflows.doa.group.needed', defaultMessage: 'Approvals needed' },
  memberKind: { id: 'workflows.doa.group.memberKind', defaultMessage: 'Add a' },
  position: { id: 'workflows.doa.group.position', defaultMessage: 'Position' },
  role: { id: 'workflows.doa.group.role', defaultMessage: 'Role' },
  roleHint: {
    id: 'workflows.doa.group.role.hint',
    defaultMessage: 'Its holders in the initiator’s unit or above.',
  },
  addMember: { id: 'workflows.doa.group.addMember', defaultMessage: 'Add member' },
  noMembers: { id: 'workflows.doa.group.noMembers', defaultMessage: 'No members yet.' },
  removeGroup: { id: 'workflows.doa.group.remove', defaultMessage: 'Remove group' },
  invalid: { id: 'workflows.doa.invalid', defaultMessage: 'The version has problems:' },
  conflict: {
    id: 'workflows.doa.conflict',
    defaultMessage: 'Someone changed this version meanwhile. It has been reloaded.',
  },
  failed: { id: 'workflows.doa.failed', defaultMessage: 'That did not work. Try again.' },
  done: { id: 'workflows.doa.done', defaultMessage: 'Done.' },
  DRAFT_EXISTS: {
    id: 'workflows.doa.refused.draftExists',
    defaultMessage: 'There is already a draft or a version in review.',
  },
  SAME_PERSON: {
    id: 'workflows.doa.refused.samePerson',
    defaultMessage: 'Someone other than the submitter has to review it.',
  },
  EFFECTIVE_DATE_TAKEN: {
    id: 'workflows.doa.refused.dateTaken',
    defaultMessage: 'Another version takes effect on that date.',
  },
  WRONG_STATE: { id: 'workflows.doa.refused.wrongState', defaultMessage: 'The version has moved on meanwhile.' },
  simulator: { id: 'workflows.doa.simulator', defaultMessage: 'Who approves this?' },
  simulatorLead: {
    id: 'workflows.doa.simulator.lead',
    defaultMessage: 'Try a memo against a version: who would have to approve it, group by group, today.',
  },
  initiator: { id: 'workflows.doa.sim.initiator', defaultMessage: 'Initiator' },
  classificationRank: { id: 'workflows.doa.sim.rank', defaultMessage: 'Classification rank' },
  version: { id: 'workflows.doa.sim.version', defaultMessage: 'Version' },
  inForce: { id: 'workflows.doa.sim.inForce', defaultMessage: 'The one in force today' },
  simulate: { id: 'workflows.doa.sim.run', defaultMessage: 'Simulate' },
  noDoa: { id: 'workflows.doa.sim.noDoa', defaultMessage: 'No version is in force.' },
  noRule: { id: 'workflows.doa.sim.noRule', defaultMessage: 'No rule applies to such a memo.' },
  nobody: { id: 'workflows.doa.sim.nobody', defaultMessage: 'Nobody holds these now.' },
  chooseInitiator: { id: 'workflows.doa.sim.chooseInitiator', defaultMessage: 'Choose the initiator.' },
})

export const dmv = defineMessages<{
  version: { number: number }
  rule: { number: number }
  group: { number: number }
  removeMember: { name: string }
  result: { version: number; rule: string }
  needs: { needed: number; names: string }
  roleIn: { role: string }
  ruleTitle: { number: number; name: string }
  moveUp: { rule: string }
  moveDown: { rule: string }
  removeRule: { rule: string }
  openVersion: { number: number }
  reviewRemark: { remark: string }
  unitSubtree: { unit: string }
}>({
  ruleTitle: { id: 'workflows.doa.ruleTitle', defaultMessage: 'Rule {number} · {name}' },
  moveUp: { id: 'workflows.doa.moveUp', defaultMessage: 'Move {rule} up' },
  moveDown: { id: 'workflows.doa.moveDown', defaultMessage: 'Move {rule} down' },
  removeRule: { id: 'workflows.doa.removeRuleNamed', defaultMessage: 'Remove {rule}' },
  openVersion: { id: 'workflows.doa.openVersion', defaultMessage: 'Open version {number}' },
  reviewRemark: { id: 'workflows.doa.reviewRemarkText', defaultMessage: 'Reviewer’s remark: {remark}' },
  unitSubtree: { id: 'workflows.doa.unitSubtree', defaultMessage: '{unit}, with the units below it' },
  version: { id: 'workflows.doa.versionNumber', defaultMessage: 'Version {number}' },
  rule: { id: 'workflows.doa.ruleNumber', defaultMessage: 'Rule {number}' },
  group: { id: 'workflows.doa.groupNumber', defaultMessage: 'Group {number}' },
  removeMember: { id: 'workflows.doa.removeMember', defaultMessage: 'Remove {name}' },
  result: { id: 'workflows.doa.sim.result', defaultMessage: 'Version {version}, rule “{rule}”' },
  needs: {
    id: 'workflows.doa.sim.needs',
    defaultMessage: '{needed, plural, one {# approval} other {# approvals}} from: {names}',
  },
  roleIn: { id: 'workflows.doa.roleIn', defaultMessage: '{role} (initiator’s unit or above)' },
})
