package com.memo.search

import com.memo.common.authz.Permission
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.forum.ForumRole
import com.memo.forum.internal.MemberInput
import com.memo.jooq.tables.references.MEETING_DECLARATION
import com.memo.meeting.DeclarationState
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.MeetingRow
import com.memo.minutes.DecisionPlanting
import com.memo.minutes.ReleaseFixture
import com.memo.minutes.internal.DecisionStore
import com.memo.minutes.internal.NoteStore
import com.memo.minutes.internal.ResolutionNumbers
import com.memo.search.internal.SearchReferences
import com.memo.search.internal.decision.DecisionIndex
import com.memo.search.internal.decision.DecisionRegister
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The register's API (ADR-0069, design E08 §1.3–§1.4, §3; test plan E08 T2 `DecisionRegisterIT`, `DecisionRegisterRecusalIT`
 * and the security rows): every filter alone and with others, the rows each matches (FULL only, or FULL and LINE), the
 * orders, totals and facets, number and memo-number lookups, keyset pages behind sealed cursors, the input problems, who
 * has a register (and who is 403), restricted and hidden items for members and `forum.read`, a declaration read at once,
 * the audit of each search (words hashed) and the per-person limit.
 */
class DecisionRegisterIT : ReleaseFixture() {
    @Autowired lateinit var decisionIndex: DecisionIndex

    @Autowired lateinit var decisionStore: DecisionStore

    @Autowired lateinit var noteStore: NoteStore

    @Autowired lateinit var numbers: ResolutionNumbers

    @Autowired lateinit var limiter: RateLimiter

    private fun search(
        person: Person,
        body: String = "{}",
    ): MvcTestResult = post("/api/v1/decision-register/search", person.session, body)

    private fun MvcTestResult.rows(): List<JsonNode> = json()["rows"].items()

    private fun MvcTestResult.ids(): List<UUID> = rows().map { UUID.fromString(it["decisionId"].asString()) }

    private fun MvcTestResult.total(): Int = json()["total"].asInt()

    @Test
    fun `every filter, number lookup, order, total and facet follows the register's rules`() {
        val r = running("Term loan, Karnaphuli Steel", "Facility renewal, Acme", "Treasury limits review")
        val loan = decide(r, r.item("Term loan, Karnaphuli Steel").item, APPROVED_WITH_CONDITIONS).decision.id
        val acme = decide(r, r.item("Facility renewal, Acme").item, APPROVED).decision.id
        val treasury = decide(r, r.item("Treasury limits review").item, NOTED).decision.id
        held(r)

        val all = search(secretary)
        assertThat(all).hasStatusOk()
        assertThat(all.response.getHeader("Cache-Control")).contains("no-store")
        val rows = all.rows().associateBy { UUID.fromString(it["decisionId"].asString()) }
        assertThat(rows.keys).containsExactlyInAnyOrder(loan, acme, treasury)
        assertThat(rows.values.map { it["level"].asString() }).containsOnly("FULL")
        assertThat(all.total()).isEqualTo(3)
        assertThat(all.json()["capped"].asBoolean()).isFalse()
        val facets = all.json()["facets"]
        assertThat(facets["forums"].items().map { it["forumId"].asString() to it["count"].asInt() }).containsExactly(brc.toString() to 3)
        assertThat(facets["outcomes"].items().associate { it["outcome"].asString() to it["count"].asInt() })
            .isEqualTo(mapOf("APPROVED_WITH_CONDITIONS" to 1, "APPROVED" to 1, "NOTED" to 1))
        val loanRow = rows.getValue(loan)
        val meetingDate = LocalDate.parse(loanRow["meetingDate"].asString())
        assertThat(facets["years"].items().map { it["year"].asInt() to it["count"].asInt() }).containsExactly(meetingDate.year to 3)
        assertThat(loanRow["title"].asString()).contains("Karnaphuli")
        assertThat(loanRow["conditions"].items().map { it.asString() }).containsExactly("Security perfected before disbursement.")
        assertThat(loanRow["forumName"].asString()).isEqualTo("Board Risk Committee")
        assertThat(loanRow["memoId"].asString()).isEqualTo(r.item("Term loan, Karnaphuli Steel").memo.toString())
        assertThat(loanRow.absent("decidedAt")).isFalse()

        // Words, with the last as a prefix; a number in three spellings; a memo's number.
        assertThat(search(secretary, """{"text":"Karnaph"}""").ids()).containsExactly(loan)
        assertThat(search(secretary, """{"text":"renewed facility"}""").ids()).containsExactly(acme)
        val number = loanRow["resolutionNumber"].asString()
        val spellings =
            listOf(
                """{"text":"$number"}""",
                """{"text":"${number.lowercase().replace(Regex("[^a-z0-9]+"), "-")}"}""",
                """{"text":"${SearchReferences.tail(number)}","forumIds":["$brc"]}""",
            )
        spellings.forEach { assertThat(search(secretary, it).ids()).describedAs(it).contains(loan).doesNotContain(treasury) }
        val memoNumber = loanRow["memoNumber"].asString()
        assertThat(search(secretary, """{"text":"$memoNumber"}""").ids()).containsExactly(loan)

        // Content filters (every flag given must hold), and the filters on what a line shows too.
        assertThat(search(secretary, """{"outcomes":["NOTED"]}""").ids()).containsExactly(treasury)
        assertThat(search(secretary, """{"flags":["WITH_CONDITIONS"]}""").ids()).containsExactly(loan)
        assertThat(search(secretary, """{"flags":["WITH_CONDITIONS","WITH_DISSENT"]}""").ids()).isEmpty()
        assertThat(search(secretary, """{"memoTypeIds":["${loanRow["memoTypeId"].asString()}"]}""").ids()).contains(loan)
        val numbered = rows.filterValues { !it.absent("resolutionNumber") }.keys
        assertThat(numbered).contains(loan, acme)
        assertThat(search(secretary, """{"resolutionsOnly":true}""").ids()).containsExactlyInAnyOrderElementsOf(numbered)
        assertThat(search(secretary, """{"states":["${loanRow["state"].asString()}"]}""").ids()).contains(loan)
        assertThat(search(secretary, """{"states":["VOID"]}""").ids()).isEmpty()
        assertThat(search(secretary, """{"forumTypeIds":["${UUID.randomUUID()}"]}""").ids()).isEmpty()
        assertThat(search(secretary, """{"forumIds":["$brc"],"from":"$meetingDate","to":"$meetingDate"}""").total()).isEqualTo(3)
        assertThat(search(secretary, """{"from":"${meetingDate.plusDays(1)}"}""").ids()).isEmpty()

        // Orders: newest first by default, oldest reversed, number order for one committee.
        val newest = all.ids()
        assertThat(search(secretary, """{"sort":"OLDEST"}""").ids()).isEqualTo(newest.reversed())
        val byNumber = search(secretary, """{"sort":"NUMBER","forumIds":["$brc"]}""")
        assertThat(byNumber.ids()).containsExactlyInAnyOrderElementsOf(numbered)
        val numbers = byNumber.rows().map { it["resolutionNumber"].asString() }
        assertThat(numbers.map { SearchReferences.tail(it)?.toInt() }).isSorted()
        val unscoped = search(secretary, """{"sort":"NUMBER"}""")
        assertThat(unscoped).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(unscoped.errors()).containsExactly("sort:SORT_NEEDS_ONE_FORUM")
    }

    @Test
    fun `pages follow a sealed cursor that only opens for the same search and person`() {
        val meeting = meeting()
        val planted = plant(meeting, "Liquidity policy", "Capital plan", "Recovery plan")
        val ids = mutableListOf<UUID>()
        var cursor: String? = null
        do {
            val body = """{"limit":1${cursor?.let { ",\"cursor\":\"$it\"" }.orEmpty()}}"""
            val page = search(secretary, body)
            assertThat(page).hasStatusOk()
            assertThat(page.rows()).hasSize(1)
            assertThat(page.total()).isEqualTo(3)
            ids += page.ids()
            cursor = page.json()["nextCursor"]?.takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        assertThat(ids).containsExactlyElementsOf(search(secretary).ids()).containsExactlyInAnyOrderElementsOf(planted.values)

        val first = checkNotNull(search(secretary, """{"limit":1}""").json()["nextCursor"]).asString()
        assertThat(first).doesNotContain(planted.values.first().toString())
        // Another search, another person, or a changed byte: 422 `cursor INVALID`, never a page.
        val middle = first.length / 2
        val tampered = first.substring(0, middle) + (if (first[middle] == 'A') 'B' else 'A') + first.substring(middle + 1)
        listOf(
            secretary to """{"limit":1,"sort":"OLDEST","cursor":"$first"}""",
            secretary to """{"limit":1,"text":"policy","cursor":"$first"}""",
            chair to """{"limit":1,"cursor":"$first"}""",
            secretary to """{"limit":1,"cursor":"$tampered"}""",
            secretary to """{"limit":1,"cursor":"not-a-cursor"}""",
        ).forEach { (person, body) ->
            val refused = search(person, body)
            assertThat(refused).describedAs(body).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
            assertThat(refused.errors()).containsExactly("cursor:INVALID")
        }
        assertThat(search(secretary, """{"limit":1,"cursor":"$first"}""")).hasStatusOk()
    }

    @Test
    fun `inputs the rules refuse are 422, and a search is POST only`() {
        val other = otherCommittee()
        val cases =
            mapOf(
                """{"text":"${"a".repeat(201)}"}""" to "text:TEXT_TOO_LONG",
                """{"text":"${(1..13).joinToString(" ") { "word$it" }}"}""" to "text:TOO_MANY_TERMS",
                """{"forumIds":["$other"]}""" to "forumIds:FORUM_OUT_OF_SCOPE",
                """{"forumIds":["${UUID.randomUUID()}"]}""" to "forumIds:FORUM_OUT_OF_SCOPE",
                """{"from":"2026-02-01","to":"2026-01-31"}""" to "to:RANGE",
                """{"from":"2000-01-01","to":"2026-01-02"}""" to "to:RANGE",
                """{"limit":51}""" to "limit:RANGE",
                """{"memoTypeIds":[${(1..51).joinToString(",") { "\"${UUID.randomUUID()}\"" }}]}""" to "memoTypeIds:TOO_MANY",
            )
        cases.forEach { (body, error) ->
            val refused = search(director, body)
            assertThat(refused).describedAs(body).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
            assertThat(refused.errors()).describedAs(body).containsExactly(error)
        }
        assertThat(search(director, """{"text":"${"a".repeat(200)}"}""")).hasStatusOk()
        assertThat(search(director, """{"from":"2001-01-02","to":"2026-01-02"}""")).hasStatusOk()
        // forum.read reads every committee, so the other one is in its scope.
        assertThat(search(reader, """{"forumIds":["$other"]}""")).hasStatusOk()
        assertThat(read("/api/v1/decision-register/search?text=loan", director.session)).hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
    }

    @Test
    fun `members, officers and oversight have a register by the rule, and everyone else is 403`() {
        val other = otherCommittee()
        val meeting = meeting()
        val shown = addItem(meeting, "Liquidity policy")
        val named = addItem(meeting, "Settlement with a counterparty")
        change(meeting, named, ItemChange("Settlement with a counterparty", restrictedTo = listOf(colleague.id), titleHidden = true))
        publish(meeting)
        val planted = plantDecisions(meeting, listOf(shown, named))
        val former = formerMember()

        fun levels(person: Person): Map<UUID, String> =
            search(person).rows().associate { UUID.fromString(it["itemId"].asString()) to it["level"].asString() }

        assertThat(levels(secretary)).isEqualTo(mapOf(shown to "FULL", named to "FULL"))
        assertThat(levels(chair)).isEqualTo(mapOf(shown to "FULL", named to "FULL"))
        val manager = person().also { asUser(admin) { grants.grantCustom(it.id, "Committee managers", setOf(Permission.FORUMS_MANAGE)) } }
        assertThat(levels(manager)).isEqualTo(mapOf(shown to "FULL", named to "FULL"))
        assertThat(levels(colleague)).isEqualTo(mapOf(shown to "FULL", named to "FULL"))
        assertThat(levels(director)).isEqualTo(mapOf(shown to "FULL", named to "LINE"))
        assertThat(levels(reader)).isEqualTo(mapOf(shown to "FULL", named to "LINE"))
        val line = search(director).rows().single { it["level"].asString() == "LINE" }
        assertThat(line["reason"].asString()).isEqualTo("RESTRICTED")
        assertThat(line["decisionId"].asString()).isEqualTo(planted.getValue(named).toString())
        listOf("title", "outcomeKind", "text", "conditions", "memoNumber", "decidedAt", "hasDissent").forEach {
            assertThat(line.absent(it)).describedAs("$it on a line").isTrue()
        }
        assertThat(search(director).response.contentAsString).doesNotContain("Settlement with a counterparty", "Approved $named")
        assertThat(search(director, """{"text":"counterparty"}""").total()).isZero()
        assertThat(search(colleague, """{"text":"counterparty"}""").total()).isEqualTo(1)

        // The scope: a member's committees, an officer's, and oversight's every committee.
        val ofDirector = read("/api/v1/decision-register/scope", director.session).json()
        assertThat(ofDirector["committees"].items().map { it["id"].asString() to it["member"].asBoolean() })
            .containsExactly(brc.toString() to true)
        assertThat(ofDirector["committees"].items().single()["officer"].asBoolean()).isFalse()
        assertThat(ofDirector["canExport"].asBoolean()).isFalse()
        assertThat(ofDirector["outcomes"].items().map { it.asString() }).contains("APPROVED", "DEFERRED")
        assertThat(ofDirector["memoTypes"].items()).isNotEmpty()
        val ofSecretary = read("/api/v1/decision-register/scope", secretary.session).json()
        assertThat(ofSecretary["committees"].items().single()["officer"].asBoolean()).isTrue()
        assertThat(ofSecretary["canExport"].asBoolean() to ofSecretary["canCheckNumbers"].asBoolean()).isEqualTo(true to true)
        val ofReader = read("/api/v1/decision-register/scope", reader.session).json()
        assertThat(ofReader["committees"].items().map { it["id"].asString() }).contains(brc.toString(), other.toString())
        assertThat(ofReader["committeeTypes"].items().map { it["name"].asString() }).contains("Board committee")
        assertThat(ofReader["canExport"].asBoolean()).isTrue()

        // A former member, a guest of the meeting and someone outside have none.
        listOf(former, guest, outsider).forEach { person ->
            assertThat(read("/api/v1/decision-register/scope", person.session)).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(search(person)).hasStatus(HttpStatus.FORBIDDEN)
        }
    }

    @Test
    fun `a declaration applies at once, the item a restricted line until it is withdrawn`() {
        val meeting = meeting()
        val a = addItem(meeting, "Term loan, Karnaphuli Steel")
        val b = addItem(meeting, "Liquidity policy")
        publish(meeting)
        val planted = plantDecisions(meeting, listOf(a, b))
        val number = checkNotNull(RegisterTable.row(tenant.id.value, planted.getValue(a))?.get("resolution_number")) as String
        assertThat(search(director, """{"text":"Karnaphuli"}""").total()).isEqualTo(1)

        listOf(director, secretary).forEach { person ->
            val declaration = declared(meeting, person, a, DeclarationState.RECUSED)
            val found = search(person)
            val byItem = found.rows().associateBy { UUID.fromString(it["itemId"].asString()) }
            assertThat(byItem.getValue(a)["level"].asString() to byItem.getValue(a)["reason"].asString()).isEqualTo("LINE" to "RECUSED")
            assertThat(byItem.getValue(b)["level"].asString()).describedAs("their other items").isEqualTo("FULL")
            assertThat(found.json()["facets"]["outcomes"].items().sumOf { it["count"].asInt() }).isEqualTo(1)
            assertThat(
                found
                    .json()["facets"]["forums"]
                    .items()
                    .single()["count"]
                    .asInt(),
            ).isEqualTo(2)
            assertThat(found.response.contentAsString).doesNotContain("Karnaphuli")
            assertThat(search(person, """{"text":"Karnaphuli"}""").total()).isZero()
            assertThat(search(person, """{"outcomes":["APPROVED"]}""").ids()).containsExactly(planted.getValue(b))
            assertThat(search(person, """{"text":"$number"}""").rows().map { it["level"].asString() }).containsExactly("LINE")
            setState(declaration, DeclarationState.AWAITING_RULING)
            assertThat(search(person).rows().single { it["itemId"].asString() == a.toString() }["level"].asString()).isEqualTo("LINE")
            setState(declaration, DeclarationState.WITHDRAWN)
            assertThat(search(person, """{"text":"Karnaphuli"}""").total()).isEqualTo(1)
            assertThat(search(person).rows().map { it["level"].asString() }).containsOnly("FULL")
        }
    }

    @Test
    fun `each search is audited with its filters, total and page, and the words as a hash only`() {
        val meeting = meeting()
        val item = addItem(meeting, "Term loan, Karnaphuli Steel")
        publish(meeting)
        val planted = plantDecisions(meeting, listOf(item))
        assertThat(search(secretary, """{"text":"  Karnaphuli   Steel ","forumIds":["$brc"],"outcomes":["APPROVED"]}""")).hasStatusOk()
        val entry = Audit.assertAudited(tenant.id, "search.decisions.performed", secretary.id.toString(), secretary.id.toString())
        assertThat(entry.canonical)
            .contains(DecisionRegister.sha256("karnaphuli steel"), brc.toString(), "APPROVED", planted.getValue(item).toString())
            .contains("textSha256", "numberLookup", "total")
            .doesNotContain("Karnaphuli", "karnaphuli", "Steel")
    }

    @Test
    fun `the 31st search in a minute is 429`() {
        assertThat(search(reader)).hasStatusOk()
        // The rest of the minute's 30, taken at once (the bucket refills smoothly, so 29 slow searches could let a token back
        // in on a busy machine): the next search is refused before any transaction, with Retry-After.
        val subject = "${tenant.id}:${reader.id}"
        repeat(29) { limiter.consume(RateLimit.DECISION_SEARCH_PER_USER, subject) }
        val refused = search(reader)
        assertThat(refused).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(refused.response.getHeader("Retry-After")).isNotNull()
    }

    /** [titles] as items of [meeting], published, each decided (planted) and indexed. */
    private fun plant(
        meeting: MeetingRow,
        vararg titles: String,
    ): Map<UUID, UUID> {
        val items = titles.map { addItem(meeting, it) }
        publish(meeting)
        return plantDecisions(meeting, items)
    }

    /** A planted, numbered and indexed decision ("Approved <item>") on each of [items], by item. */
    private fun plantDecisions(
        meeting: MeetingRow,
        items: List<UUID>,
    ): Map<UUID, UUID> {
        val planting = DecisionPlanting(transactions, decisionStore, noteStore)
        val decided = items.associateWith { planting.decision(tenant.id, meeting.id, brc, it, secretary.id, "Approved $it") }
        decided.values.forEach { planting.number(tenant.id, it, decisionReads, numbers) }
        transactions.inTenant(tenant.id) { decided.values.forEach { check(decisionIndex.put(it)) } }
        return decided
    }

    /** A declaration of [person] on [item] in [state], as a decided item's would be (its window is closed in the app). */
    private fun declared(
        meeting: MeetingRow,
        person: Person,
        item: UUID,
        state: DeclarationState,
    ): UUID {
        val id = UUID.randomUUID()
        val d = MEETING_DECLARATION
        transactions.inTenant(tenant.id) {
            tenantDsl
                .current()
                .insertInto(d)
                .set(d.ID, id)
                .set(d.TENANT_ID, tenant.id.value)
                .set(d.MEETING_ID, meeting.id)
                .set(d.ITEM_ID, item)
                .set(d.FORUM_ID, meeting.forumId)
                .set(d.USER_ID, person.id)
                .set(d.NATURE, "An interest")
                .set(d.INTENT, "LEAVE")
                .set(d.SOURCE, "SELF")
                .set(d.HOW, "IN_APP")
                .set(d.DECLARED_AT, OffsetDateTime.now())
                .set(d.AT_MEETING, false)
                .set(d.STATE, state.name)
                .execute()
        }
        return id
    }

    private fun setState(
        declaration: UUID,
        state: DeclarationState,
    ) {
        val d = MEETING_DECLARATION
        transactions.inTenant(tenant.id) {
            tenantDsl
                .current()
                .update(d)
                .set(d.STATE, state.name)
                .set(d.VERSION, d.VERSION.plus(1))
                .where(d.ID.eq(declaration))
                .execute()
        }
    }

    /** A member whose term ended yesterday. */
    private fun formerMember(): Person =
        person().also { former ->
            asUser(admin) {
                members.add(
                    brc,
                    MemberInput(
                        userId = former.id,
                        role = ForumRole.MEMBER,
                        startsOn = LocalDate.now().minusYears(2),
                        termEndsOn = LocalDate.now().minusDays(1),
                    ),
                )
            }
        }
}
