import { defineMessages, type NoMessageValues } from 'react-intl'

/** A message's values: the organisation's terms (`{forum}` and its kin), numbers and words. */
type Args = Record<string, string | number>

type WithValues =
  | 'lead'
  | 'noScope'
  | 'noScopeHint'
  | 'queryHint'
  | 'committee'
  | 'allCommittees'
  | 'dissolved'
  | 'chip'
  | 'removeFilter'
  | 'count'
  | 'countCapped'
  | 'byCommittee'
  | 'facetValue'
  | 'emptyAll'
  | 'meetingItem'
  | 'meetingOnly'
  | 'resolution'
  | 'untitled'
  | 'conditions'
  | 'validUntil'
  | 'validityPassed'
  | 'minutesApprovedOn'
  | 'memoLink'
  | 'outOfScope'
  | 'sortNeedsOne'
  | 'checkComplete'
  | 'checkMissing'
  | 'checkDuplicates'
  | 'checkOutOfRange'
  | 'checkNone'
  | 'checkMore'
  | 'checkForbidden'
  | 'exportCount'
  | 'exportCountCapped'
  | 'exportAllCommittees'
  | 'exportSomeCommittees'
  | 'exportDates'
  | 'exportFromOnly'
  | 'exportToOnly'
  | 'exportWords'
  | 'exportAbout'
  | 'exportTooManyPdf'
  | 'exportTooManyXlsx'
  | 'exportTooManyMore'
  | 'exportRateLimitedAt'
  | 'exportForbidden'
  | 'exportOutOfScope'

type Plain =
  | 'title'
  | 'queryLabel'
  | 'search'
  | 'filters'
  | 'committeeType'
  | 'any'
  | 'from'
  | 'to'
  | 'outcome'
  | 'resolutionsOnly'
  | 'moreFilters'
  | 'exceptions'
  | 'memoType'
  | 'state'
  | 'toBeforeFrom'
  | 'activeFilters'
  | 'reset'
  | 'searching'
  | 'sort'
  | 'sortNewest'
  | 'sortOldest'
  | 'sortRelevance'
  | 'sortNumber'
  | 'restrictedNote'
  | 'byOutcome'
  | 'byYear'
  | 'facetHint'
  | 'results'
  | 'showMore'
  | 'loadingMore'
  | 'empty'
  | 'emptyHint'
  | 'noNumber'
  | 'untitledNoItem'
  | 'restricted'
  | 'reasonRecused'
  | 'reasonRestricted'
  | 'withDissent'
  | 'toBeRatified'
  | 'ratified'
  | 'corrected'
  | 'memoLinkNoNumber'
  | 'stateRECORDED'
  | 'stateNUMBERED'
  | 'stateRELEASED'
  | 'stateFINAL'
  | 'stateVOID'
  | 'inForce'
  | 'outcomeAPPROVED'
  | 'outcomeAPPROVED_WITH_CONDITIONS'
  | 'outcomeREJECTED'
  | 'outcomeDEFERRED'
  | 'outcomeREFERRED'
  | 'outcomeRETURNED'
  | 'outcomeWITHDRAWN'
  | 'outcomeNOTED'
  | 'flagWITH_CONDITIONS'
  | 'flagWITH_DISSENT'
  | 'flagTO_BE_RATIFIED'
  | 'flagCORRECTED'
  | 'flagVALIDITY_PASSED'
  | 'textTooLong'
  | 'tooManyTerms'
  | 'range'
  | 'tooManyValues'
  | 'cursorInvalid'
  | 'invalid'
  | 'rateLimited'
  | 'timeout'
  | 'searchAgain'
  | 'checkNumbers'
  | 'checking'
  | 'checkFailed'
  | 'export'
  | 'exportTitle'
  | 'exportAllDates'
  | 'format'
  | 'formatXlsx'
  | 'formatXlsxHint'
  | 'formatPdf'
  | 'formatPdfHint'
  | 'exportRestricted'
  | 'exportRateLimited'
  | 'exportFailed'
  | 'cancel'
  | 'download'
  | 'preparing'
  | 'downloaded'

type Values = Record<WithValues, Args> & Record<Plain, NoMessageValues>

/**
 * The decision register (Phase 4 E08 T6, flows AS and AT). These load with `/decisions` (the `decisionRegister`
 * catalogue part), never with the core part. Texts that name the committee take the organisation's terms (ADR-0032):
 * `{forum}`, `{Forum}`, `{forums}`, `{forumGender}`.
 */
export const drm = defineMessages<Values>({
  title: { id: 'decisionRegister.title', defaultMessage: 'Decisions' },
  lead: {
    id: 'decisionRegister.lead',
    defaultMessage: 'Search decisions and resolutions of the {forums} you can see.',
  },
  noScope: {
    id: 'decisionRegister.noScope',
    defaultMessage: '{forumGender, select, other {You are not a member or officer of any {forum}.}}',
  },
  noScopeHint: {
    id: 'decisionRegister.noScopeHint',
    defaultMessage: 'The register is for {forum} members, officers and oversight.',
  },

  // The search form
  queryLabel: { id: 'decisionRegister.query', defaultMessage: 'Words, a resolution number or a memo number' },
  queryHint: {
    id: 'decisionRegister.queryHint',
    defaultMessage: 'For example: liquidity, BRC/2026/014, or 14 with one {forum} chosen.',
  },
  search: { id: 'decisionRegister.search', defaultMessage: 'Search' },
  filters: { id: 'decisionRegister.filters', defaultMessage: 'Filters' },
  committee: { id: 'decisionRegister.committee', defaultMessage: '{Forum}' },
  allCommittees: { id: 'decisionRegister.allCommittees', defaultMessage: 'All my {forums}' },
  dissolved: { id: 'decisionRegister.dissolved', defaultMessage: '{name} (dissolved)' },
  committeeType: { id: 'decisionRegister.committeeType', defaultMessage: 'Type' },
  any: { id: 'decisionRegister.any', defaultMessage: 'Any' },
  from: { id: 'decisionRegister.from', defaultMessage: 'From' },
  to: { id: 'decisionRegister.to', defaultMessage: 'To' },
  outcome: { id: 'decisionRegister.outcome', defaultMessage: 'Outcome' },
  resolutionsOnly: { id: 'decisionRegister.resolutionsOnly', defaultMessage: 'Resolutions only' },
  moreFilters: { id: 'decisionRegister.moreFilters', defaultMessage: 'More filters' },
  exceptions: { id: 'decisionRegister.exceptions', defaultMessage: 'Exceptions' },
  memoType: { id: 'decisionRegister.memoType', defaultMessage: 'Memo type' },
  state: { id: 'decisionRegister.state', defaultMessage: 'State' },
  toBeforeFrom: {
    id: 'decisionRegister.toBeforeFrom',
    defaultMessage: 'The end date must be on or after the start date.',
  },

  // Chips, count and order
  activeFilters: { id: 'decisionRegister.activeFilters', defaultMessage: 'Active filters' },
  chip: { id: 'decisionRegister.chip', defaultMessage: '{name}: {value}' },
  removeFilter: { id: 'decisionRegister.removeFilter', defaultMessage: 'Remove filter {filter}' },
  reset: { id: 'decisionRegister.reset', defaultMessage: 'Reset' },
  count: {
    id: 'decisionRegister.count',
    defaultMessage: '{count, plural, =0 {No decisions} one {Showing # decision} other {Showing # decisions}}',
  },
  countCapped: { id: 'decisionRegister.countCapped', defaultMessage: 'Showing more than {count} decisions' },
  searching: { id: 'decisionRegister.searching', defaultMessage: 'Searching…' },
  sort: { id: 'decisionRegister.sort', defaultMessage: 'Order' },
  sortNewest: { id: 'decisionRegister.sort.newest', defaultMessage: 'Newest first' },
  sortOldest: { id: 'decisionRegister.sort.oldest', defaultMessage: 'Oldest first' },
  sortRelevance: { id: 'decisionRegister.sort.relevance', defaultMessage: 'Best match' },
  sortNumber: { id: 'decisionRegister.sort.number', defaultMessage: 'Number order' },
  restrictedNote: {
    id: 'decisionRegister.restrictedNote',
    defaultMessage:
      'Restricted items are not matched by words, an outcome, a memo type or an exception. A resolution number still finds them.',
  },

  // Facets
  byCommittee: { id: 'decisionRegister.byCommittee', defaultMessage: 'By {forum}' },
  byOutcome: { id: 'decisionRegister.byOutcome', defaultMessage: 'By outcome' },
  byYear: { id: 'decisionRegister.byYear', defaultMessage: 'By year' },
  facetValue: { id: 'decisionRegister.facetValue', defaultMessage: '{label} ({count})' },
  facetHint: {
    id: 'decisionRegister.facetHint',
    defaultMessage: 'Outcome counts include only decisions you can see in full.',
  },

  // Results
  results: { id: 'decisionRegister.results', defaultMessage: 'Decisions' },
  showMore: { id: 'decisionRegister.showMore', defaultMessage: 'Show more decisions' },
  loadingMore: { id: 'decisionRegister.loadingMore', defaultMessage: 'Loading more decisions…' },
  empty: { id: 'decisionRegister.empty', defaultMessage: 'No decisions match.' },
  emptyHint: { id: 'decisionRegister.emptyHint', defaultMessage: 'Try a wider date range or fewer filters.' },
  emptyAll: { id: 'decisionRegister.emptyAll', defaultMessage: 'No decision has been recorded in your {forums} yet.' },
  meetingItem: { id: 'decisionRegister.meetingItem', defaultMessage: '{meeting}, item {item}' },
  meetingOnly: { id: 'decisionRegister.meetingOnly', defaultMessage: 'Meeting of {date}' },
  resolution: { id: 'decisionRegister.resolution', defaultMessage: 'Resolution {number}' },
  noNumber: { id: 'decisionRegister.noNumber', defaultMessage: 'No resolution number' },
  untitled: { id: 'decisionRegister.untitled', defaultMessage: 'Item {item}' },
  untitledNoItem: { id: 'decisionRegister.untitledNoItem', defaultMessage: 'Decision without a title' },
  restricted: { id: 'decisionRegister.restricted', defaultMessage: 'Restricted item' },
  reasonRecused: { id: 'decisionRegister.reason.recused', defaultMessage: 'You declared an interest in this item.' },
  reasonRestricted: {
    id: 'decisionRegister.reason.restricted',
    defaultMessage: 'This item is restricted to named members.',
  },
  conditions: {
    id: 'decisionRegister.conditions',
    defaultMessage: '{count, plural, one {# condition} other {# conditions}}',
  },
  withDissent: { id: 'decisionRegister.withDissent', defaultMessage: 'with dissent' },
  toBeRatified: { id: 'decisionRegister.toBeRatified', defaultMessage: 'to be ratified' },
  ratified: { id: 'decisionRegister.ratified', defaultMessage: 'ratified' },
  corrected: { id: 'decisionRegister.corrected', defaultMessage: 'corrected' },
  validUntil: { id: 'decisionRegister.validUntil', defaultMessage: 'valid until {date}' },
  validityPassed: { id: 'decisionRegister.validityPassed', defaultMessage: 'validity passed on {date}' },
  minutesApprovedOn: { id: 'decisionRegister.minutesApprovedOn', defaultMessage: 'Minutes approved {date}' },
  memoLink: { id: 'decisionRegister.memoLink', defaultMessage: 'Memo {number}' },
  memoLinkNoNumber: { id: 'decisionRegister.memoLinkNoNumber', defaultMessage: 'The memo' },

  // States in words (flow AS)
  stateRECORDED: { id: 'decisionRegister.state.recorded', defaultMessage: 'Recorded at the meeting' },
  stateNUMBERED: { id: 'decisionRegister.state.numbered', defaultMessage: 'Numbered' },
  stateRELEASED: { id: 'decisionRegister.state.released', defaultMessage: 'Sent to the memo' },
  stateFINAL: { id: 'decisionRegister.state.final', defaultMessage: 'Minutes approved' },
  stateVOID: { id: 'decisionRegister.state.void', defaultMessage: 'Void' },
  inForce: { id: 'decisionRegister.inForce', defaultMessage: 'In force' },

  // Outcomes (the meanings; a committee's own label is shown on its rows)
  outcomeAPPROVED: { id: 'decisionRegister.outcome.approved', defaultMessage: 'Approved' },
  outcomeAPPROVED_WITH_CONDITIONS: {
    id: 'decisionRegister.outcome.approvedWithConditions',
    defaultMessage: 'Approved with conditions',
  },
  outcomeREJECTED: { id: 'decisionRegister.outcome.rejected', defaultMessage: 'Rejected' },
  outcomeDEFERRED: { id: 'decisionRegister.outcome.deferred', defaultMessage: 'Deferred' },
  outcomeREFERRED: { id: 'decisionRegister.outcome.referred', defaultMessage: 'Referred' },
  outcomeRETURNED: { id: 'decisionRegister.outcome.returned', defaultMessage: 'Returned for more information' },
  outcomeWITHDRAWN: { id: 'decisionRegister.outcome.withdrawn', defaultMessage: 'Withdrawn' },
  outcomeNOTED: { id: 'decisionRegister.outcome.noted', defaultMessage: 'Noted' },

  // Exception filters
  flagWITH_CONDITIONS: { id: 'decisionRegister.flag.withConditions', defaultMessage: 'With conditions' },
  flagWITH_DISSENT: { id: 'decisionRegister.flag.withDissent', defaultMessage: 'With dissent' },
  flagTO_BE_RATIFIED: { id: 'decisionRegister.flag.toBeRatified', defaultMessage: 'To be ratified' },
  flagCORRECTED: { id: 'decisionRegister.flag.corrected', defaultMessage: 'Corrected' },
  flagVALIDITY_PASSED: { id: 'decisionRegister.flag.validityPassed', defaultMessage: 'Validity passed' },

  // Refusals, in words
  textTooLong: { id: 'decisionRegister.problem.textTooLong', defaultMessage: 'Use at most 200 characters.' },
  tooManyTerms: { id: 'decisionRegister.problem.tooManyTerms', defaultMessage: 'Use at most 12 words.' },
  outOfScope: {
    id: 'decisionRegister.problem.outOfScope',
    defaultMessage:
      '{forumGender, select, other {This {forum} is not in your register. Choose one from the list, or reset the filters.}}',
  },
  range: {
    id: 'decisionRegister.problem.range',
    defaultMessage: 'The end date must be on or after the start date, and at most 25 years after it.',
  },
  sortNeedsOne: {
    id: 'decisionRegister.problem.sortNeedsOne',
    defaultMessage: 'Number order needs one {forum}. Choose a {forum} first.',
  },
  tooManyValues: { id: 'decisionRegister.problem.tooManyValues', defaultMessage: 'A filter has too many values.' },
  cursorInvalid: {
    id: 'decisionRegister.problem.cursor',
    defaultMessage: 'The list has changed since it was shown. Search again to see it from the start.',
  },
  invalid: { id: 'decisionRegister.problem.invalid', defaultMessage: 'This search cannot be made. Check the filters.' },
  rateLimited: {
    id: 'decisionRegister.problem.rateLimited',
    defaultMessage: 'You have searched many times in the last minute. Wait a moment, then try again.',
  },
  timeout: {
    id: 'decisionRegister.problem.timeout',
    defaultMessage: 'The search took too long. Add words or narrow the dates, then try again.',
  },
  searchAgain: { id: 'decisionRegister.searchAgain', defaultMessage: 'Search again' },

  // Check the numbers (E06's per-committee register)
  checkNumbers: { id: 'decisionRegister.check', defaultMessage: 'Check the numbers' },
  checking: { id: 'decisionRegister.check.checking', defaultMessage: 'Checking the numbers…' },
  checkComplete: {
    id: 'decisionRegister.check.complete',
    defaultMessage: '{period}: resolutions {first} to {last} are all accounted for ({inForce} in force, {void} void).',
  },
  checkMissing: {
    id: 'decisionRegister.check.missing',
    defaultMessage:
      '{period}: resolutions {first} to {last}, {count, plural, one {# number is missing} other {# numbers are missing}}: {numbers}.',
  },
  checkDuplicates: { id: 'decisionRegister.check.duplicates', defaultMessage: 'Numbers given twice: {numbers}.' },
  checkOutOfRange: {
    id: 'decisionRegister.check.outOfRange',
    defaultMessage: 'Numbers outside the range: {numbers}.',
  },
  checkNone: { id: 'decisionRegister.check.none', defaultMessage: '{period}: no resolution has a number yet.' },
  checkMore: { id: 'decisionRegister.check.more', defaultMessage: '{numbers} and {count} more' },
  checkForbidden: {
    id: 'decisionRegister.check.forbidden',
    defaultMessage: 'Only the {forum}’s secretaries, chair and oversight can check its numbers.',
  },
  checkFailed: { id: 'decisionRegister.check.failed', defaultMessage: 'The numbers could not be checked. Try again.' },

  // Export (flow AT)
  export: { id: 'decisionRegister.export', defaultMessage: 'Export…' },
  exportTitle: { id: 'decisionRegister.export.title', defaultMessage: 'Export decisions' },
  exportCount: {
    id: 'decisionRegister.export.count',
    defaultMessage: '{count, plural, one {# decision matches your filters:} other {# decisions match your filters:}}',
  },
  exportCountCapped: {
    id: 'decisionRegister.export.countCapped',
    defaultMessage: 'More than {count} decisions match your filters:',
  },
  exportAllCommittees: { id: 'decisionRegister.export.allCommittees', defaultMessage: 'All your {forums}' },
  exportSomeCommittees: {
    id: 'decisionRegister.export.someCommittees',
    defaultMessage: '{count, plural, other {# {forums}}}',
  },
  exportAllDates: { id: 'decisionRegister.export.allDates', defaultMessage: 'All dates' },
  exportDates: { id: 'decisionRegister.export.dates', defaultMessage: '{from} – {to}' },
  exportFromOnly: { id: 'decisionRegister.export.fromOnly', defaultMessage: 'From {from}' },
  exportToOnly: { id: 'decisionRegister.export.toOnly', defaultMessage: 'Up to {to}' },
  exportWords: { id: 'decisionRegister.export.words', defaultMessage: 'Words: “{words}”' },
  format: { id: 'decisionRegister.export.format', defaultMessage: 'Format' },
  formatXlsx: { id: 'decisionRegister.export.xlsx', defaultMessage: 'Spreadsheet (.xlsx)' },
  formatXlsxHint: {
    id: 'decisionRegister.export.xlsxHint',
    defaultMessage: 'For sorting and checking, up to 20,000 rows.',
  },
  formatPdf: { id: 'decisionRegister.export.pdf', defaultMessage: 'PDF' },
  formatPdfHint: { id: 'decisionRegister.export.pdfHint', defaultMessage: 'For filing, up to 2,000 rows.' },
  exportAbout: {
    id: 'decisionRegister.export.about',
    defaultMessage:
      'The export includes a page “About this export” with these filters, your name and the time, and a number check for each {forum} and year.',
  },
  exportRestricted: {
    id: 'decisionRegister.export.restricted',
    defaultMessage: 'Restricted items show their number only. Each export is recorded.',
  },
  exportTooManyPdf: {
    id: 'decisionRegister.export.tooManyPdf',
    defaultMessage: 'This is {count} decisions; a PDF holds up to 2,000. Narrow the dates or choose a spreadsheet.',
  },
  exportTooManyXlsx: {
    id: 'decisionRegister.export.tooManyXlsx',
    defaultMessage: 'This is {count} decisions; a spreadsheet holds up to 20,000. Narrow the dates or the filters.',
  },
  exportTooManyMore: {
    id: 'decisionRegister.export.tooManyMore',
    defaultMessage:
      'This is more than {count} decisions; the {format, select, PDF {PDF holds up to 2,000} other {spreadsheet holds up to 20,000}}. Narrow the dates or the filters.',
  },
  exportRateLimitedAt: {
    id: 'decisionRegister.export.rateLimitedAt',
    defaultMessage: 'You have exported 5 times in the last hour. Please try again at {time}.',
  },
  exportRateLimited: {
    id: 'decisionRegister.export.rateLimited',
    defaultMessage: 'You have exported 5 times in the last hour. Please try again later.',
  },
  exportForbidden: {
    id: 'decisionRegister.export.forbidden',
    defaultMessage: 'Only the {forums}’ secretaries, chairs and oversight can export the register.',
  },
  exportOutOfScope: {
    id: 'decisionRegister.export.outOfScope',
    defaultMessage:
      'You can export the decisions of the {forums} where you are a secretary or the chair. Choose one of those.',
  },
  exportFailed: { id: 'decisionRegister.export.failed', defaultMessage: 'The export could not be made. Try again.' },
  cancel: { id: 'decisionRegister.export.cancel', defaultMessage: 'Cancel' },
  download: { id: 'decisionRegister.export.download', defaultMessage: 'Download' },
  preparing: { id: 'decisionRegister.export.preparing', defaultMessage: 'Preparing the file…' },
  downloaded: { id: 'decisionRegister.export.downloaded', defaultMessage: 'The register has been downloaded.' },
})
