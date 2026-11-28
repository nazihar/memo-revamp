package com.memo.forum.internal

import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.memo.MemoReaders
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * Committee officers read a memo while it waits in their queue (ADR-0059): the chair and secretaries in office of every
 * forum with an open submission of the memo, on the tenant's date, with position seats resolved to their holders. Kept
 * in step after each submission change and each change of a forum's members, and by [OfficerReaders] when the calendar
 * or the organisation changes who those officers are. The memo module's read rule still checks their clearance on
 * every read.
 */
@Component
class SubmissionReaders(
    private val store: SubmissionStore,
    private val directory: ForumDirectory,
    private val readers: MemoReaders,
    private val org: OrgDirectory,
) {
    /** Brings the committee readers of [subjectId] in line with its open submissions on [on]; other subjects have none. */
    fun sync(
        subjectType: String,
        subjectId: UUID,
        on: LocalDate = org.today(),
    ) = syncWith(subjectType, subjectId) { officersOn(it, on) }

    /**
     * After [forumId]'s officers changed (its members, the calendar or a position's holder): every memo in its open
     * queue, against the officers on [on]. Each forum's officers are read once. Returns how many subjects were looked at.
     */
    fun resync(
        forumId: UUID,
        on: LocalDate = org.today(),
    ): Int {
        val officers = mutableMapOf<UUID, Set<UUID>>()
        val subjects =
            store
                .queue(forumId, ForumSubmissions.OPEN, Int.MAX_VALUE)
                .map { it.subjectType to it.subjectId }
                .distinct()
        subjects.forEach { (type, id) -> syncWith(type, id) { forum -> officers.getOrPut(forum) { officersOn(forum, on) } } }
        return subjects.size
    }

    /** The people who read [forumId]'s queue on [date]: its chair and secretaries in office, seats resolved to holders. */
    fun officersOn(
        forumId: UUID,
        date: LocalDate,
    ): Set<UUID> =
        directory
            .membersOn(forumId, date)
            .filter { it.role in OFFICERS }
            .map { it.userId }
            .toSet()

    private fun syncWith(
        subjectType: String,
        subjectId: UUID,
        officers: (UUID) -> Set<UUID>,
    ) {
        if (subjectType != MemoReaders.SUBJECT_TYPE) return
        val forums =
            store
                .ofSubject(subjectType, subjectId)
                .filter { it.status.open }
                .map { it.forumId }
                .toSet()
        readers.syncForum(subjectId, forums.flatMap(officers).toSet())
    }

    companion object {
        /** The roles that read the memos of the queue (ADR-0059). */
        val OFFICERS = setOf(ForumRole.CHAIR, ForumRole.SECRETARY)
    }
}
