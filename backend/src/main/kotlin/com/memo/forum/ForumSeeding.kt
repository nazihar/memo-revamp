package com.memo.forum

import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMemberStore
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumStore
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypeStore
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.forum.internal.MemberKind
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/** A preset a seeder can start a forum type from (ADR-0056). */
enum class SeedPreset(
    internal val preset: ForumTypePreset,
) {
    BOARD(ForumTypePreset.BOARD),
    BOARD_COMMITTEE(ForumTypePreset.BOARD_COMMITTEE),
    MANAGEMENT_COMMITTEE(ForumTypePreset.MANAGEMENT_COMMITTEE),
}

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
) {
    /** The type with [code], created and published from [preset] (named [name]) if missing. */
    fun ensureType(
        code: String,
        name: String,
        preset: SeedPreset,
    ): UUID = typeStore.all().firstOrNull { it.code == code }?.id ?: types.create(code, name, preset.preset, publish = true).type.id

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
