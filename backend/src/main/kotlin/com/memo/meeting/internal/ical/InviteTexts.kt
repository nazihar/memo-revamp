package com.memo.meeting.internal.ical

import com.memo.meeting.MeetingMode
import org.springframework.context.support.ResourceBundleMessageSource
import java.util.Locale

/**
 * The words of an invitation (design E05 §1.2.1) in the recipient's language, from `meeting/invite*.properties` (plain
 * `{name}` placeholders, as the mail texts). The description says how to reply in memo, links the meeting page and
 * carries the joining link and details; it never holds the agenda, items, papers, classification or a reason.
 */
internal class InviteTexts {
    private val messages =
        ResourceBundleMessageSource().apply {
            setBasename("meeting/invite")
            setDefaultEncoding(Charsets.UTF_8.name())
            setFallbackToSystemLocale(false)
            setAlwaysUseMessageFormat(false)
        }

    fun description(
        kind: InviteKind,
        event: InviteEvent,
        meetingUrl: String,
        joinUrl: String?,
        locale: Locale,
    ): String {
        val meeting = text("invite.meeting", locale, "link" to meetingUrl)
        val paragraphs =
            when (kind) {
                InviteKind.REQUEST -> listOf(reply(event.mode, locale), joining(meeting, event, joinUrl, locale))
                InviteKind.PUBLISH -> listOf(joining(meeting, event, joinUrl, locale))
                InviteKind.CANCEL -> listOf(text("invite.cancelled", locale), meeting)
                InviteKind.UNINVITE -> listOf(text("invite.uninvited", locale), meeting)
            }
        return paragraphs.joinToString("\n\n")
    }

    /** What changed, for the update email: one sentence per change ("The place changed."), in [codes]' order. */
    fun changes(
        codes: Collection<String>,
        locale: Locale,
    ): String = codes.joinToString(" ") { text("invite.change.$it", locale) }

    /** LOCATION: the place, or "Online meeting" for an online meeting without one. */
    fun location(
        event: InviteEvent,
        locale: Locale,
    ): String? =
        event.location?.let(InviteInput::line)?.ifEmpty { null }
            ?: text("invite.online", locale).takeIf { event.mode == MeetingMode.ONLINE }

    private fun reply(
        mode: MeetingMode,
        locale: Locale,
    ): String = text("invite.reply.${mode.name}", locale) + "\n" + text("invite.buttons", locale)

    private fun joining(
        meeting: String,
        event: InviteEvent,
        joinUrl: String?,
        locale: Locale,
    ): String {
        val details = event.joinDetails?.let(InviteInput::block)?.ifEmpty { null }
        return listOfNotNull(
            meeting,
            joinUrl?.let { text("invite.join", locale, "link" to it) },
            details?.let { text("invite.details", locale, "details" to it) },
        ).joinToString("\n")
    }

    private fun text(
        key: String,
        locale: Locale,
        vararg params: Pair<String, String>,
    ): String {
        val template = messages.getMessage(key, null, locale)
        return params.fold(template) { acc, (name, value) -> acc.replace("{$name}", value) }
    }
}
