package com.memo.testsupport

import com.memo.minutes.MinutesActionLine
import com.memo.minutes.MinutesItemActions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The actions a test plants on a meeting's minutes (E06 T7; the port E07 implements): the lines planted for a meeting, or
 * else what the application's own implementation answers (none before E07). In every test context, like the other
 * stand-ins, so a draft filled by any context's scheduler sees the same lines.
 */
@Component
@Primary
class PlantedMinutesActions(
    private val all: ObjectProvider<MinutesItemActions>,
) : MinutesItemActions {
    private val planted = ConcurrentHashMap<UUID, List<MinutesActionLine>>()

    fun plant(
        meetingId: UUID,
        lines: List<MinutesActionLine>,
    ) {
        planted[meetingId] = lines
    }

    override fun of(meetingId: UUID): List<MinutesActionLine> =
        planted[meetingId] ?: all
            .orderedStream()
            .filter { it !== this }
            .findFirst()
            .map { it.of(meetingId) }
            .orElse(emptyList())
}
