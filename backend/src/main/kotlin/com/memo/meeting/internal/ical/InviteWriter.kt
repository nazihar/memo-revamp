package com.memo.meeting.internal.ical

import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.parameter.Cn
import net.fortuna.ical4j.model.parameter.PartStat
import net.fortuna.ical4j.model.parameter.Role
import net.fortuna.ical4j.model.parameter.Rsvp
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.property.Attendee
import net.fortuna.ical4j.model.property.CalScale
import net.fortuna.ical4j.model.property.Clazz
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStamp
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.Method
import net.fortuna.ical4j.model.property.Organizer
import net.fortuna.ical4j.model.property.ProdId
import net.fortuna.ical4j.model.property.Sequence
import net.fortuna.ical4j.model.property.Status
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.Url
import net.fortuna.ical4j.model.property.Version
import net.fortuna.ical4j.validate.ValidationEntry
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Writes one meeting invitation file for one recipient (ADR-0063, design E05 §1.2) with ical4j 4.4.0, whose tzurl.org
 * updater is off (`ical4j.properties`). Every file is checked before it is returned: ical4j's strict validation with its
 * iTIP rules for the method, the RFC 5546 rules ical4j does not enforce ([InviteRules]), and the format (CRLF, lines of
 * at most 75 octets, a VTIMEZONE for the TZID). A file that fails is never returned ([InviteInvalidException]).
 */
@Component
class InviteWriter(
    private val clock: Clock,
) {
    private val texts = InviteTexts()

    fun write(invite: Invite): ByteArray {
        val calendar = calendar(invite)
        val problems =
            calendar
                .validate()
                .entries
                .filter { it.severity == ValidationEntry.Severity.ERROR }
                .map { "${it.context}: ${it.message}" }
        val file = IcsFolding.fold(calendar.toString())
        val all = problems + InviteRules.problems(calendar, invite.kind) + IcsFolding.problems(file)
        if (all.isNotEmpty()) throw InviteInvalidException(all)
        return file.toByteArray(Charsets.UTF_8)
    }

    private fun calendar(invite: Invite): Calendar {
        val event = invite.event
        val refusal =
            when {
                event.sequence < 0 -> "negative sequence"
                !event.endsAt.isAfter(event.startsAt) -> "the meeting ends before it starts"
                event.zone is ZoneOffset -> "not a region time zone"
                else -> null
            }
        if (refusal != null) throw InviteRefusedException(refusal)
        val meetingUrl = InviteInput.link(event.meetingUrl)
        val joinUrl = event.joinUrl?.let(InviteInput::link)
        return Calendar().apply {
            add<Calendar>(ProdId(PRODID))
            add<Calendar>(Version(ParameterList(), Version.VALUE_2_0))
            add<Calendar>(CalScale(CalScale.VALUE_GREGORIAN))
            add<Calendar>(Method(invite.kind.method))
            add<Calendar>(VTimezones.of(event.zone, event.startsAt))
            add<Calendar>(vevent(invite, meetingUrl.toString(), joinUrl?.toString()))
        }
    }

    private fun vevent(
        invite: Invite,
        meetingUrl: String,
        joinUrl: String?,
    ): VEvent {
        val event = invite.event
        val properties =
            listOfNotNull(
                Uid(event.uid.toString()),
                Sequence(event.sequence),
                DtStamp(clock.instant().truncatedTo(ChronoUnit.SECONDS)),
                DtStart(zoned(event), local(event.startsAt, event)),
                DtEnd(zoned(event), local(event.endsAt, event)),
                Summary(InviteInput.line(event.summary).ifEmpty { "-" }),
                texts.location(event, invite.locale)?.let(::Location),
                organizer(invite.organizer),
                invite.attendee?.takeIf { invite.kind != InviteKind.PUBLISH }?.let { attendee(it, invite.guest) },
                Description(texts.description(invite.kind, event, meetingUrl, joinUrl, invite.locale)),
                Url(java.net.URI(meetingUrl)),
                status(invite.kind),
                Clazz(Clazz.VALUE_PRIVATE).takeIf { event.confidential },
            )
        return VEvent(false).apply { properties.forEach { add<VEvent>(it) } }
    }

    /**
     * The local time as `java.time` computes it, written with the TZID parameter. Given a `ZonedDateTime`, ical4j would
     * re-express the time in its own bundled definition of the zone, which can be stale: the E05 T1 spike found it
     * writing 11:00 for a 10:00 meeting in America/Sao_Paulo (Brazil ended daylight saving in 2019).
     */
    private fun local(
        instant: java.time.Instant,
        event: InviteEvent,
    ): LocalDateTime = LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.SECONDS), event.zone)

    private fun zoned(event: InviteEvent) = ParameterList(listOf(TzId(event.zone.id)))

    private fun organizer(party: InviteParty): Property =
        Organizer(InviteInput.mailto(party.email)).apply {
            InviteInput.name(party.name).takeIf { it.isNotEmpty() }?.let { add<Property>(Cn(it)) }
        }

    /** The recipient alone, asked for no calendar reply (`RSVP=FALSE`): answers are given in memo (ADR-0063). */
    private fun attendee(
        party: InviteParty,
        guest: Boolean,
    ): Property =
        Attendee(InviteInput.mailto(party.email)).apply {
            InviteInput.name(party.name).takeIf { it.isNotEmpty() }?.let { add<Property>(Cn(it)) }
            add<Property>(if (guest) Role.OPT_PARTICIPANT else Role.REQ_PARTICIPANT)
            add<Property>(PartStat.NEEDS_ACTION)
            add<Property>(Rsvp.FALSE)
        }

    /** CONFIRMED, CANCELLED for the whole meeting, and none when one person is uninvited (RFC 5546 §3.2.5). */
    private fun status(kind: InviteKind): Status? =
        when (kind) {
            InviteKind.REQUEST, InviteKind.PUBLISH -> Status(Status.VALUE_CONFIRMED)
            InviteKind.CANCEL -> Status(Status.VALUE_CANCELLED)
            InviteKind.UNINVITE -> null
        }

    companion object {
        const val PRODID = "-//memo//meetings//EN"
    }
}
