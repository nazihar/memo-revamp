package com.memo.forum.internal

import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.memo.MemoReaders
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Committee officers read a memo while it waits in their queue (ADR-0059): the chair and secretaries in office of every
 * forum with an open submission of the memo, kept in step after each submission change and each change of a forum's
 * members. The memo module's read rule still checks their clearance on every read.
 */
@Component
class SubmissionReaders(
    private val store: SubmissionStore,
    private val directory: ForumDirectory,
    private val readers: MemoReaders,
) {
    /** Brings the committee readers of [subjectId] in line with its open submissions; other subjects have none. */
    fun sync(
        subjectType: String,
        subjectId: UUID,
    ) {
        if (subjectType != MemoReaders.SUBJECT_TYPE) return
        val forums =
            store
                .ofSubject(subjectType, subjectId)
                .filter { it.status.open }
                .map { it.forumId }
                .toSet()
        readers.syncForum(subjectId, forums.flatMap(::officers).toSet())
    }

    /** After [forumId]'s members changed: every memo in its open queue. */
    fun resync(forumId: UUID) {
        store
            .queue(forumId, ForumSubmissions.OPEN, Int.MAX_VALUE)
            .map { it.subjectType to it.subjectId }
            .distinct()
            .forEach { (type, id) -> sync(type, id) }
    }

    private fun officers(forumId: UUID): List<UUID> =
        directory.membersOn(forumId).filter { it.role == ForumRole.CHAIR || it.role == ForumRole.SECRETARY }.map { it.userId }
}
