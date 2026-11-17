import { defineMessages, type NoMessageValues } from 'react-intl'

/** The top-bar search box (flow H1). Every page shows it, so these are in the core catalogue (`searchBox.*`). */
export const sbm = defineMessages<{
  label: NoMessageValues
  placeholder: NoMessageValues
  searchFor: { text: string }
  groupMemos: NoMessageValues
  groupRecent: NoMessageValues
  groupSaved: NoMessageValues
  allSaved: NoMessageValues
  options: { count: number }
}>({
  label: { id: 'searchBox.label', defaultMessage: 'Search memos' },
  placeholder: { id: 'searchBox.placeholder', defaultMessage: 'Search memos, notes and documents…' },
  searchFor: { id: 'searchBox.searchFor', defaultMessage: 'Search for “{text}”' },
  groupMemos: { id: 'searchBox.group.memos', defaultMessage: 'Memos' },
  groupRecent: { id: 'searchBox.group.recent', defaultMessage: 'Recent' },
  groupSaved: { id: 'searchBox.group.saved', defaultMessage: 'Saved searches' },
  allSaved: { id: 'searchBox.allSaved', defaultMessage: 'All saved searches' },
  options: {
    id: 'searchBox.options',
    defaultMessage: '{count, plural, =0 {No suggestions} one {# suggestion} other {# suggestions}}',
  },
})

/** The Search and Saved searches pages (flows H2 and H3); they load with `/search` (the `search` catalogue part). */
export const sm = defineMessages({
  title: { id: 'search.title', defaultMessage: 'Search' },
  saveThis: { id: 'search.save', defaultMessage: 'Save this search' },
  savedLink: { id: 'search.savedLink', defaultMessage: 'Saved searches' },
  queryLabel: { id: 'search.query', defaultMessage: 'Words to find' },
  submit: { id: 'search.submit', defaultMessage: 'Search' },
  prompt: {
    id: 'search.prompt',
    defaultMessage: 'Type words from a memo’s details, notes or documents, or a memo number.',
  },
  searching: { id: 'search.searching', defaultMessage: 'Searching…' },
  clearFilters: { id: 'search.clearFilters', defaultMessage: 'Clear filters' },
  activeFilters: { id: 'search.activeFilters', defaultMessage: 'Active filters' },
  sort: { id: 'search.sort', defaultMessage: 'Sort' },
  sortRelevance: { id: 'search.sort.relevance', defaultMessage: 'Relevance' },
  sortNewest: { id: 'search.sort.newest', defaultMessage: 'Newest' },
  filters: { id: 'search.filters', defaultMessage: 'Filters' },
  facetStatus: { id: 'search.facet.status', defaultMessage: 'Status' },
  facetType: { id: 'search.facet.type', defaultMessage: 'Type' },
  facetUnit: { id: 'search.facet.unit', defaultMessage: 'Unit' },
  date: { id: 'search.date', defaultMessage: 'Date' },
  from: { id: 'search.date.from', defaultMessage: 'From' },
  to: { id: 'search.date.to', defaultMessage: 'To' },
  amount: { id: 'search.amount', defaultMessage: 'Amount' },
  amountMin: { id: 'search.amount.min', defaultMessage: 'Minimum' },
  amountMax: { id: 'search.amount.max', defaultMessage: 'Maximum' },
  searchIn: { id: 'search.in', defaultMessage: 'Search in' },
  origin: { id: 'search.origin', defaultMessage: 'Origin' },
  originAll: { id: 'search.origin.all', defaultMessage: 'All memos' },
  originNative: { id: 'search.origin.native', defaultMessage: 'Created here' },
  originImported: { id: 'search.origin.imported', defaultMessage: 'Imported' },
  imported: { id: 'search.imported', defaultMessage: 'Imported' },
  sourceMetadata: { id: 'search.in.metadata', defaultMessage: 'Details' },
  sourceNotes: { id: 'search.in.notes', defaultMessage: 'Notes' },
  sourceDocuments: { id: 'search.in.documents', defaultMessage: 'Documents' },
  apply: { id: 'search.apply', defaultMessage: 'Apply filters' },
  results: { id: 'search.results', defaultMessage: 'Results' },
  foundDetails: { id: 'search.foundIn.details', defaultMessage: 'Found in: details' },
  foundNumber: { id: 'search.foundIn.number', defaultMessage: 'Found in: its number' },
  foundNoteAnyone: { id: 'search.foundIn.noteAnyone', defaultMessage: 'Found in: a note' },
  foundDocument: { id: 'search.foundIn.document', defaultMessage: 'Found in: the document' },
  foundAttachmentAny: { id: 'search.foundIn.attachmentAny', defaultMessage: 'Found in: an attachment' },
  loadMore: { id: 'search.loadMore', defaultMessage: 'Load more' },
  empty: {
    id: 'search.empty',
    defaultMessage: 'No memos match. Check the spelling, remove filters, or search all of the memo’s text.',
  },
  rateLimited: { id: 'search.rateLimited', defaultMessage: 'Too many searches in a minute. Try again shortly.' },
  timeout: { id: 'search.timeout', defaultMessage: 'That search took too long. Add words or filters.' },
  invalid: { id: 'search.invalid', defaultMessage: 'Check the search words and filters.' },
  qRequired: { id: 'search.error.q.required', defaultMessage: 'Type at least one word.' },
  qTooLong: { id: 'search.error.q.tooLong', defaultMessage: 'The search is too long. Use fewer words.' },
  qTooManyTerms: { id: 'search.error.q.tooManyTerms', defaultMessage: 'Too many words. Keep the most telling ones.' },
  toBeforeFrom: { id: 'search.error.to.beforeFrom', defaultMessage: 'The end date is before the start date.' },
  maxBelowMin: { id: 'search.error.amount.belowMin', defaultMessage: 'The maximum is below the minimum.' },
  inRequired: { id: 'search.error.in.required', defaultMessage: 'Choose at least one place to search.' },
  tooManyValues: { id: 'search.error.filters.tooMany', defaultMessage: 'Too many values in one filter. Choose fewer.' },
  badFilter: { id: 'search.error.filter', defaultMessage: 'Check this filter.' },

  saveTitle: { id: 'search.saveDialog.title', defaultMessage: 'Save this search' },
  name: { id: 'search.saveDialog.name', defaultMessage: 'Name' },
  privacy: {
    id: 'search.saveDialog.privacy',
    defaultMessage: 'Only you see your saved searches. Each time you open one, it shows what you may read then.',
  },
  save: { id: 'search.saveDialog.save', defaultMessage: 'Save' },
  cancel: { id: 'search.cancel', defaultMessage: 'Cancel' },
  saved: { id: 'search.saved', defaultMessage: 'Search saved.' },
  nameRequired: { id: 'search.error.name.required', defaultMessage: 'Enter a name.' },
  nameTooLong: { id: 'search.error.name.tooLong', defaultMessage: 'Use at most 100 characters.' },
  tooLarge: {
    id: 'search.error.criteria.tooLarge',
    defaultMessage: 'This search is too large to save. Remove some filters.',
  },
  limit: {
    id: 'search.error.limit',
    defaultMessage: 'You have as many saved searches as you can keep. Delete one on the Saved searches page first.',
  },
  changed: {
    id: 'search.error.changed',
    defaultMessage: 'It was changed in another window. Reload the page and try again.',
  },
  failed: { id: 'search.error.failed', defaultMessage: 'That did not work. Try again.' },

  savedTitle: { id: 'search.savedPage.title', defaultMessage: 'Saved searches' },
  savedLead: {
    id: 'search.savedPage.lead',
    defaultMessage: 'Only you see these. Each one shows what you may read when you open it.',
  },
  savedEmpty: {
    id: 'search.savedPage.empty',
    defaultMessage: 'No saved searches yet. Run a search and choose Save this search.',
  },
  rename: { id: 'search.savedPage.rename', defaultMessage: 'Rename' },
  renameTitle: { id: 'search.savedPage.renameTitle', defaultMessage: 'Rename saved search' },
  delete: { id: 'search.savedPage.delete', defaultMessage: 'Delete' },
  deleteLead: {
    id: 'search.savedPage.deleteLead',
    defaultMessage: 'Only the saved search goes; no memo is affected.',
  },
  keep: { id: 'search.savedPage.keep', defaultMessage: 'Keep' },
  renamed: { id: 'search.savedPage.renamed', defaultMessage: 'Saved search renamed.' },
  deleted: { id: 'search.savedPage.deleted', defaultMessage: 'Saved search deleted.' },
})

/** Texts with values (react-intl checks them against the message). */
export const smv = defineMessages<{
  count: { count: number }
  countCapped: { count: number }
  cappedHint: { count: number }
  filtersButton: { count: number }
  chip: { name: string; value: string }
  removeFilter: { filter: string }
  facetOption: { label: string; count: number }
  updated: { date: string }
  foundNote: { name: string }
  foundAttachment: { name: string }
  words: { q: string }
  filterCount: { count: number }
  renameFor: { name: string }
  deleteFor: { name: string }
  deleteTitle: { name: string }
}>({
  count: { id: 'search.count', defaultMessage: '{count, plural, one {# memo} other {# memos}}' },
  countCapped: { id: 'search.countCapped', defaultMessage: '{count, number}+ memos' },
  cappedHint: {
    id: 'search.cappedHint',
    defaultMessage: 'Showing the best matches among the first {count, number}. Add words or filters to narrow it.',
  },
  filtersButton: { id: 'search.filtersButton', defaultMessage: 'Filters ({count})' },
  chip: { id: 'search.chip', defaultMessage: '{name}: {value}' },
  removeFilter: { id: 'search.removeFilter', defaultMessage: 'Remove filter {filter}' },
  facetOption: { id: 'search.facetOption', defaultMessage: '{label} ({count, number})' },
  updated: { id: 'search.updated', defaultMessage: 'updated {date}' },
  foundNote: { id: 'search.foundIn.note', defaultMessage: 'Found in: note by {name}' },
  foundAttachment: { id: 'search.foundIn.attachment', defaultMessage: 'Found in: attachment “{name}”' },
  words: { id: 'search.savedPage.words', defaultMessage: '“{q}”' },
  filterCount: {
    id: 'search.savedPage.filterCount',
    defaultMessage: '{count, plural, one {# filter} other {# filters}}',
  },
  renameFor: { id: 'search.savedPage.renameFor', defaultMessage: 'Rename {name}' },
  deleteFor: { id: 'search.savedPage.deleteFor', defaultMessage: 'Delete {name}' },
  deleteTitle: { id: 'search.savedPage.deleteTitle', defaultMessage: 'Delete “{name}”?' },
})
