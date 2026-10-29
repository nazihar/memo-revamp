package com.memo.testsupport

import com.memo.common.crypto.DataKeys
import com.memo.notification.Mail
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** A queued email as tests read it back: template, recipient and decrypted parameters. */
data class QueuedMail(
    val id: UUID,
    val template: String,
    val to: String,
    val status: String,
    val params: Map<String, String>,
)

/** Reads the tenant's mail outbox (RLS-scoped, as the application role) and decrypts parameters with [keys]. */
class MailOutbox(
    private val keys: DataKeys,
    private val json: JsonMapper,
) {
    fun all(tenant: TestTenant): List<QueuedMail> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.query("select id, template, to_address, status, params_enc from notification_mail_outbox order by created_at, id") { rs ->
                val id = rs.getObject(1, UUID::class.java)
                val sealed: ByteArray? = rs.getBytes(5)
                QueuedMail(id, rs.getString(2), rs.getString(3), rs.getString(4), sealed?.let { params(tenant, id, it) }.orEmpty())
            }
        }

    fun last(
        tenant: TestTenant,
        template: String,
        to: String,
    ): QueuedMail = all(tenant).last { it.template == template && it.to == to }

    /** The one-time token of an emailed link (after `#`). */
    fun token(mail: QueuedMail): String = mail.params.getValue("link").substringAfter('#')

    @Suppress("UNCHECKED_CAST")
    private fun params(
        tenant: TestTenant,
        id: UUID,
        sealed: ByteArray,
    ): Map<String, String> =
        json.readValue(keys.decrypt(sealed, Mail.aad(tenant.id.toString(), id.toString())), Map::class.java) as Map<String, String>
}
