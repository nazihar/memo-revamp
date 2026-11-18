package com.memo.notification.internal

import com.memo.notification.CalendarMethod
import jakarta.activation.DataHandler
import jakarta.mail.Part
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource

/**
 * The iMIP layout of a mail with a calendar part (RFC 6047 §2.4, ADR-0063):
 * ```
 * multipart/mixed
 *   multipart/alternative
 *     text/plain; charset=UTF-8
 *     text/html; charset=UTF-8
 *     text/calendar; method=REQUEST; charset=UTF-8   (method equals the file's METHOD)
 *   application/ics; name="invite.ics"              (the same bytes, as an attachment)
 * ```
 * The human-readable parts come first, so a client that cannot read the calendar shows the text; the attachment lets a
 * person import the file where the client offers no button.
 */
internal object Imip {
    const val FILE_NAME = "invite.ics"

    fun layout(
        mime: MimeMessage,
        text: String,
        html: String,
        method: CalendarMethod,
        ics: ByteArray,
    ) {
        val calendarType = "text/calendar; method=${method.name}; charset=UTF-8"
        val alternative =
            MimeMultipart("alternative").apply {
                addBodyPart(MimeBodyPart().apply { setText(text, Charsets.UTF_8.name(), "plain") })
                addBodyPart(MimeBodyPart().apply { setText(html, Charsets.UTF_8.name(), "html") })
                addBodyPart(part(ics, calendarType))
            }
        val attachment =
            part(ics, "application/ics; name=\"$FILE_NAME\"").apply {
                disposition = Part.ATTACHMENT
                fileName = FILE_NAME
            }
        mime.setContent(
            MimeMultipart("mixed").apply {
                addBodyPart(MimeBodyPart().apply { setContent(alternative) })
                addBodyPart(attachment)
            },
        )
    }

    /** A part with exactly these bytes and this Content-Type (the data source's type becomes the header). */
    private fun part(
        bytes: ByteArray,
        type: String,
    ) = MimeBodyPart().apply {
        dataHandler = DataHandler(ByteArrayDataSource(bytes, type))
        setHeader("Content-Type", type)
    }
}
