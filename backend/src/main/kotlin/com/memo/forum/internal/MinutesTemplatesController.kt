package com.memo.forum.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.numbering.NumberFormats
import com.memo.common.numbering.NumberReset
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.forum.MinutesTemplateTags
import com.memo.forum.ResolutionFormats
import com.memo.forum.ResolutionNumbering
import com.memo.org.OrgDirectory
import com.memo.templating.TemplateProblemCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A tag an admin may copy into a minutes template, with what it stands for. */
data class MinutesTemplateTagHelp(
    /** As typed in Word: `{{meeting.date}}`, `{{present}}`, `[name]`, or `{{?items}}…{{/items}}`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val tag: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val label: String,
    /**
     * `VALUE`; `LOOP` (in a table row, its `[fields]` in the row below); `ROW_FIELD`; `BLOCK` (the items block, each end
     * alone in a paragraph of the body); `ITEM_VALUE` (inside the items block only).
     */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: String,
)

/** One problem of a minutes template, where it is and how to fix it. */
data class MinutesTemplateProblemView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val code: TemplateProblemCode,
    /** "Body", "Header 1", "Footer 2", "Footnotes" or "Endnotes". */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val part: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val paragraph: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val excerpt: String,
    val tag: String? = null,
    val suggestion: String? = null,
)

/** Content the template may never carry (ADR-0048), already refused at upload: listed for a file stored before a rule. */
data class MinutesTemplateRefusalView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rule: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val where: String,
)

/** The uploaded template file. */
data class MinutesTemplateFileView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fileId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val versionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sizeBytes: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val uploadedAt: Instant,
)

/** A committee-type version's minutes template (flow AI): the upload, or the built-in one, checked; and the tags. */
data class MinutesTemplateView(
    /** The version's id: the `owner` of an upload through `POST /files?purpose=forum.minutes-template`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val owner: UUID,
    /** Null: the built-in template. */
    val template: MinutesTemplateFileView?,
    /** The tag names it uses, in document order, once each. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val tags: List<String>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val refusals: List<MinutesTemplateRefusalView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val problems: List<MinutesTemplateProblemView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val available: List<MinutesTemplateTagHelp>,
)

data class MinutesTemplatePreviewView(
    /** A file of purpose `forum.minutes-template.preview`; open its PDF rendition. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fileId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val versionId: UUID,
)

/** A resolution numbering to preview, for a committee's code and a meeting number (left out: `BRC` and 3). */
data class ResolutionNumberingPreviewRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val numbering: ResolutionNumbering,
    val committeeCode: String? = null,
    val meetingNumber: Int? = null,
)

/** The live example (flow AI): a number this year ("BRC/2026/014") and the first of the next period, or what is wrong. */
data class ResolutionNumberingPreview(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val samples: List<String>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val problems: List<FieldProblem>,
)

/**
 * The committee type's Minutes tab (design E06 §1.7, §1.8, flow AI): a version's minutes template checked against the
 * minutes vocabulary, its preview with a sample meeting, and the resolution number's live example. Uploads go through
 * `POST /files?purpose=forum.minutes-template&owner=<versionId>` and become the draft's template.
 */
@RestController
@Tag(name = "forum")
class MinutesTemplatesController(
    private val service: MinutesTemplateService,
    private val directory: OrgDirectory,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/api/v1/admin/forum-types/{typeId}/versions/{number}/minutes-template")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE, orAnyOf = [Permission.FORUM_TYPES_APPROVE])
    @Operation(operationId = "getForumTypeMinutesTemplate", summary = "A version's minutes template, checked, and the tags it may use")
    fun get(
        @PathVariable typeId: UUID,
        @PathVariable number: String,
    ): ResponseEntity<MinutesTemplateView> {
        val info = service.info(typeId, versionNumber(number))
        val view =
            MinutesTemplateView(
                owner = info.owner,
                template = info.file?.let { MinutesTemplateFileView(it.fileId, it.id, it.name, it.sizeBytes, it.uploadedAt) },
                tags = info.tags,
                refusals = info.refusals.map { MinutesTemplateRefusalView(it.rule.name, it.where) },
                problems =
                    info.problems.map { p ->
                        MinutesTemplateProblemView(p.code, p.location.part, p.location.paragraph, p.excerpt, p.tag, p.suggestion)
                    },
                available = HELP,
            )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view)
    }

    @PostMapping("/api/v1/admin/forum-types/{typeId}/versions/{number}/minutes-template/preview")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE)
    @Operation(operationId = "previewForumTypeMinutesTemplate", summary = "Fill a draft's minutes template with a sample meeting")
    fun preview(
        @PathVariable typeId: UUID,
        @PathVariable number: String,
    ): MinutesTemplatePreviewView =
        service.preview(typeId, versionNumber(number)).let { (file, version) -> MinutesTemplatePreviewView(file, version) }

    @PostMapping("/api/v1/admin/forum-types/resolution-numbering/preview")
    @RequiresPermission(Permission.FORUM_TYPES_MANAGE, orAnyOf = [Permission.FORUM_TYPES_APPROVE])
    @Operation(operationId = "previewResolutionNumbering", summary = "A resolution number's live example, or what is wrong with it")
    fun numbering(
        @RequestBody body: ResolutionNumberingPreviewRequest,
    ): ResolutionNumberingPreview {
        val code = body.committeeCode?.trim() ?: SAMPLE_CODE
        val meeting = body.meetingNumber ?: SAMPLE_MEETING
        val problems =
            ResolutionFormats.problems(body.numbering, "numbering") +
                listOfNotNull(
                    FieldProblem("committeeCode", "FORMAT", "1 to 10 capital letters, digits or hyphens.").takeUnless {
                        ForumTypeChecks.CODE.matches(code)
                    },
                    FieldProblem("meetingNumber", "RANGE", "1 to $MAX_MEETING.").takeUnless { meeting in 1..MAX_MEETING },
                )
        if (problems.isNotEmpty()) return ResolutionNumberingPreview(emptyList(), problems)
        val today = transactions.readInTenant(TenantContext.current()) { directory.today() }
        val numbering = body.numbering
        return ResolutionNumberingPreview(
            listOf(
                ResolutionFormats.render(numbering, code, meeting, today, ResolutionFormats.EXAMPLE_SEQUENCE),
                ResolutionFormats.render(numbering, code, 1, nextPeriod(numbering, today), 1),
            ),
            emptyList(),
        )
    }

    private companion object {
        const val SAMPLE_CODE = "BRC"
        const val SAMPLE_MEETING = 3
        const val MAX_MEETING = 999_999

        /** The first day of the period after the one [today] is in (today itself when numbers never restart). */
        fun nextPeriod(
            numbering: ResolutionNumbering,
            today: LocalDate,
        ): LocalDate =
            when (numbering.reset) {
                NumberReset.NEVER -> {
                    today
                }

                NumberReset.YEARLY -> {
                    LocalDate.of(today.year + 1, 1, 1)
                }

                NumberReset.FINANCIAL_YEAR -> {
                    LocalDate.of(
                        NumberFormats.financialYear(numbering.fyStartMonth, today).first + 1,
                        numbering.fyStartMonth,
                        1,
                    )
                }
            }

        /** The tags of the minutes vocabulary, in the order of the admin's list. */
        val HELP: List<MinutesTemplateTagHelp> =
            MinutesTemplateTags.MEETING.map { (tag, label) -> MinutesTemplateTagHelp("{{$tag}}", label, "VALUE") } +
                MinutesTemplateTags.LOOPS.flatMap { (loop, fields) ->
                    listOf(MinutesTemplateTagHelp("{{$loop}}", loopLabel(loop), "LOOP")) +
                        fields.map { (field, label) -> MinutesTemplateTagHelp("[$field]", "${loopLabel(loop)}: $label", "ROW_FIELD") }
                } +
                MinutesTemplateTagHelp(
                    "{{?${MinutesTemplateTags.ITEMS}}}…{{/${MinutesTemplateTags.ITEMS}}}",
                    "Each agenda item (each end alone in a paragraph, not in a table)",
                    "BLOCK",
                ) +
                MinutesTemplateTags.ITEM.map { (tag, label) -> MinutesTemplateTagHelp("{{$tag}}", label, "ITEM_VALUE") }

        private fun loopLabel(loop: String): String =
            when (loop) {
                "present" -> "Members present"
                "alternates" -> "Alternates"
                "inAttendance" -> "In attendance"
                "apologies" -> "Apologies"
                "absent" -> "Absent"
                "quorumChanges" -> "Quorum lost or restored"
                "resolutions" -> "Resolutions"
                else -> loop
            }
    }
}

private fun versionNumber(path: String): Int = path.toIntOrNull() ?: throw ForumNotFoundException("forum type version")
