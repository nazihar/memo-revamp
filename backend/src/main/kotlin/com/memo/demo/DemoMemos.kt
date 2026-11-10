package com.memo.demo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileUploads
import com.memo.identity.UserAccounts
import com.memo.identity.UserId
import com.memo.identity.UserLifecycle
import com.memo.memo.MemoSeeding
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Demo memos in every state (design E01 §15), written by `user@` and acted on by the demo approvers through the real
 * services, so the inbox, the boxes, the history and the reassignment screen all have something to show. Runs once: if
 * `user@` has any memo, nothing is added. Needs the demo password (approvers must be able to sign in) and the files
 * service (scanner and object storage).
 */
@Component
class DemoMemoSeeder(
    private val accounts: UserAccounts,
    private val lifecycle: UserLifecycle,
    private val memos: MemoSeeding,
    private val transactions: TenantTransactions,
    @param:Value("\${memo.demo.password:}") private val demoPassword: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun seed() {
        if (demoPassword.isBlank()) return
        val ids = ActorContext.asSystem("demo-memos") { transactions.inTenant(DemoTenant.ID) { people() } }
        val author = ids.getValue(AUTHOR)
        if (asUser(author) { memos.hasMemos(author) }) return
        val credit = ids.getValue(HEAD_CREDIT)
        val finance = ids.getValue(HEAD_FINANCE)
        val director = ids.getValue(DIRECTOR)

        // A draft with its document and route, ready to submit in the demo.
        draft("Laptop refresh for the credit team", listOf(credit, director))
        // In review at step 1, and at step 2.
        submitted("Opening a branch in Sylhet", listOf(credit, finance, director))
        submitted("Annual training budget", listOf(credit, director)).also { act(credit, it, MemoSeeding.APPROVE, "Supported.") }
        // Returned with a remark, and resubmitted as round 2 with a new version.
        submitted("Vendor contract renewal", listOf(credit, director)).also {
            act(credit, it, MemoSeeding.RETURN, "Please attach the vendor's quotation.")
        }
        submitted("Office lease extension", listOf(credit, finance)).also {
            act(credit, it, MemoSeeding.RETURN, "The rent figure on page 2 does not match the agreement.")
            asUser(author) {
                memos.document(it, "office-lease-extension.docx", docx("Office lease extension", "Version 2: rent figure corrected."))
                memos.resubmit(it, "Corrected the rent figure.")
            }
        }
        // Approved, rejected and withdrawn.
        submitted("Staff welfare fund contribution", listOf(credit, director)).also {
            act(credit, it, MemoSeeding.APPROVE)
            act(director, it, MemoSeeding.APPROVE, "Approved.")
        }
        submitted("Sponsorship of a football tournament", listOf(finance)).also {
            act(finance, it, MemoSeeding.REJECT, "Outside this year's budget.")
        }
        submitted("Duplicate request for printers", listOf(credit)).also { asUser(author) { memos.withdraw(it, "Sent twice by mistake.") } }
        // A task waiting for reassignment: the second approver left after the memo was submitted.
        val leaver = ids.getValue(LEAVER)
        submitted("Replacement of the branch generator", listOf(credit, leaver)).also {
            ActorContext.asSystem("demo-memos") {
                transactions.inTenant(DemoTenant.ID) {
                    val account = checkNotNull(accounts.find(UserId(leaver)))
                    lifecycle.deactivate(account.id, account.version, UserId(ids.getValue(ADMIN)))
                }
            }
            act(credit, it, MemoSeeding.APPROVE)
        }
        log.info("Demo memos seeded")
    }

    /** The demo people by email; the leaver is created active once, so the route accepts them before they leave. */
    private fun people(): Map<String, UUID> {
        val leaver =
            accounts.findByEmail(LEAVER) ?: accounts.create(LEAVER, "Demo Leaver").also { accounts.setPassword(it.id, demoPassword) }
        return listOf(AUTHOR, HEAD_CREDIT, HEAD_FINANCE, DIRECTOR, ADMIN).associateWith {
            checkNotNull(accounts.findByEmail(it)) { "demo user $it missing" }.id.value
        } + (LEAVER to leaver.id.value)
    }

    private fun draft(
        subject: String,
        route: List<UUID>,
    ): UUID {
        val author = authorId()
        return asUser(author) {
            val id = memos.draft(subject)
            memos.document(id, subject.lowercase().replace(' ', '-') + ".docx", docx(subject, "Version 1."))
            memos.route(id, route)
            id
        }
    }

    private fun submitted(
        subject: String,
        route: List<UUID>,
    ): UUID = draft(subject, route).also { id -> asUser(authorId()) { memos.submit(id, "For your approval.") } }

    private fun act(
        approver: UUID,
        memo: UUID,
        action: String,
        remark: String? = null,
    ) = asUser(approver) { memos.act(memo, action, remark) }

    private fun authorId(): UUID =
        ActorContext.asSystem("demo-memos") {
            transactions.inTenant(DemoTenant.ID) { checkNotNull(accounts.findByEmail(AUTHOR)).id.value }
        }

    private fun <T> asUser(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(DemoTenant.ID, block) }

    private fun docx(
        title: String,
        version: String,
    ) = DemoDocx.of(
        title,
        listOf("To: the approvers on the route.", "This memo asks for your approval of: ${title.lowercase()}.", version),
        listOf("مذكرة تجريبية: $title"),
    )

    companion object {
        const val AUTHOR = "user@demo.example"
        const val HEAD_CREDIT = "head.credit@demo.example"
        const val HEAD_FINANCE = "head.finance@demo.example"
        const val DIRECTOR = "director@demo.example"
        const val ADMIN = "admin@demo.example"
        const val LEAVER = "leaver@demo.example"
    }
}

/**
 * After the demo tenant itself (its users and their passwords), on a background thread: the documents need the malware
 * scanner, which may still be loading its signatures when the app starts. The app never waits for it or fails because
 * of it; the demo memos simply appear once the scanner answers (or not at all, with a warning, if it never does).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty("memo.demo.seed", havingValue = "true")
class DemoMemoSeedOnStartup(
    private val seeder: DemoMemoSeeder,
    private val uploads: FileUploads,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        Thread.ofVirtual().name("demo-memos").start(::seedWhenReady)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun seedWhenReady() {
        if (!scannerAnswers()) {
            log.warn("Demo memos not seeded: the malware scanner did not answer")
            return
        }
        try {
            seeder.seed()
        } catch (e: RuntimeException) {
            log.warn("Demo memos not seeded", e)
        }
    }

    private fun scannerAnswers(): Boolean {
        repeat(ATTEMPTS) {
            if (uploads.ready()) return true
            Thread.sleep(WAIT_MILLIS)
        }
        return false
    }

    private companion object {
        const val ATTEMPTS = 90
        const val WAIT_MILLIS = 10_000L
    }
}
