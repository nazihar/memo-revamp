package com.memo.forum.internal

import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumRole
import com.memo.forum.ForumStatus
import com.memo.forum.MemberAttribute
import com.memo.forum.MemoResumes
import com.memo.forum.Outcome
import com.memo.forum.QuorumOverride
import com.memo.forum.QuorumRule
import com.memo.forum.SubmissionPolicy
import com.memo.forum.TermPolicy
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

data class ForumRequest(
    /** Lower case; workflow definitions name it; fixed once created. */
    val key: String,
    /** 1 to 10 capital letters, digits or hyphens, for meeting and resolution numbers. */
    val code: String,
    val name: String,
    val shortName: String,
    /** Fixed once created. */
    val typeId: UUID,
    val parentId: UUID? = null,
    /** Null: the type's default. */
    val classificationId: UUID? = null,
)

data class DissolveRequest(
    val dissolvedOn: LocalDate? = null,
    val reason: String,
)

data class CloneRequest(
    val key: String,
    val code: String,
    val name: String,
    val shortName: String,
    val withMembers: Boolean = false,
    /** The day copied members start; null: today. */
    val startsOn: LocalDate? = null,
)

data class MemberRequest(
    val userId: UUID? = null,
    val positionId: UUID? = null,
    val role: ForumRole = ForumRole.MEMBER,
    val voting: Boolean? = null,
    val attributes: Set<String> = emptySet(),
    val alternateUserId: UUID? = null,
    val startsOn: LocalDate? = null,
    val termEndsOn: LocalDate? = null,
    val authority: String? = null,
)

data class MemberEditRequest(
    val voting: Boolean,
    val attributes: Set<String> = emptySet(),
    val alternateUserId: UUID? = null,
    val termEndsOn: LocalDate? = null,
    val authority: String? = null,
)

data class EndMemberRequest(
    val endedOn: LocalDate? = null,
    /** RESIGNED, TERM_COMPLETED, REMOVED or OTHER. */
    val reason: EndReason,
    val note: String? = null,
)

data class RenewRequest(
    val termEndsOn: LocalDate? = null,
    val authority: String? = null,
)

data class ChangeRoleRequest(
    val role: ForumRole,
    /** The first day in the new role; null: today. */
    val from: LocalDate? = null,
    val voting: Boolean? = null,
)

data class ForumResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val key: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val code: String,
    /** Whether the code is locked: it is in a meeting number and no longer changes. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val codeLocked: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val shortName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val typeId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val typeName: String,
    val parentId: UUID?,
    val parentName: String?,
    val classificationId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: ForumStatus,
    val dissolvedOn: LocalDate?,
    val dissolvedReason: String?,
    /** The chair in office today, if any. */
    val chair: PersonRef?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val secretaries: List<PersonRef>,
    /** Members in office today. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memberCount: Int,
    /** Rows in office whose term ends within the type's longest reminder period, or has ended without renewal. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val termWarnings: Int,
    /** The actor's role today, if they sit on it. */
    val myRole: ForumRole?,
    /** Whether the actor may add, change and end members (administrator or secretary). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canManageMembers: Boolean,
    /** Whether the actor may edit, dissolve and clone it. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canManage: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
)

data class MemberResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: MemberKind,
    /** The person: the member, or the seat's holder on the date asked (absent when the seat is vacant). */
    val person: PersonRef?,
    val positionId: UUID?,
    val positionTitle: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val role: ForumRole,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val voting: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attributes: List<String>,
    val alternate: PersonRef?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val startsOn: LocalDate,
    val termEndsOn: LocalDate?,
    val endedOn: LocalDate?,
    val endReason: EndReason?,
    val endNote: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val termNumber: Int,
    val authority: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: MemberState,
    /** Days until the planned term end, when it is within the type's longest reminder period. */
    val termEndsInDays: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
)

data class MemberChangeResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val member: MemberResponse,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val beyondMaxTerms: Boolean,
)

/** The committee type's published policy as the committee's members read it (flow K2), with the quorum on a date. */
data class ForumPolicyResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val typeId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val typeName: String,
    /** The type's published version. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Int,
    val description: String?,
    /** The classification a committee of this type has unless it sets its own. */
    val defaultClassificationId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memberAttributes: List<MemberAttribute>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val quorum: QuorumRule,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcomes: List<Outcome>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val termPolicy: TermPolicy,
    /** Papers for meetings (ADR-0059): the deadline, the late-paper rule and the triage reminder. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val submissionPolicy: SubmissionPolicy,
    /** On the date asked: the members who count towards quorum, or the fixed seats. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val quorumBasis: Int,
    /** On the date asked: how many of them must attend. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val quorumNeeded: Int,
    /** Whether a decision may be recorded without a quorum, with a reason (E05): the decision screen offers it (E06 T10). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val quorumOverride: QuorumOverride,
    /** When a memo moves on after the committee decides (ADR-0065): said before the meeting is marked as held (E06 T10). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memoResumes: MemoResumes,
)

data class MyForumResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val code: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: ForumStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val role: ForumRole,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val voting: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attributes: List<String>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: MemberState,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val startsOn: LocalDate,
    val termEndsOn: LocalDate?,
    val endedOn: LocalDate?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val termNumber: Int,
    /** Whether the committee page opens for the user (current or upcoming member); former members see only this card. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canOpen: Boolean,
)

/**
 * Committees (ADR-0056, design E01 §3). Lists show only what the actor may see; an invisible committee is 404. Creating
 * rows needs an `Idempotency-Key`; edits need `If-Match`. Members are in [ForumMembersController].
 */
@RestController
@Tag(name = "forum")
class ForumsController(
    private val forums: Forums,
    private val views: ForumViews,
    private val policies: ForumPolicies,
    private val transactions: TenantTransactions,
    private val idempotency: Idempotency,
    private val json: JsonMapper,
) {
    @GetMapping("/api/v1/forums")
    @SignedIn
    @Operation(operationId = "listForums", summary = "The committees the user may see")
    fun list(): ResponseEntity<List<ForumResponse>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read { views.forums(forums.visible()) })

    @PostMapping("/api/v1/forums")
    @SignedIn
    @Operation(operationId = "createForum", summary = "Create a committee (forums.manage)")
    fun create(
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ForumRequest,
    ): ResponseEntity<ForumResponse> {
        val created =
            write {
                val request = fingerprint(json.writeValueAsString(body))
                val id = idempotency.once("forum.create", key, request) { forums.create(body.input()).id.toString() }
                views.forum(forums.get(UUID.fromString(id)))
            }
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(created.version)).body(created)
    }

    @GetMapping("/api/v1/forums/{forumId}")
    @SignedIn
    @Operation(operationId = "getForum", summary = "One committee")
    fun get(
        @PathVariable forumId: UUID,
    ): ResponseEntity<ForumResponse> {
        val forum = read { views.forum(forums.get(forumId)) }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(forum.version))
            .body(forum)
    }

    @PatchMapping("/api/v1/forums/{forumId}")
    @SignedIn
    @Operation(operationId = "updateForum", summary = "Change a committee's name, code, parent or classification")
    fun update(
        @PathVariable forumId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: ForumRequest,
    ): ResponseEntity<ForumResponse> {
        val version = ifMatchVersion(ifMatch)
        val forum = write { views.forum(forums.update(forumId, version, body.input())) }
        return ResponseEntity.ok().eTag(etagOf(forum.version)).body(forum)
    }

    @PostMapping("/api/v1/forums/{forumId}/dissolve")
    @SignedIn
    @Operation(operationId = "dissolveForum", summary = "Dissolve a committee; its history stays")
    fun dissolve(
        @PathVariable forumId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: DissolveRequest,
    ): ForumResponse {
        val version = ifMatchVersion(ifMatch)
        return write { views.forum(forums.dissolve(forumId, version, body.dissolvedOn, body.reason)) }
    }

    @PostMapping("/api/v1/forums/{forumId}/clone")
    @SignedIn
    @Operation(operationId = "cloneForum", summary = "A new committee with this one's type and settings, and its members if asked")
    fun clone(
        @PathVariable forumId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: CloneRequest,
    ): ResponseEntity<ForumResponse> {
        val created =
            write {
                val request = fingerprint("$forumId\n${json.writeValueAsString(body)}")
                val input = CloneInput(body.key, body.code, body.name, body.shortName, body.withMembers, body.startsOn)
                val id = idempotency.once("forum.clone", key, request) { forums.clone(forumId, input).id.toString() }
                views.forum(forums.get(UUID.fromString(id)))
            }
        return ResponseEntity.status(HttpStatus.CREATED).body(created)
    }

    @GetMapping("/api/v1/forums/{forumId}/policy")
    @SignedIn
    @Operation(operationId = "getForumPolicy", summary = "The committee type's published policy, with the quorum on a date (default today)")
    fun policy(
        @PathVariable forumId: UUID,
        @RequestParam(required = false) on: LocalDate?,
    ): ResponseEntity<ForumPolicyResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read { policies.policy(forums.get(forumId), on) })

    @GetMapping("/api/v1/me/forums")
    @SignedIn
    @Operation(operationId = "listMyForums", summary = "The committees the user sits on, will sit on, or sat on")
    fun mine(): ResponseEntity<List<MyForumResponse>> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read { views.mine() })

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    private fun <T> write(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}

/**
 * Members of a committee (ADR-0056, design E01 §3): read by whoever sees the committee; added, changed, renewed, re-roled,
 * ended and withdrawn by administrators or, within their limits, its secretary. Adding needs an `Idempotency-Key`;
 * edits and ends need `If-Match`.
 */
@RestController
@Tag(name = "forum")
class ForumMembersController(
    private val forums: Forums,
    private val members: ForumMembers,
    private val views: ForumViews,
    private val transactions: TenantTransactions,
    private val idempotency: Idempotency,
    private val json: JsonMapper,
) {
    @GetMapping("/api/v1/forums/{forumId}/members")
    @SignedIn
    @Operation(operationId = "listForumMembers", summary = "Members in office on a date (default today), or every row with history")
    fun members(
        @PathVariable forumId: UUID,
        @RequestParam(required = false) on: LocalDate?,
        @RequestParam(required = false, defaultValue = "false") history: Boolean,
    ): ResponseEntity<List<MemberResponse>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read { views.members(forums.get(forumId), on, history) })

    @PostMapping("/api/v1/forums/{forumId}/members")
    @SignedIn
    @Operation(operationId = "addForumMember", summary = "Add a person or a position to a committee")
    fun add(
        @PathVariable forumId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: MemberRequest,
    ): ResponseEntity<MemberChangeResponse> {
        val change =
            write {
                val request = fingerprint("$forumId\n${json.writeValueAsString(body)}")
                val id =
                    idempotency.once("forum.member.add", key, request) {
                        members
                            .add(forumId, body.input())
                            .row.id
                            .toString()
                    }
                views.change(forums.get(forumId), MemberChange(views.row(forumId, UUID.fromString(id))))
            }
        return ResponseEntity.status(HttpStatus.CREATED).body(change)
    }

    @PatchMapping("/api/v1/forums/{forumId}/members/{memberId}")
    @SignedIn
    @Operation(operationId = "editForumMember", summary = "Change a member's vote, attributes, alternate, term end or authority")
    fun edit(
        @PathVariable forumId: UUID,
        @PathVariable memberId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: MemberEditRequest,
    ): MemberChangeResponse {
        val version = ifMatchVersion(ifMatch)
        val edit = MemberEdit(body.voting, body.attributes, body.alternateUserId, body.termEndsOn, body.authority)
        return write { views.change(forums.get(forumId), members.edit(forumId, memberId, version, edit)) }
    }

    @PostMapping("/api/v1/forums/{forumId}/members/{memberId}/end")
    @SignedIn
    @Operation(operationId = "endForumMember", summary = "End a membership on a date, with a reason")
    fun end(
        @PathVariable forumId: UUID,
        @PathVariable memberId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: EndMemberRequest,
    ): MemberChangeResponse {
        val version = ifMatchVersion(ifMatch)
        return write {
            val change = members.end(forumId, memberId, version, body.endedOn, body.reason, body.note)
            views.change(forums.get(forumId), change)
        }
    }

    @PostMapping("/api/v1/forums/{forumId}/members/{memberId}/renew")
    @SignedIn
    @Operation(operationId = "renewForumMember", summary = "Start the member's next term the day after this one ends")
    fun renew(
        @PathVariable forumId: UUID,
        @PathVariable memberId: UUID,
        @RequestBody(required = false) body: RenewRequest?,
    ): MemberChangeResponse =
        write { views.change(forums.get(forumId), members.renew(forumId, memberId, body?.termEndsOn, body?.authority)) }

    @PostMapping("/api/v1/forums/{forumId}/members/{memberId}/change-role")
    @SignedIn
    @Operation(operationId = "changeForumMemberRole", summary = "Give a member another role from a date; the term continues")
    fun changeRole(
        @PathVariable forumId: UUID,
        @PathVariable memberId: UUID,
        @RequestBody body: ChangeRoleRequest,
    ): MemberChangeResponse =
        write { views.change(forums.get(forumId), members.changeRole(forumId, memberId, body.role, body.from, body.voting)) }

    @PostMapping("/api/v1/forums/{forumId}/members/{memberId}/withdraw")
    @SignedIn
    @Operation(operationId = "withdrawForumMember", summary = "Withdraw a membership that has not started")
    fun withdraw(
        @PathVariable forumId: UUID,
        @PathVariable memberId: UUID,
    ): MemberChangeResponse = write { views.change(forums.get(forumId), members.withdraw(forumId, memberId)) }

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    private fun <T> write(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}

private fun ForumRequest.input() = ForumInput(key, code, name, shortName, typeId, parentId, classificationId)

private fun MemberRequest.input() =
    MemberInput(userId, positionId, role, voting, attributes, alternateUserId, startsOn, termEndsOn, authority)
