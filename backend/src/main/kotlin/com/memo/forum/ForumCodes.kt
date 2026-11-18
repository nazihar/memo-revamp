package com.memo.forum

import com.memo.forum.internal.Forums
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * A committee's code as meeting and resolution numbers use it (design E03 §2): editable until the first numbered meeting,
 * locked from then on. Inside a tenant transaction; the caller has decided that a number is being taken.
 */
@Component
class ForumCodes(
    private val forums: Forums,
) {
    /** Locks [forumId]'s code if it is not locked yet and returns it. */
    fun lock(forumId: UUID): String = forums.lockCode(forumId).code
}
