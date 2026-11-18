package com.memo.meeting

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.ForumRole
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.meeting.internal.AgendaChair
import com.memo.meeting.internal.AgendaDraftViews
import com.memo.meeting.internal.AgendaItems
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.AgendaPublisher
import com.memo.meeting.internal.AgendaStart
import com.memo.meeting.internal.DeclarationReviews
import com.memo.meeting.internal.Declarations
import com.memo.meeting.internal.DeclareInput
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingGuests
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingNotFoundException
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.MeetingRejectedException
import com.memo.meeting.internal.TimesInput
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.AppRoleJdbc.update
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Tenant isolation of meetings (test plan E03, T1; AGENTS.md §3.2): another tenant neither reads nor writes a tenant's
 * meetings, counters, guests or audience, not through its services and not through SQL; references between the tables
 * stay within one tenant (composite foreign keys); and the app role deletes nothing.
 */
class MeetingCrossTenantIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var lifecycle: MeetingLifecycle

    @Autowired lateinit var guests: MeetingGuests

    @Autowired lateinit var reads: MeetingReads

    @Autowired lateinit var starts: AgendaStart

    @Autowired lateinit var items: AgendaItems

    @Autowired lateinit var views: AgendaDraftViews

    @Autowired lateinit var chairing: AgendaChair

    @Autowired lateinit var publisher: AgendaPublisher

    @Autowired lateinit var declarations: Declarations

    @Autowired lateinit var reviews: DeclarationReviews

    private class Side(
        val tenant: TestTenant,
        val admin: UserAccount,
        val member: UserAccount,
        val forum: UUID,
    )

    private fun side(label: String): Side {
        val tenant = registry.aTestTenant(transactions, settings, label, mfaRequired = false)
        val admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val member = accounts.anActiveUser(transactions, tenant, aPassword())
        val forum =
            asUser(tenant, admin) {
                val type = types.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE, publish = true).type.id
                forums.create(ForumInput("board-risk", "BRC", "Board Risk Committee", "Risk", type)).id
            }
        asUser(tenant, admin) { members.add(forum, MemberInput(userId = member.id.value, role = ForumRole.MEMBER)) }
        return Side(tenant, admin, member, forum)
    }

    private fun meeting(side: Side): UUID {
        val day = LocalDate.now(ZoneOffset.UTC).plusDays(20).atTime(LocalTime.of(10, 0))
        return asUser(side.tenant, side.admin) {
            val created =
                lifecycle.create(
                    side.forum,
                    MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), null),
                    TimesInput(day, day.plusHours(2), "Asia/Dhaka"),
                )
            lifecycle.schedule(created.id, created.version).id.also { guests.add(it, side.member.id.value, null) }
        }
    }

    @Test
    fun `another tenant neither sees nor writes meetings, guests or audiences`() {
        val a = side("meetinga")
        val b = side("meetingb")
        val ofA = meeting(a)
        val ofB = meeting(b)

        // Through the services: tenant B's administrator gets 404, and B's member has only B's meeting.
        assertThatThrownBy { asUser(b.tenant, b.admin) { reads.get(ofA) } }.isInstanceOf(MeetingNotFoundException::class.java)
        assertThatThrownBy { asUser(b.tenant, b.admin) { lifecycle.start(ofA, 1) } }.isInstanceOf(MeetingNotFoundException::class.java)
        assertThatThrownBy { asUser(b.tenant, b.admin) { guests.add(ofA, b.member.id.value, null) } }
            .isInstanceOf(MeetingNotFoundException::class.java)
        assertThatThrownBy { asUser(b.tenant, b.admin) { reads.ofForum(a.forum, null, null, emptySet(), null) } }
            .isInstanceOf(MeetingNotFoundException::class.java)
        assertThat(asUser(b.tenant, b.member) { reads.mine(null) }.map { it.id }).containsExactly(ofB)

        // Through SQL as the app role: no rows of A are visible to B, and none can be written for A.
        AppRoleJdbc.inTransaction(b.tenant.id) { c ->
            listOf("meeting", "meeting_counter", "meeting_guest", "meeting_audience").forEach { table ->
                assertThat(c.long("select count(*) from $table where tenant_id = ?", a.tenant.id.value)).describedAs(table).isZero()
            }
        }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val writes =
            listOf(
                "insert into meeting_guest (id, tenant_id, meeting_id, user_id, added_at) values (?, ?, ?, ?, ?)" to
                    listOf<Any>(UUID.randomUUID(), a.tenant.id.value, ofA, b.member.id.value, now),
                "insert into meeting_counter (tenant_id, forum_id, year, last) values (?, ?, 2026, 1)" to
                    listOf<Any>(
                        a.tenant.id.value,
                        a.forum,
                    ),
            )
        writes.forEach { (sql, params) ->
            val written = runCatching { AppRoleJdbc.inTransaction(b.tenant.id) { c -> c.update(sql, *params.toTypedArray()) } }
            assertThat(written.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }
        // Within B, a row cannot point at A's meeting: the composite foreign key needs B's own.
        val crossed =
            runCatching {
                AppRoleJdbc.inTransaction(b.tenant.id) { c ->
                    c.update(
                        "insert into meeting_guest (id, tenant_id, meeting_id, user_id, added_at) values (?, ?, ?, ?, ?)",
                        UUID.randomUUID(),
                        b.tenant.id.value,
                        ofA,
                        b.member.id.value,
                        now,
                    )
                }
            }
        assertThat(crossed.exceptionOrNull()).isInstanceOf(SQLException::class.java)
        assertThat(
            AppRoleJdbc.inTransaction(b.tenant.id) { c -> c.update("update meeting set title = 'x' where id = ?", ofA) },
        ).isZero()

        // The app role deletes nothing, even in its own tenant.
        listOf("meeting" to "id", "meeting_guest" to "meeting_id", "meeting_audience" to "meeting_id", "meeting_counter" to "forum_id")
            .forEach { (table, column) ->
                val deleted =
                    runCatching {
                        AppRoleJdbc.inTransaction(a.tenant.id) { c -> c.update("delete from $table where $column = ?", ofA) }
                    }
                assertThat(deleted.exceptionOrNull()).describedAs(table).isInstanceOf(SQLException::class.java)
            }
    }

    @Test
    fun `another tenant neither sees nor writes agendas, sections, items, attendees or papers`() {
        val a = side("agendaa")
        val b = side("agendab")
        val ofA = meeting(a)
        val ofB = meeting(b)
        val itemA =
            asUser(a.tenant, a.admin) {
                starts.start(ofA, AgendaOrigin.TEMPLATE, null)
                items.add(ofA, views.draft(ofA).version, ItemInput(ItemKind.ITEM, title = "Capital plan"))
            }
        val versionA = asUser(a.tenant, a.admin) { views.draft(ofA).version }
        asUser(b.tenant, b.admin) { starts.start(ofB, AgendaOrigin.TEMPLATE, null) }

        // Through the services: B's administrator gets 404 for A's agenda, and copying it is refused as unknown.
        assertThatThrownBy { asUser(b.tenant, b.admin) { views.draft(ofA) } }.isInstanceOf(MeetingNotFoundException::class.java)
        assertThatThrownBy { asUser(b.tenant, b.admin) { starts.start(ofA, AgendaOrigin.TEMPLATE, null) } }
            .isInstanceOf(MeetingNotFoundException::class.java)
        assertThatThrownBy {
            asUser(
                b.tenant,
                b.admin,
            ) { items.remove(ofA, itemA, versionA) }
        }.isInstanceOf(MeetingNotFoundException::class.java)
        val another = meeting(b)
        val copy = runCatching { asUser(b.tenant, b.admin) { starts.start(another, AgendaOrigin.MEETING, ofA) } }.exceptionOrNull()
        assertThat((copy as MeetingRejectedException).problems.map { "${it.field}:${it.code}" }).containsExactly("meetingId:unknown")

        // Through SQL as the app role: none of A's rows is visible to B, none written for A, none pointing at A's.
        val tables = listOf("meeting_agenda", "meeting_section", "meeting_item", "meeting_item_attendee", "meeting_paper")
        AppRoleJdbc.inTransaction(b.tenant.id) { c ->
            tables.forEach { table ->
                assertThat(c.long("select count(*) from $table where tenant_id = ?", a.tenant.id.value)).describedAs(table).isZero()
            }
            assertThat(c.update("update meeting_item set title = 'x' where id = ?", itemA)).isZero()
        }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val sectionB = asUser(b.tenant, b.admin) { views.draft(ofB) }.sections.first().id
        val writes =
            listOf(
                // A's tenant id: refused by the policy.
                "insert into meeting_item_attendee (tenant_id, item_id, user_id, added_at) values (?, ?, ?, ?)" to
                    listOf<Any>(a.tenant.id.value, itemA, b.member.id.value, now),
                // B's tenant id pointing at A's item: refused by the composite foreign key.
                "insert into meeting_item_attendee (tenant_id, item_id, user_id, added_at) values (?, ?, ?, ?)" to
                    listOf<Any>(b.tenant.id.value, itemA, b.member.id.value, now),
                "insert into meeting_item (id, tenant_id, meeting_id, section_id, position, kind, title, classification_id, " +
                    "created_at, updated_at, purpose) values (?, ?, ?, ?, 0, 'ITEM', 'x', ?, ?, ?, 'DECISION')" to
                    listOf<Any>(UUID.randomUUID(), b.tenant.id.value, ofA, sectionB, UUID.randomUUID(), now, now),
            )
        writes.forEach { (sql, params) ->
            val written = runCatching { AppRoleJdbc.inTransaction(b.tenant.id) { c -> c.update(sql, *params.toTypedArray()) } }
            assertThat(written.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }

        // The app role deletes nothing from the agenda tables, even in its own tenant.
        tables.forEach { table ->
            val (column, id) = if (table == "meeting_item_attendee") "item_id" to itemA else "meeting_id" to ofA
            val deleted =
                runCatching { AppRoleJdbc.inTransaction(a.tenant.id) { c -> c.update("delete from $table where $column = ?", id) } }
            assertThat(deleted.exceptionOrNull()).describedAs(table).isInstanceOf(SQLException::class.java)
        }
    }

    @Test
    fun `another tenant neither sees nor writes revisions, notices or chair comments, and nobody changes a revision`() {
        val a = side("revisiona")
        val b = side("revisionb")
        val (ofA, revision) = published(a)

        // B's administrator gets 404 for A's publication and chair actions.
        assertThatThrownBy { asUser(b.tenant, b.admin) { publisher.publish(ofA, 1, null, true) } }
            .isInstanceOf(MeetingNotFoundException::class.java)
        assertThatThrownBy { asUser(b.tenant, b.admin) { publisher.preview(ofA) } }.isInstanceOf(MeetingNotFoundException::class.java)
        assertThatThrownBy { asUser(b.tenant, b.admin) { chairing.agree(ofA, 1, true, "by email") } }
            .isInstanceOf(MeetingNotFoundException::class.java)

        // Through SQL as the app role: none of A's rows is visible to B, none written for A or pointing at A's.
        val tables =
            listOf(
                "meeting_revision",
                "meeting_revision_section",
                "meeting_revision_item",
                "meeting_revision_paper",
                "meeting_revision_notice",
                "meeting_chair_comment",
            )
        AppRoleJdbc.inTransaction(a.tenant.id) { c ->
            assertThat(c.long("select count(*) from meeting_revision_item where revision_id = ?", revision)).isGreaterThan(0)
        }
        AppRoleJdbc.inTransaction(b.tenant.id) { c ->
            tables.forEach { table ->
                assertThat(c.long("select count(*) from $table where tenant_id = ?", a.tenant.id.value)).describedAs(table).isZero()
            }
        }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val writes =
            listOf(
                "insert into meeting_revision_notice (tenant_id, revision_id, user_id, kind, notified_at) " +
                    "values (?, ?, ?, 'MEETING_AGENDA_PUBLISHED', ?)" to listOf<Any>(a.tenant.id.value, revision, b.member.id.value, now),
                "insert into meeting_revision_notice (tenant_id, revision_id, user_id, kind, notified_at) " +
                    "values (?, ?, ?, 'MEETING_AGENDA_PUBLISHED', ?)" to listOf<Any>(b.tenant.id.value, revision, b.member.id.value, now),
                "insert into meeting_chair_comment (id, tenant_id, meeting_id, text, created_at) values (?, ?, ?, 'x', ?)" to
                    listOf<Any>(UUID.randomUUID(), b.tenant.id.value, ofA, now),
            )
        writes.forEach { (sql, params) ->
            val written = runCatching { AppRoleJdbc.inTransaction(b.tenant.id) { c -> c.update(sql, *params.toTypedArray()) } }
            assertThat(written.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }

        assertAppendOnly(a, ofA, revision)
    }

    @Test
    fun `another tenant neither sees nor writes declarations, and declarations change only along their transitions`() {
        val a = side("declarea")
        val b = side("declareb")
        val (ofA, revision) = published(a)
        val itemA = query(a, "select item_id from meeting_revision_item where revision_id = ? and title = 'Capital plan'", revision)
        val declared = asUser(a.tenant, a.member) { declarations.declare(ofA, itemA, DeclareInput("Shares in the borrower")) }
        asUser(a.tenant, a.member) { reviews.review(ofA, 1) }

        // Through the services: B's administrator gets 404 for A's meeting, its items and its declarations.
        assertThatThrownBy { asUser(b.tenant, b.admin) { declarations.declare(ofA, itemA, DeclareInput("x")) } }
            .isInstanceOf(MeetingNotFoundException::class.java)
        assertThatThrownBy { asUser(b.tenant, b.admin) { declarations.withdraw(ofA, declared.id, 0, "x") } }
            .isInstanceOf(MeetingNotFoundException::class.java)
        assertThatThrownBy { asUser(b.tenant, b.admin) { reviews.mine(ofA) } }.isInstanceOf(MeetingNotFoundException::class.java)

        // Through SQL as the app role: none of A's rows is visible to B, none written for A or pointing at A's.
        val tables =
            listOf("meeting_declaration", "meeting_declaration_event", "meeting_declaration_review", "meeting_declaration_reminder")
        AppRoleJdbc.inTransaction(a.tenant.id) { c ->
            assertThat(c.long("select count(*) from meeting_declaration_review where meeting_id = ?", ofA)).isEqualTo(1)
        }
        AppRoleJdbc.inTransaction(b.tenant.id) { c ->
            tables.forEach { table ->
                assertThat(c.long("select count(*) from $table where tenant_id = ?", a.tenant.id.value)).describedAs(table).isZero()
            }
            assertThat(
                c.update("update meeting_declaration set state = 'WITHDRAWN', version = version + 1 where id = ?", declared.id),
            ).isZero()
        }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val member = b.member.id.value
        val writes =
            listOf(
                "insert into meeting_declaration_reminder (tenant_id, meeting_id, user_id, revision, sent_at) values (?, ?, ?, 1, ?)" to
                    listOf<Any>(a.tenant.id.value, ofA, member, now),
                "insert into meeting_declaration_reminder (tenant_id, meeting_id, user_id, revision, sent_at) values (?, ?, ?, 1, ?)" to
                    listOf<Any>(b.tenant.id.value, ofA, member, now),
                "insert into meeting_declaration (id, tenant_id, meeting_id, item_id, forum_id, user_id, nature, intent, source, how, " +
                    "declared_at, at_meeting, state) values (?, ?, ?, ?, ?, ?, 'x', 'LEAVE', 'SELF', 'IN_APP', ?, false, 'RECUSED')" to
                    listOf<Any>(UUID.randomUUID(), b.tenant.id.value, ofA, itemA, b.forum, member, now),
            )
        writes.forEach { (sql, params) ->
            val written = runCatching { AppRoleJdbc.inTransaction(b.tenant.id) { c -> c.update(sql, *params.toTypedArray()) } }
            assertThat(written.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }

        assertDeclarationGuarded(a, ofA, itemA, declared.id)
    }

    /**
     * Within its own tenant the app role changes a declaration only along design E04 §1.2, with the version moving by one;
     * never its nature, declarant or item; and it neither changes nor deletes the history and the reviews. An item of
     * another meeting cannot take a declaration of this one.
     */
    private fun assertDeclarationGuarded(
        a: Side,
        ofA: UUID,
        itemA: UUID,
        declaration: UUID,
    ) {
        AppRoleJdbc.inTransaction(a.tenant.id) { c ->
            assertThat(c.update("update meeting_declaration set state = 'TAKING_PART', version = version + 1 where id = ?", declaration))
                .isEqualTo(1)
        }
        val refused =
            listOf(
                "update meeting_declaration set nature = 'x' where id = ?",
                "update meeting_declaration set user_id = gen_random_uuid() where id = ?",
                "update meeting_declaration set state = 'RECUSED', version = version + 1 where id = ?",
                "update meeting_declaration set state = 'TAKING_PART' where id = ?",
                "delete from meeting_declaration where id = ?",
                "update meeting_declaration_event set note = 'x' where declaration_id = ?",
                "delete from meeting_declaration_event where declaration_id = ?",
            )
        refused.forEach { sql ->
            val changed = runCatching { AppRoleJdbc.inTransaction(a.tenant.id) { c -> c.update(sql, declaration) } }
            assertThat(changed.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }
        listOf(
            "update meeting_declaration_review set revision = 1 where meeting_id = ?",
            "delete from meeting_declaration_review where meeting_id = ?",
        ).forEach { sql ->
            val changed = runCatching { AppRoleJdbc.inTransaction(a.tenant.id) { c -> c.update(sql, ofA) } }
            assertThat(changed.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }
        // From WITHDRAWN nothing moves.
        val fromWithdrawn =
            runCatching {
                AppRoleJdbc.inTransaction(a.tenant.id) { c ->
                    c.update("update meeting_declaration set state = 'WITHDRAWN', version = version + 1 where id = ?", declaration)
                    c.update("update meeting_declaration set state = 'RECUSED', version = version + 1 where id = ?", declaration)
                }
            }
        assertThat(fromWithdrawn.exceptionOrNull()).isInstanceOf(SQLException::class.java)
        // The item must be of the declaration's meeting (the composite foreign key).
        val another = meeting(a)
        val crossed =
            runCatching {
                AppRoleJdbc.inTransaction(a.tenant.id) { c ->
                    c.update(
                        "insert into meeting_declaration (id, tenant_id, meeting_id, item_id, forum_id, user_id, nature, intent, source, " +
                            "how, declared_at, at_meeting, state) " +
                            "values (?, ?, ?, ?, ?, ?, 'x', 'LEAVE', 'SELF', 'IN_APP', now(), false, 'RECUSED')",
                        UUID.randomUUID(),
                        a.tenant.id.value,
                        another,
                        itemA,
                        a.forum,
                        a.member.id.value,
                    )
                }
            }
        assertThat(crossed.exceptionOrNull()).isInstanceOf(SQLException::class.java)
    }

    private fun query(
        a: Side,
        sql: String,
        param: UUID,
    ): UUID =
        AppRoleJdbc.inTransaction(a.tenant.id) { c ->
            c.prepareStatement(sql).use { st ->
                st.setObject(1, param)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1, UUID::class.java)
                }
            }
        }

    /** A published revision of a meeting of [a], with a chair comment: the meeting and the revision. */
    private fun published(a: Side): Pair<UUID, UUID> {
        val chair = accounts.anActiveUser(transactions, a.tenant, aPassword())
        asUser(a.tenant, a.admin) { members.add(a.forum, MemberInput(userId = chair.id.value, role = ForumRole.CHAIR)) }
        val ofA = meeting(a)
        val item =
            asUser(a.tenant, a.admin) {
                starts.start(ofA, AgendaOrigin.TEMPLATE, null)
                items.add(ofA, views.draft(ofA).version, ItemInput(ItemKind.ITEM, title = "Capital plan"))
            }
        asUser(a.tenant, chair) { chairing.comment(ofA, "Fine by me") }
        asUser(a.tenant, chair) { chairing.agree(ofA, views.draft(ofA).version, false, null) }
        asUser(a.tenant, a.admin) { publisher.publish(ofA, views.draft(ofA).version, null, true) }
        val revision =
            AppRoleJdbc.inTransaction(a.tenant.id) { c ->
                c.prepareStatement("select id from meeting_revision where meeting_id = ?").use { st ->
                    st.setObject(1, ofA)
                    st.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1, UUID::class.java)
                    }
                }
            }
        assertThat(item).isNotNull()
        return ofA to revision
    }

    /** Revisions, notices and comments are append-only: the app role neither updates nor deletes them, in its own tenant. */
    private fun assertAppendOnly(
        a: Side,
        ofA: UUID,
        revision: UUID,
    ) {
        val changes =
            listOf(
                "update meeting_revision set note = 'x' where id = ?",
                "update meeting_revision_item set title = 'x' where revision_id = ?",
                "update meeting_revision_section set title = 'x' where revision_id = ?",
                "delete from meeting_revision_item where revision_id = ?",
                "delete from meeting_revision_paper where revision_id = ?",
                "delete from meeting_revision_notice where revision_id = ?",
                "delete from meeting_revision where id = ?",
            )
        changes.forEach { sql ->
            val changed = runCatching { AppRoleJdbc.inTransaction(a.tenant.id) { c -> c.update(sql, revision) } }
            assertThat(changed.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }
        val comment =
            runCatching {
                AppRoleJdbc.inTransaction(
                    a.tenant.id,
                ) { c -> c.update("update meeting_chair_comment set text = 'x' where meeting_id = ?", ofA) }
            }
        assertThat(comment.exceptionOrNull()).isInstanceOf(SQLException::class.java)
    }

    private fun <T> asUser(
        tenant: TestTenant,
        account: UserAccount,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(account.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }
}
