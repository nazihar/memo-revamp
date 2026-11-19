package com.memo.meeting

import com.memo.forum.ForumRole
import com.memo.forum.internal.MemberInput
import com.memo.meeting.internal.DeclarationRow
import com.memo.meeting.internal.DeclarationRulings
import com.memo.meeting.internal.Declarations
import com.memo.meeting.internal.DeclareInput
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.RulingInput
import com.memo.testsupport.Person
import com.memo.testsupport.Samples
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.util.UUID

/**
 * The paper-rule committee (PaperFixture) with what the recusal tests add (test plan E04, T2): a vice-chair, a second
 * secretary, and declarations made, recorded and ruled on through the services.
 */
abstract class RecusalFixture : PaperFixture() {
    @Autowired lateinit var declarations: Declarations

    @Autowired lateinit var rulings: DeclarationRulings

    lateinit var vice: Person

    /** The second secretary, who handles an item the first is recused from. */
    lateinit var secretary2: Person

    @BeforeEach
    fun officers() {
        vice = person(clearance = 1)
        secretary2 = person(clearance = 1)
        asUser(admin) { members.add(brc, MemberInput(userId = vice.id, role = ForumRole.VICE_CHAIR)) }
        asUser(admin) { members.add(brc, MemberInput(userId = secretary2.id, role = ForumRole.SECRETARY)) }
    }

    /** [person] declares on [item] for themselves. */
    fun declare(
        person: Person,
        meeting: MeetingRow,
        item: UUID,
        intent: DeclarationIntent = DeclarationIntent.LEAVE,
    ): DeclarationRow = asUser(person.account) { declarations.declare(meeting.id, item, DeclareInput(NATURE, intent)) }

    /** [chairPerson] rules on [declaration] in the app. */
    fun rule(
        chairPerson: Person,
        meeting: MeetingRow,
        declaration: DeclarationRow,
        outcome: RulingOutcome,
    ): DeclarationRow = asUser(chairPerson.account) { rulings.rule(meeting.id, declaration.id, declaration.version, RulingInput(outcome)) }

    /** A sub-item of [parent]. */
    fun subItem(
        meeting: MeetingRow,
        parent: UUID,
        title: String,
    ): UUID =
        asUser(secretary.account) { items.add(meeting.id, version(meeting), ItemInput(ItemKind.ITEM, parentId = parent, title = title)) }

    /** A new version of an uploaded paper's file, uploaded by [person] (an officer not recused from its items). */
    fun replaceAs(
        person: Person,
        fileId: UUID,
    ) {
        val file = read("/api/v1/files/$fileId", person.session).json()
        val replaced =
            upload(
                person.session,
                Samples.pdf(2),
                "/api/v1/files/$fileId/versions",
                "v2.pdf",
                mapOf("If-Match" to "\"${file["version"]}\""),
            )
        assertThat(replaced).hasStatus(HttpStatus.CREATED)
    }

    /** The draft as [person] (an officer) reads it, over HTTP. */
    fun draftOf(
        person: Person,
        meeting: MeetingRow,
    ): MvcTestResult = read("/api/v1/meetings/${meeting.id}/agenda/draft", person.session)

    /** A builder change over HTTP by [person], with the agenda's current version as If-Match. */
    fun change(
        person: Person,
        method: HttpMethod,
        uri: String,
        meeting: MeetingRow,
        body: String = "{}",
    ): MvcTestResult =
        send(
            method,
            uri,
            person,
            body,
            mapOf("If-Match" to "\"${version(meeting)}\"", "Idempotency-Key" to UUID.randomUUID().toString()),
        )

    companion object {
        const val NATURE = "My brother-in-law is a director of Delta Textiles"
        const val TITLE = "Term loan, Delta Textiles"
    }
}
