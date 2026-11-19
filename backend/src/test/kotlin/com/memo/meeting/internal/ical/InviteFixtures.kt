package com.memo.meeting.internal.ical

import com.memo.meeting.MeetingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** The meetings of the E05 golden files and acceptance criteria (test plan T1), and a writer on a fixed clock. */
object InviteFixtures {
    val NOW: Instant = Instant.parse("2026-11-18T04:00:00Z")
    val UID: UUID = UUID.fromString("6f1d2c4e-8a3b-4c7d-9e1f-2a3b4c5d6e7f")
    val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")
    val LONDON: ZoneId = ZoneId.of("Europe/London")
    const val MEETING_URL = "https://dev.memo.example/meetings/01936b2e-4c1a-7d3e-9f20-6a1b2c3d4e5f"

    fun writer(clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)) = InviteWriter(clock)

    fun at(
        local: String,
        zone: ZoneId,
    ): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    /** The BRC meeting on 12 Dec 2026, 10:00–13:00 in Dhaka, in the Board Room. */
    val brc =
        InviteEvent(
            uid = UID,
            sequence = 0,
            startsAt = at("2026-12-12T10:00", DHAKA),
            endsAt = at("2026-12-12T13:00", DHAKA),
            zone = DHAKA,
            summary = "Board Risk Committee",
            confidential = false,
            mode = MeetingMode.IN_PERSON,
            location = "Board Room, Level 12, Head Office",
            joinUrl = null,
            joinDetails = null,
            meetingUrl = MEETING_URL,
        )

    val organizer = InviteParty("Board Risk Committee", "meetings@bank.example")
    val director = InviteParty("Demo Director", "director@demo.example")

    fun request(
        event: InviteEvent = brc,
        locale: Locale = Locale.ENGLISH,
    ) = Invite(InviteKind.REQUEST, event, organizer, director, locale = locale)

    fun of(
        kind: InviteKind,
        event: InviteEvent = brc,
    ) = Invite(kind, event, organizer, director.takeIf { kind != InviteKind.PUBLISH })
}
