package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.workflow.engine.Answer
import com.memo.workflow.engine.Ask
import com.memo.workflow.engine.Claim
import com.memo.workflow.engine.Delegate
import com.memo.workflow.engine.Forward
import com.memo.workflow.engine.GiveOpinion
import com.memo.workflow.engine.Recuse
import com.memo.workflow.engine.Refer
import com.memo.workflow.engine.Release
import com.memo.workflow.engine.SendBack
import com.memo.workflow.internal.WorkflowRunner
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * What a task holder can do besides deciding (design E03 §4.4): send back, forward, delegate, ask and answer, refer and
 * give an opinion. Like [Workflows], every call runs inside the caller's tenant transaction as the signed-in user, and the
 * engine checks that the actor holds the task and that its step offers the action.
 */
@Service
class WorkflowHandOns(
    private val runner: WorkflowRunner,
) {
    /** Sends the item back to an earlier step of this round ([targetStepId], a step the holder may choose). */
    fun sendBack(
        taskId: UUID,
        targetStepId: UUID,
        remark: String?,
    ): WorkflowView = runner.onTask(taskId, currentUser()) { actor -> SendBack(actor, taskId, targetStepId, remark) }

    /** Hands the task to [to], who then holds the step. */
    fun forward(
        taskId: UUID,
        to: UUID,
        remark: String?,
    ): WorkflowView = runner.onTask(taskId, currentUser()) { actor -> Forward(actor, taskId, to, remark) }

    /** Hands the task to [to] to act for the holder. */
    fun delegate(
        taskId: UUID,
        to: UUID,
        remark: String?,
    ): WorkflowView = runner.onTask(taskId, currentUser()) { actor -> Delegate(actor, taskId, to, remark) }

    /** Asks [to] a question ([remark]); the task waits for the answer. */
    fun ask(
        taskId: UUID,
        to: UUID,
        remark: String?,
    ): WorkflowView = runner.onTask(taskId, currentUser()) { actor -> Ask(actor, taskId, to, remark) }

    /** Answers the clarification task [taskId]. */
    fun answer(
        taskId: UUID,
        remark: String?,
    ): WorkflowView = runner.onTask(taskId, currentUser()) { actor -> Answer(actor, taskId, remark) }

    /** Asks [to] for their opinions on what [remark] says. */
    fun refer(
        taskId: UUID,
        to: List<UUID>,
        remark: String?,
    ): WorkflowView = runner.onTask(taskId, currentUser()) { actor -> Refer(actor, taskId, to, remark) }

    /** Gives the opinion the opinion task [taskId] asked for. */
    fun giveOpinion(
        taskId: UUID,
        remark: String?,
    ): WorkflowView = runner.onTask(taskId, currentUser()) { actor -> GiveOpinion(actor, taskId, remark) }

    /** Takes the shared step of [taskId] (design E04 §4.2): the other candidates cannot decide it until it is released. */
    fun claim(taskId: UUID): WorkflowView = runner.onTask(taskId, currentUser()) { actor -> Claim(actor, taskId) }

    /**
     * Gives the claimed step of [taskId] back to all its candidates: by the claimer, or by an administrator when
     * [byAdministrator] (the caller has checked `workflow.tasks.reassign`).
     */
    fun release(
        taskId: UUID,
        byAdministrator: Boolean = false,
    ): WorkflowView = runner.onTask(taskId, currentUser()) { actor -> Release(actor, taskId, byAdministrator) }

    /** The holder declares an interest and stands aside (SOD-4); [remark] is the reason. */
    fun recuse(
        taskId: UUID,
        remark: String?,
    ): WorkflowView = runner.onTask(taskId, currentUser()) { actor -> Recuse(actor, taskId, remark) }

    private fun currentUser(): Actor.User =
        ActorContext.current().actor as? Actor.User ?: throw IllegalStateException("workflow actions need a signed-in user")
}
