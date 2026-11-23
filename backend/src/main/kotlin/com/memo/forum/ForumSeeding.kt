package com.memo.forum

import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMemberStore
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumStore
import com.memo.forum.internal.ForumTypeLifecycle
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypeStore
import com.memo.forum.internal.ForumTypeVersionStore
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberEdit
import com.memo.forum.internal.MemberInput
import com.memo.forum.internal.MemberKind
import com.memo.forum.internal.SubmissionStore
import com.memo.memo.MemoReaders
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/** A preset a seeder can start a forum type from (ADR-0056). */
enum class SeedPreset(
    internal val preset: ForumTypePreset,
) {
    BOARD(ForumTypePreset.BOARD),
    BOARD_COMMITTEE(ForumTypePreset.BOARD_COMMITTEE),
    BOARD_AUDIT_COMMITTEE(ForumTypePreset.BOARD_AUDIT_COMMITTEE),
    MANAGEMENT_COMMITTEE(ForumTypePreset.MANAGEMENT_COMMITTEE),
}

/**
 * What a seeder changes in a committee type's published settings (Phase 4 E06 T12): how its minutes are approved and
 * whether a missing quorum may be overridden with a reason. Null leaves a setting as it is.
 */
data class SeedTypeChange(
    val minutesApproval: MinutesApproval? = null,
    val quorumOverride: QuorumOverride? = null,
)

/** A member a seeder adds: a person or a position, with its role and attributes. */
data class SeedMember(
    val userId: UUID? = null,
    val positionId: UUID? = null,
    val role: ForumRole = ForumRole.MEMBER,
    val attributes: Set<String> = emptySet(),
    val startsOn: LocalDate,
)

/**
 * Forum types, forums and members for seeders (the demo tenant, Phase 4 E01 T6), through the same services as the API,
 * as the user bound to the current unit of work. Never called from requests. Idempotent: what exists is left as it is
 * ("seeders only fill gaps"). Inside a tenant transaction.
 */
@Service
class ForumSeeding(
    private val types: ForumTypes,
    private val typeStore: ForumTypeStore,
    private val forums: Forums,
    private val store: ForumStore,
    private val members: ForumMembers,
    private val memberStore: ForumMemberStore,
    private val submissions: SubmissionStore,
    private val lifecycle: ForumTypeLifecycle,
    private val versions: ForumTypeVersionStore,
) {
    /** The type with [code], created and published from [preset] (named [name]) if missing. */
    fun ensureType(
        code: String,
        name: String,
        preset: SeedPreset,
    ): UUID = typeStore.all().firstOrNull { it.code == code }?.id ?: types.create(code, name, preset.preset, publish = true).type.id

    /**
     * Drafts and submits, as the bound administrator, a new version of the type with [code] carrying [change] (Phase 4 E06
     * T12), unless its published version already has it or the type has a version open; the version's number, for a second
     * administrator to [approveVersion] (maker-checker, as the API). Meetings created afterwards are pinned to it.
     */
    fun proposeTypeChange(
        code: String,
        change: SeedTypeChange,
    ): Int? {
        val type = typeStore.all().firstOrNull { it.code == code } ?: return null
        val published = versions.published(type.id) ?: return null
        val settings = published.settings
        val wanted =
            settings.copy(
                minutes = change.minutesApproval?.let { settings.minutes.copy(approval = it) } ?: settings.minutes,
                meetings = change.quorumOverride?.let { settings.meetings.copy(quorumOverride = it) } ?: settings.meetings,
            )
        if (wanted == settings || versions.versions(type.id).any { it.state.open }) return null
        val draft = lifecycle.newDraft(type.id)
        val saved = lifecycle.replace(type.id, draft.number, draft.revision, wanted)
        lifecycle.submit(type.id, draft.number, saved.revision)
        return draft.number
    }

    /** Publishes version [number] of the type with [code], as the bound administrator (who did not submit it). */
    fun approveVersion(
        code: String,
        number: Int,
    ) {
        val type = checkNotNull(typeStore.all().firstOrNull { it.code == code }) { "forum type $code missing" }
        lifecycle.approve(type.id, number, "Demo committee settings (Phase 4 E06).")
    }

    /** The forum with [key], created if missing. */
    fun ensureForum(
        key: String,
        code: String,
        name: String,
        shortName: String,
        typeId: UUID,
        parentKey: String? = null,
    ): UUID =
        store.findByKey(key)?.id
            ?: forums.create(ForumInput(key, code, name, shortName, typeId, parentKey?.let { store.findByKey(it)?.id })).id

    /**
     * The open submission of memo [memoId] to the forum with [key] that is on no agenda yet, if its route has escalated it
     * there (Phase 4 E03 T9).
     */
    fun unplacedSubmission(
        key: String,
        memoId: UUID,
    ): UUID? {
        val forum = store.findByKey(key) ?: return null
        return submissions
            .ofSubject(MemoReaders.SUBJECT_TYPE, memoId)
            .firstOrNull { it.forumId == forum.id && it.status.open && it.meetingId == null }
            ?.id
    }

    /**
     * Gives [userId]'s open membership of the forum with [key] the alternate [alternateUserId] (Phase 4 E05 T10), unless it
     * has one already or the person holds no open membership there.
     */
    fun ensureAlternate(
        key: String,
        userId: UUID,
        alternateUserId: UUID,
    ) {
        val forum = checkNotNull(store.findByKey(key)) { "forum $key missing" }
        val row =
            memberStore
                .members(forum.id)
                .lastOrNull { it.kind == MemberKind.PERSON && it.userId == userId && it.endedOn == null }
                ?.takeIf { it.alternateUserId == null }
                ?: return
        members.edit(forum.id, row.id, row.version, MemberEdit(row.voting, row.attributes, alternateUserId, row.termEndsOn, row.authority))
    }

    /** Adds [member] to the forum with [key] unless that person or position already has a row there. */
    fun ensureMember(
        key: String,
        member: SeedMember,
    ) {
        val forum = checkNotNull(store.findByKey(key)) { "forum $key missing" }
        val kind = if (member.positionId != null) MemberKind.POSITION else MemberKind.PERSON
        val exists =
            memberStore.members(forum.id).any {
                val samePerson = member.userId != null && it.userId == member.userId
                val sameSeat = member.positionId != null && it.positionId == member.positionId
                it.kind == kind && (samePerson || sameSeat)
            }
        if (exists) return
        members.add(
            forum.id,
            MemberInput(
                userId = member.userId,
                positionId = member.positionId,
                role = member.role,
                attributes = member.attributes,
                startsOn = member.startsOn,
            ),
        )
    }
}
