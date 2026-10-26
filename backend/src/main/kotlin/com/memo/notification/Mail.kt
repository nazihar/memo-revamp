package com.memo.notification

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.common.crypto.DataKeys
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.NOTIFICATION_MAIL_OUTBOX
import com.memo.notification.internal.MailSend
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Transactional email (design E03 §6). Subjects never carry sensitive content (AGENTS.md §3.4); the parameters each
 * template uses are listed with it.
 */
enum class MailTemplate(
    val subject: String,
    val text: String,
) {
    INVITATION(
        "You are invited to memo",
        "Hello {name},\n\n{inviter} invited you to {organisation} on memo. Set your password and get started:\n\n{link}\n\n" +
            "The link works once and expires in 7 days. If you did not expect this invitation, you can ignore this email.",
    ),
    PASSWORD_RESET(
        "Reset your memo password",
        "Hello {name},\n\nSomeone asked to reset your password for {organisation} on memo. Choose a new one here:\n\n{link}\n\n" +
            "The link works once and expires in 30 minutes. If it was not you, ignore this email: your password stays as it is.",
    ),
    PASSWORD_CHANGED(
        "Your memo password was changed",
        "Hello {name},\n\nThe password of your account at {organisation} was just changed, and your other sessions were " +
            "signed out.\n\nIf this was not you, contact your administrator now.",
    ),
    MFA_RESET(
        "Your memo sign-in was reset",
        "Hello {name},\n\nAn administrator at {organisation} reset your two-step sign-in. You will set up your " +
            "authenticator app again the next time you sign in.\n\nIf you did not expect this, contact your administrator.",
    ),
    SIGN_IN_ALERT(
        "New sign-in to memo",
        "Hello {name},\n\nYour account at {organisation} was signed in to from a new device:\n\n{device}\n{time} (UTC)\n\n" +
            "If this was not you, change your password and contact your administrator.",
    ),
}

/**
 * Queues an email in the caller's tenant transaction and schedules its delivery in the same commit (db-scheduler joins
 * Spring transactions, ADR-0018): a rolled-back business change sends nothing. Parameters are encrypted at rest because
 * they may hold one-time links.
 */
@Service
class Mail(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val keys: DataKeys,
    private val json: JsonMapper,
    private val clock: Clock,
    @param:Qualifier("mailSendTask") private val sendTask: OneTimeTask<MailSend>,
    private val scheduler: ObjectProvider<SchedulerClient>,
) {
    fun enqueue(
        template: MailTemplate,
        to: String,
        params: Map<String, String>,
    ) {
        val id = ids.next()
        val tenant = tenantDsl.tenant()
        tenantDsl
            .current()
            .insertInto(NOTIFICATION_MAIL_OUTBOX)
            .set(NOTIFICATION_MAIL_OUTBOX.ID, id)
            .set(NOTIFICATION_MAIL_OUTBOX.TENANT_ID, tenant.value)
            .set(NOTIFICATION_MAIL_OUTBOX.TEMPLATE, template.name)
            .set(NOTIFICATION_MAIL_OUTBOX.TO_ADDRESS, to)
            .set(NOTIFICATION_MAIL_OUTBOX.PARAMS_ENC, keys.encrypt(json.writeValueAsBytes(params), aad(tenant.toString(), id.toString())))
            .set(NOTIFICATION_MAIL_OUTBOX.STATUS, "PENDING")
            .set(NOTIFICATION_MAIL_OUTBOX.CREATED_AT, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
            .execute()
        scheduler.ifAvailable?.scheduleIfNotExists(sendTask.schedulableInstance(id.toString(), MailSend(tenant.value, id)))
    }

    companion object {
        internal fun aad(
            tenant: String,
            id: String,
        ) = "notification.mail:$tenant:$id"
    }
}
