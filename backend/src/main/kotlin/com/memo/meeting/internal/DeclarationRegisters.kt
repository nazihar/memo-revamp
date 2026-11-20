package com.memo.meeting.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.poi.PoiLimits
import com.memo.common.poi.SheetCells
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.Cursors
import com.memo.common.web.FieldProblem
import com.memo.forum.ForumDirectory
import com.memo.jooq.tables.references.MEETING
import com.memo.jooq.tables.references.MEETING_DECLARATION
import com.memo.jooq.tables.references.MEETING_ITEM
import com.memo.jooq.tables.references.MEETING_SECTION
import com.memo.meeting.DeclarationHow
import com.memo.meeting.DeclarationIntent
import com.memo.meeting.DeclarationSource
import com.memo.meeting.DeclarationState
import com.memo.meeting.MeetingStatus
import com.memo.org.OrgDirectory
import io.swagger.v3.oas.annotations.media.Schema
import org.apache.poi.poifs.crypt.temp.SXSSFWorkbookWithCustomZipEntrySource
import org.jooq.Condition
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The cross-forum register's filter (design E04 §3): each part optional; [from] and [to] are days in the tenant's zone. */
data class RegisterFilter(
    val forumId: UUID? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val userId: UUID? = null,
    val state: DeclarationState? = null,
)

/** How far the actor reads the cross-forum register (design E04 §4). */
data class RegisterScope(
    val actor: UUID,
    /** `forum.read` or `forums.manage`: every committee. */
    val everyForum: Boolean,
    /** `forums.manage`: an officer of every committee. */
    val managesAll: Boolean,
    /** The committees the actor is an officer of today (secretary, chair, the vice-chair when there is no chair). */
    val officerOf: Set<UUID>,
    /** The classification levels the actor is cleared for. */
    val cleared: Set<UUID>,
) {
    fun officer(forumId: UUID): Boolean = managesAll || forumId in officerOf
}

/** A declaration with its meeting and its item's parent, as the register reads it. */
data class RegisterRecord(
    val row: DeclarationRow,
    val meeting: MeetingRow,
    val parentId: UUID?,
)

/** What the register reads of an item: its row in the latest revision that carries it, else (officers) the draft's. */
data class RegisterItem(
    val number: String?,
    val title: String,
    val titleHidden: Boolean,
    val classificationId: UUID,
    val restricted: Boolean,
    val membersOnly: Boolean,
    val published: Boolean,
)

/**
 * The cross-forum register's reads (design E04 §3, §8), inside a tenant transaction: newest first, keyset on
 * `(declared_at, id)` through the register indexes (by forum, by person, or across forums).
 */
@Repository
class DeclarationRegisterStore(
    private val tenantDsl: TenantDsl,
    private val declarations: DeclarationStore,
    private val revisions: RevisionStore,
) {
    /**
     * The declarations [scope] may list under [filter] (dates as [from]–[to] instants), after [after], at most [limit]:
     *  - of the committees in scope, in meetings at a level the actor is cleared for;
     *  - outside the committees they are an officer of, only past DRAFT and on items published at least once;
     *  - on an item (or its parent) the actor is recused from, only their own.
     */
    fun page(
        scope: RegisterScope,
        filter: RegisterFilter,
        from: Instant?,
        to: Instant?,
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<RegisterRecord> {
        val d = MEETING_DECLARATION
        val m = MEETING
        val i = MEETING_ITEM
        val own = MEETING_DECLARATION.`as`("own")
        val published = RevisionStore.Conditions.everPublished(d.TENANT_ID, d.ITEM_ID)
        val recused =
            DSL.exists(
                DSL
                    .selectOne()
                    .from(own)
                    .where(
                        own.TENANT_ID.eq(d.TENANT_ID),
                        own.USER_ID.eq(scope.actor),
                        own.ITEM_ID.`in`(d.ITEM_ID, i.PARENT_ID),
                        own.STATE.`in`(RecusalStore.EFFECTIVE),
                    ),
            )
        val conditions =
            listOfNotNull<Condition>(
                if (scope.everyForum) null else d.FORUM_ID.`in`(scope.officerOf),
                filter.forumId?.let { d.FORUM_ID.eq(it) },
                filter.userId?.let { d.USER_ID.eq(it) },
                filter.state?.let { d.STATE.eq(it.name) },
                from?.let { d.DECLARED_AT.ge(MeetingStore.utc(it)) },
                to?.let { d.DECLARED_AT.lt(MeetingStore.utc(it)) },
                if (scope.cleared.isEmpty()) DSL.falseCondition() else m.CLASSIFICATION_ID.`in`(scope.cleared),
                if (scope.managesAll) null else d.FORUM_ID.`in`(scope.officerOf).or(m.STATUS.ne(MeetingStatus.DRAFT.name).and(published)),
                d.USER_ID.eq(scope.actor).or(DSL.not(recused)),
                after?.let { (at, id) -> DSL.row(d.DECLARED_AT, d.ID).lt(DSL.row(DSL.value(MeetingStore.utc(at)), DSL.value(id))) },
            )
        return tenantDsl
            .current()
            .select(d.asterisk(), m.asterisk(), i.PARENT_ID)
            .from(d)
            .join(m)
            .on(m.TENANT_ID.eq(d.TENANT_ID), m.ID.eq(d.MEETING_ID))
            .join(i)
            .on(i.TENANT_ID.eq(d.TENANT_ID), i.ID.eq(d.ITEM_ID))
            .where(conditions)
            .orderBy(d.DECLARED_AT.desc(), d.ID.desc())
            .limit(limit)
            .fetch { RegisterRecord(declarations.row(it.into(d)), it.into(m).row(), it.get(i.PARENT_ID)) }
    }

    /** Each of [itemIds] as the latest revision carrying it shows it, else, for [draftOf], as the draft has it. */
    fun items(
        itemIds: Collection<UUID>,
        draftOf: Collection<UUID>,
    ): Map<UUID, RegisterItem> {
        val published = revisions.latestPublished(itemIds)
        val drafts = draftOf.toSet() - published.keys
        return published + if (drafts.isEmpty()) emptyMap() else drafts(drafts)
    }

    /** [itemIds] as the draft has them, unnumbered. */
    private fun drafts(itemIds: Collection<UUID>): Map<UUID, RegisterItem> {
        val i = MEETING_ITEM
        val s = MEETING_SECTION
        return tenantDsl
            .current()
            .select(i.ID, i.TITLE, i.TITLE_HIDDEN, i.CLASSIFICATION_ID, i.RESTRICTED_TO, s.MEMBERS_ONLY)
            .from(i)
            .join(s)
            .on(s.TENANT_ID.eq(i.TENANT_ID), s.ID.eq(i.SECTION_ID))
            .where(i.ID.`in`(itemIds))
            .fetch()
            .associate {
                checkNotNull(it.value1()) to
                    RegisterItem(
                        number = null,
                        title = checkNotNull(it.value2()),
                        titleHidden = it.value3() == true,
                        classificationId = checkNotNull(it.value4()),
                        restricted = it.value5() != null,
                        membersOnly = it.value6() == true,
                        published = false,
                    )
            }
    }

    /** The items among [itemIds] that [userId] holds an effective declaration on. */
    fun recusedAmong(
        userId: UUID,
        itemIds: Collection<UUID>,
    ): Set<UUID> {
        if (itemIds.isEmpty()) return emptySet()
        val d = MEETING_DECLARATION
        return tenantDsl
            .current()
            .selectDistinct(d.ITEM_ID)
            .from(d)
            .where(d.USER_ID.eq(userId), d.ITEM_ID.`in`(itemIds), d.STATE.`in`(RecusalStore.EFFECTIVE))
            .fetchSet(d.ITEM_ID)
            .filterNotNull()
            .toSet()
    }
}

/** A row of the register of declarations (design E04 flow U1). */
data class DeclarationRegisterRowResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    /** The committee's short name. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val committee: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    /** "BRC 3/2026"; null while the meeting is not numbered. */
    val meetingLabel: String?,
    /** The day the meeting starts, in its zone. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingDate: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingStatus: MeetingStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    /** As last published; null when the item was never published. */
    val itemNumber: String?,
    /** Null when the viewer may not read the title ("Restricted item"). */
    val itemTitle: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val user: PersonResponse,
    /** The interest in the declarant's words; null when the viewer may not read it ("(restricted item)"). */
    val nature: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val intent: DeclarationIntent,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: DeclarationState,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val source: DeclarationSource,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val how: DeclarationHow,
    /** For one recorded by someone else: how it was given; null with the nature. */
    val howNote: String?,
    val recordedBy: PersonResponse?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val declaredAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val atMeeting: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val correction: Boolean,
    /** The chair's latest ruling; its note only with the nature. */
    val ruling: RulingResponse?,
)

/** A page of the register of declarations, newest first. */
data class DeclarationRegisterResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<DeclarationRegisterRowResponse>,
    /** Pass it as `cursor` for the next page; null on the last. */
    val nextCursor: String?,
)

/**
 * The register of declarations across committees (design E04 §3, §4, flow U): for `forum.read` and `forums.manage`
 * holders every committee, for secretaries and chairs their own; everyone else is 403. Newest first, 50 a page. A title
 * shows by E03's title rule (an officer of the committee: cleared for the item, and not hidden from them by their own
 * recusal; anyone else: published, cleared, not hidden, not restricted and not members-only); the nature, the note on how
 * it was given and the ruling's note only with the title and when the viewer is not recused from the item, or on their
 * own declaration. The export holds the same rows, at most 20,000, and is audited with its filter and count.
 */
@Service
class DeclarationRegisters(
    private val access: MeetingAccess,
    private val directory: ForumDirectory,
    private val store: DeclarationRegisterStore,
    private val views: DeclarationViews,
    private val org: OrgDirectory,
    private val audit: AuditLog,
) {
    /** Who the actor is to the register: 403 without `forum.read`, `forums.manage` or an officer's seat. */
    fun scope(): RegisterScope {
        val actor = actorOrNull() ?: throw ForbiddenException(Permission.FORUM_READ)
        val officerOf =
            directory
                .rolesOf(actor)
                .keys
                .filter { access.officer(it) }
                .toSet()
        val every = access.readsAll()
        if (!every && officerOf.isEmpty()) throw ForbiddenException(Permission.FORUM_READ)
        return RegisterScope(actor, every, access.managesAll(), officerOf, access.clearedLevels())
    }

    fun page(
        filter: RegisterFilter,
        cursor: String?,
    ): DeclarationRegisterResponse {
        val scope = scoped(filter)
        val after =
            cursor?.let {
                runCatching { Cursors.decode(it) }.getOrElse {
                    throw MeetingRejectedException(listOf(FieldProblem("cursor", "INVALID", "Not a cursor of this list.")))
                }
            }
        val records = read(scope, filter, after, PAGE + 1)
        val shown = records.take(PAGE)
        val next = shown.lastOrNull()?.takeIf { records.size > PAGE }?.let { Cursors.encode(it.row.declaredAt, it.row.id) }
        return DeclarationRegisterResponse(rows(scope, shown), next)
    }

    /** The rows of the export: the register under [filter], at most [MAX_EXPORT]; audited with the filter and the count. */
    fun export(filter: RegisterFilter): List<DeclarationRegisterRowResponse> {
        val scope = scoped(filter)
        val rows = mutableListOf<DeclarationRegisterRowResponse>()
        var after: Pair<Instant, UUID>? = null
        do {
            val page = read(scope, filter, after, minOf(EXPORT_PAGE, MAX_EXPORT - rows.size))
            rows += rows(scope, page)
            after = page.lastOrNull()?.let { it.row.declaredAt to it.row.id }
        } while (page.size == EXPORT_PAGE && rows.size < MAX_EXPORT)
        audit.record(
            "meeting.declarations.exported",
            OBJECT_TYPE,
            filter.forumId?.toString(),
            after =
                mapOf(
                    "forumId" to filter.forumId?.toString(),
                    "from" to filter.from?.toString(),
                    "to" to filter.to?.toString(),
                    "userId" to filter.userId?.toString(),
                    "state" to filter.state?.name,
                    "count" to rows.size,
                ),
        )
        return rows
    }

    /** The scope, with the filter's committee checked: 404 when the actor does not see it in the register. */
    private fun scoped(filter: RegisterFilter): RegisterScope {
        val scope = scope()
        filter.forumId?.let { forum ->
            val visible = if (scope.everyForum) directory.forum(forum) != null else forum in scope.officerOf
            if (!visible) throw MeetingNotFoundException("forum")
        }
        return scope
    }

    private fun read(
        scope: RegisterScope,
        filter: RegisterFilter,
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<RegisterRecord> {
        val zone = org.zone()
        val from = filter.from?.atStartOfDay(zone)?.toInstant()
        val to =
            filter.to
                ?.plusDays(1)
                ?.atStartOfDay(zone)
                ?.toInstant()
        return store.page(scope, filter, from, to, after, limit)
    }

    private fun rows(
        scope: RegisterScope,
        records: List<RegisterRecord>,
    ): List<DeclarationRegisterRowResponse> {
        if (records.isEmpty()) return emptyList()
        val itemIds = records.map { it.row.itemId }.toSet()
        val items = store.items(itemIds, records.filter { scope.officer(it.row.forumId) }.map { it.row.itemId })
        val recused = store.recusedAmong(scope.actor, itemIds + records.mapNotNull { it.parentId })
        val forums = directory.forums(records.map { it.row.forumId }.toSet())
        val names = views.names(records.map { it.row })
        val person = { id: UUID -> PersonResponse(id, names[id].orEmpty()) }
        return records.map { record ->
            val row = record.row
            val item = items[row.itemId]
            val recusedHere = row.itemId in recused || record.parentId in recused
            val titled = item != null && titled(scope, row.forumId, item, recusedHere)
            val natured = row.userId == scope.actor || (titled && !recusedHere)
            DeclarationRegisterRowResponse(
                id = row.id,
                forumId = row.forumId,
                committee = forums[row.forumId]?.shortName.orEmpty(),
                meetingId = row.meetingId,
                meetingLabel = record.meeting.label,
                meetingDate = record.meeting.date,
                meetingStatus = record.meeting.status,
                itemId = row.itemId,
                itemNumber = item?.number,
                itemTitle = item?.title?.takeIf { titled },
                user = person(row.userId),
                nature = row.nature.takeIf { natured },
                intent = row.intent,
                state = row.state,
                source = row.source,
                how = row.how,
                howNote = row.howNote?.takeIf { natured },
                recordedBy = row.recordedBy?.let(person),
                declaredAt = row.declaredAt,
                atMeeting = row.atMeeting,
                correction = row.correction,
                ruling =
                    row.ruling?.let {
                        RulingResponse(
                            it.outcome,
                            it.by?.let(person),
                            it.onBehalfBy?.let(person),
                            it.how,
                            it.note?.takeIf { natured },
                            it.at,
                        )
                    },
            )
        }
    }

    /** Whether the viewer reads [item]'s title in the register (E03 `seeTitle`, design E04 §3). */
    private fun titled(
        scope: RegisterScope,
        forumId: UUID,
        item: RegisterItem,
        recused: Boolean,
    ): Boolean {
        val cleared = item.classificationId in scope.cleared
        return if (scope.officer(forumId)) {
            cleared && (!item.titleHidden || !recused)
        } else {
            item.published && cleared && !item.titleHidden && !item.restricted && !item.membersOnly
        }
    }

    companion object {
        const val PAGE = 50
        const val MAX_EXPORT = 20_000
        const val EXPORT_PAGE = 1_000
        const val OBJECT_TYPE = "meeting_declaration_register"
    }
}

/**
 * The register's export (design E04 §3, flow U1): one sheet of the rows on screen under the filters, every value a string
 * cell with the formula-injection prefix ([SheetCells]), streamed (POI's SXSSF, its temporary data encrypted with a key
 * held in memory). A title the viewer may not read is "Restricted item" and its interest "(restricted item)".
 */
@Component
class DeclarationWorkbook {
    fun write(
        rows: List<DeclarationRegisterRowResponse>,
        out: OutputStream,
    ) {
        PoiLimits.apply()
        val workbook = SXSSFWorkbookWithCustomZipEntrySource()
        try {
            val sheet = workbook.createSheet("Declarations")
            SheetCells.row(sheet, 0, HEADERS)
            rows.forEachIndexed { index, row -> SheetCells.row(sheet, index + 1, values(row)) }
            workbook.write(out)
        } finally {
            workbook.close()
        }
    }

    private fun values(row: DeclarationRegisterRowResponse): List<Any?> =
        listOf(
            row.committee,
            row.meetingLabel,
            row.meetingDate,
            row.itemNumber,
            row.itemTitle ?: RESTRICTED_TITLE,
            row.user.name,
            row.nature ?: RESTRICTED_NATURE,
            row.intent,
            row.state,
            row.source,
            row.how,
            row.howNote,
            row.recordedBy?.name,
            row.declaredAt,
            if (row.atMeeting) "Yes" else "No",
            if (row.correction) "Yes" else "No",
            row.ruling?.outcome,
            row.ruling?.by?.name,
            row.ruling?.onBehalfBy?.name,
            row.ruling?.at,
            row.ruling?.note,
        )

    companion object {
        const val MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val RESTRICTED_TITLE = "Restricted item"
        const val RESTRICTED_NATURE = "(restricted item)"
        val HEADERS =
            listOf(
                "Committee",
                "Meeting",
                "Meeting date",
                "Item",
                "Title",
                "Member",
                "Interest",
                "Intent",
                "State",
                "Source",
                "How",
                "Note on how",
                "Recorded by",
                "Declared at (UTC)",
                "At the meeting",
                "Correction",
                "Ruling",
                "Ruled by",
                "Recorded for the chair by",
                "Ruled at (UTC)",
                "Ruling note",
            )
    }
}
