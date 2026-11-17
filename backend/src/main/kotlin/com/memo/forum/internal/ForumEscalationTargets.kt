package com.memo.forum.internal

import com.memo.forum.ForumStatus
import com.memo.workflow.EscalationTarget
import com.memo.workflow.EscalationTargets
import org.springframework.stereotype.Component

/** Active forums are what `escalate-to-forum` steps may name (ADR-0056, design E01 §2). */
@Component
class ForumEscalationTargets(
    private val store: ForumStore,
) : EscalationTargets {
    override fun active(keys: Set<String>): Set<String> =
        store
            .all()
            .filter { it.status == ForumStatus.ACTIVE && it.key in keys }
            .map { it.key }
            .toSet()

    override fun list(): List<EscalationTarget> =
        store.all().filter { it.status == ForumStatus.ACTIVE }.map { EscalationTarget(it.key, it.name, it.code) }
}
