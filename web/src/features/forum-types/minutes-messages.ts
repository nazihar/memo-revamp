import { defineMessages } from 'react-intl'
import type { TermValues } from '@/features/terms/terms'

/**
 * Texts of the committee type's Minutes tab (Phase 4 E06 T10, flow AI): how minutes are approved and the day counts, when
 * memos move on, the resolution numbers with their live example, and the minutes template. The `forumMinutes` prefix is
 * its own catalogue part, loaded on the committee type pages.
 */
export const fmm = defineMessages({
  tab: { id: 'forumMinutes.tab', defaultMessage: 'Minutes' },
  approval: { id: 'forumMinutes.approval', defaultMessage: 'How minutes are approved' },
  atNextMeeting: {
    id: 'forumMinutes.approval.atNextMeeting',
    defaultMessage: 'At the next meeting (as “Minutes of the previous meeting”)',
  },
  byCirculation: {
    id: 'forumMinutes.approval.byCirculation',
    defaultMessage: 'By circulation, then noted at the next meeting',
  },
  circulationDays: { id: 'forumMinutes.circulationDays', defaultMessage: 'Working days members have to comment' },
  circulationDaysHint: {
    id: 'forumMinutes.circulationDaysHint',
    defaultMessage: '1 to 20. No answer counts as “no comments”.',
  },
  draftDays: { id: 'forumMinutes.draftDays', defaultMessage: 'Remind the secretary after this many working days' },
  draftDaysHint: {
    id: 'forumMinutes.draftDaysHint',
    defaultMessage: '1 to 30: when the minutes are not circulated this long after the meeting.',
  },
  memoResumes: { id: 'forumMinutes.memoResumes', defaultMessage: 'When a memo moves on after the committee decides' },
  atMeetingEnd: { id: 'forumMinutes.memoResumes.atEnd', defaultMessage: 'When the meeting is marked as held' },
  onApproval: { id: 'forumMinutes.memoResumes.onApproval', defaultMessage: 'When the minutes are approved' },
  numbers: { id: 'forumMinutes.numbers', defaultMessage: 'Resolution numbers' },
  numbersLead: {
    id: 'forumMinutes.numbersLead',
    defaultMessage:
      'The parts of the number in order. Numbers are given when the meeting is marked as held, in agenda order, and at once for a decision recorded later.',
  },
  partTEXT: { id: 'forumMinutes.part.text', defaultMessage: 'Text' },
  partCOMMITTEE_CODE: { id: 'forumMinutes.part.committeeCode', defaultMessage: 'Committee code' },
  partYEAR: { id: 'forumMinutes.part.year', defaultMessage: 'Year' },
  partFINANCIAL_YEAR: { id: 'forumMinutes.part.financialYear', defaultMessage: 'Financial year' },
  partMEETING_NUMBER: { id: 'forumMinutes.part.meetingNumber', defaultMessage: 'Meeting number' },
  partSEQUENCE: { id: 'forumMinutes.part.sequence', defaultMessage: 'Sequence' },
  digits: { id: 'forumMinutes.digits', defaultMessage: 'Digits' },
  styleLong: { id: 'forumMinutes.style.long', defaultMessage: '2026-27' },
  styleShort: { id: 'forumMinutes.style.short', defaultMessage: 'FY27' },
  reset: { id: 'forumMinutes.reset', defaultMessage: 'Start again' },
  resetYearly: { id: 'forumMinutes.reset.yearly', defaultMessage: 'Every calendar year' },
  resetFinancial: { id: 'forumMinutes.reset.financial', defaultMessage: 'Every financial year' },
  resetNever: { id: 'forumMinutes.reset.never', defaultMessage: 'Never' },
  fyStart: { id: 'forumMinutes.fyStart', defaultMessage: 'The financial year starts in' },
  example: { id: 'forumMinutes.example', defaultMessage: 'Example' },
  exampleLoading: { id: 'forumMinutes.exampleLoading', defaultMessage: 'Working out an example…' },
  template: { id: 'forumMinutes.template', defaultMessage: 'Minutes template' },
  builtIn: { id: 'forumMinutes.template.builtIn', defaultMessage: 'Built-in template' },
  upload: { id: 'forumMinutes.template.upload', defaultMessage: 'Upload a Word template…' },
  replace: { id: 'forumMinutes.template.replace', defaultMessage: 'Replace the template…' },
  uploadHint: {
    id: 'forumMinutes.template.uploadHint',
    defaultMessage:
      'A .docx file, at most 10 MB. Macros, embedded objects, links to other files and field codes are refused.',
  },
  uploadSaveFirst: {
    id: 'forumMinutes.template.saveFirst',
    defaultMessage: 'Save your other changes before uploading.',
  },
  preview: { id: 'forumMinutes.template.preview', defaultMessage: 'Preview with a sample meeting' },
  previewTitle: {
    id: 'forumMinutes.template.previewTitle',
    defaultMessage: 'The minutes template with a sample meeting',
  },
  tags: { id: 'forumMinutes.template.tags', defaultMessage: 'List of tags' },
  used: { id: 'forumMinutes.template.used', defaultMessage: 'Tags it uses' },
  problems: {
    id: 'forumMinutes.template.problems',
    defaultMessage: 'Problems to fix in Word before the version is submitted',
  },
  warnings: { id: 'forumMinutes.template.warnings', defaultMessage: 'Worth changing' },
  refusals: { id: 'forumMinutes.template.refusals', defaultMessage: 'The file was refused' },
  copied: { id: 'forumMinutes.template.copied', defaultMessage: 'Tag copied.' },
  failed: { id: 'forumMinutes.failed', defaultMessage: 'That did not work. Try again.' },
  TEMPLATE_PROBLEMS: {
    id: 'forumMinutes.templateProblems',
    defaultMessage: 'Fix the template’s problems first: the preview needs a template memo can fill.',
  },
})

export const fmmv = defineMessages<{
  moveUp: { name: string }
  moveDown: { name: string }
  remove: { name: string }
  add: { name: string }
  exampleLine: { now: string; next: string }
  exampleOne: { now: string }
  file: { name: string; size: string }
  where: { part: string; paragraph: number; excerpt: string }
  suggestion: { suggestion: string }
  copy: { tag: string }
  problem: { code: string; tag: string }
  refusal: { rule: string; where: string }
}>({
  moveUp: { id: 'forumMinutes.moveUp', defaultMessage: 'Move {name} earlier' },
  moveDown: { id: 'forumMinutes.moveDown', defaultMessage: 'Move {name} later' },
  remove: { id: 'forumMinutes.remove', defaultMessage: 'Remove {name}' },
  add: { id: 'forumMinutes.add', defaultMessage: 'Add {name}' },
  exampleLine: { id: 'forumMinutes.exampleLine', defaultMessage: '{now} — the first of the next period: {next}' },
  exampleOne: { id: 'forumMinutes.exampleOne', defaultMessage: '{now}' },
  file: { id: 'forumMinutes.template.file', defaultMessage: '{name} ({size})' },
  where: { id: 'forumMinutes.template.where', defaultMessage: '{part}, paragraph {paragraph, number}: “{excerpt}”' },
  suggestion: { id: 'forumMinutes.template.suggestion', defaultMessage: 'Did you mean {suggestion}?' },
  copy: { id: 'forumMinutes.template.copy', defaultMessage: 'Copy {tag}' },
  problem: {
    id: 'forumMinutes.template.problem',
    defaultMessage:
      '{code, select, MALFORMED_TAG {A tag is not written correctly} UNSUPPORTED_TAG {This kind of tag is not supported: {tag}} UNKNOWN_TAG {{tag} is not a known tag} UNBALANCED_SECTION {A section is opened and not closed, or closed and not opened} LOOP_WITHOUT_ROW {This list must be inside a table row: {tag}} UNKNOWN_ROW_FIELD {Unknown field of a table row: {tag}} TAG_IN_LOOP_ROW {Only the list’s own fields can go in its row: {tag}} BLOCK_PLACEMENT {The items block must start and end in paragraphs of their own, in the body, not in a table, and only once: {tag}} DEPRECATED_TAG {{tag} still works but has a newer name} other {Too many tags}}',
  },
  refusal: { id: 'forumMinutes.template.refusal', defaultMessage: '{rule} ({where})' },
})

export const fmmt = defineMessages<Record<'approvalLead' | 'memoResumesLead', TermValues>>({
  approvalLead: {
    id: 'forumMinutes.approvalLead',
    defaultMessage:
      'Every {forum} of this type follows the same path: the chair reviews the draft, members comment, then the chair approves.',
  },
  memoResumesLead: {
    id: 'forumMinutes.memoResumesLead',
    defaultMessage: 'Choose “When the minutes are approved” only when the {forum}’s charter requires it.',
  },
})
