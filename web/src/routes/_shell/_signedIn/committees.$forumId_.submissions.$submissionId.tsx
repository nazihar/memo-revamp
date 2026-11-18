import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { SubmissionPage } from '@/features/forums/SubmissionPage'

const crumbs = defineMessages({
  page: { id: 'nav.forumPaper', defaultMessage: 'Paper' },
})

/**
 * One paper sent to a committee (Phase 4 E02 T4), where `FORUM_SUBMISSION_*` notifications link. The API decides who
 * may see it: its author and presenter, readers of its memo, and the committee's officers.
 */
export const Route = createFileRoute('/_shell/_signedIn/committees/$forumId_/submissions/$submissionId')({
  component: SubmissionRoute,
  staticData: { crumb: crumbs.page },
})

function SubmissionRoute() {
  const { forumId, submissionId } = Route.useParams()
  return <SubmissionPage key={submissionId} forumId={forumId} submissionId={submissionId} />
}
