package com.memo.branding

import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.Files
import com.memo.files.StorageClass
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.UUID

/** The tenant logo (design E07 §6): raster images only (no SVG, which can carry script), small, shown on the sign-in page. */
@Component
class TenantLogoPurpose(
    private val policy: Policy,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes = setOf(FileType.PNG, FileType.JPEG, FileType.WEBP)
    override val maxBytes = MAX_BYTES
    override val storage = StorageClass.ORDINARY
    override val auditReads = false
    override val inlineTypes = allowedTypes

    override fun mayUpload(ownerId: String?): Boolean = ownerId == OWNER && policy.holdsAnywhere(Permission.TENANT_SETTINGS_MANAGE)

    /** Anyone on the tenant's host may see it: it is on the sign-in page. */
    override fun mayRead(file: FileInfo): Boolean = true

    companion object {
        const val KEY = "tenant.logo"
        const val OWNER = "tenant"
        const val MAX_BYTES = 512L * 1024
    }
}

/** What the sign-in page and the shell need to brand the app. */
data class BrandingView(
    val organisation: String,
    val colourLight: String?,
    val colourDark: String?,
    val logoFileId: UUID?,
    val version: Long,
)

/** A colour that misses its contrast minimum, with the measured ratio, for the form to explain. */
data class ContrastProblem(
    val field: String,
    val against: String,
    val ratio: Double,
    val minimum: Double,
)

class BrandingRejectedException(
    val problems: List<ContrastProblem>,
    val invalidFields: List<String>,
) : IllegalArgumentException("branding rejected")

/**
 * Tenant branding (design E07 §6), stored as tenant settings (small, non-secret, audited, cached). Colours replace the
 * primary token and must keep WCAG contrast in their theme; the logo is a `tenant.logo` file. Inside a tenant transaction.
 */
@Service
class Branding(
    private val settings: TenantSettings,
    private val registry: TenantRegistry,
    private val files: Files,
) {
    fun view(): BrandingView =
        BrandingView(
            organisation = registry.current()?.name.orEmpty(),
            colourLight = settings.get(LIGHT)?.value,
            colourDark = settings.get(DARK)?.value,
            logoFileId = settings.get(LOGO)?.value?.let(UUID::fromString),
            version = KEYS.mapNotNull { settings.get(it) }.sumOf { it.version + 1 },
        )

    /** Saves colours and logo; null clears one. Refuses colours below their contrast minimum and files of another purpose. */
    fun update(
        colourLight: String?,
        colourDark: String?,
        logoFileId: UUID?,
    ): BrandingView {
        val invalid = invalidFields(colourLight, colourDark, logoFileId)
        val problems =
            if (invalid.isEmpty()) {
                listOfNotNull(
                    colourLight?.let { checks("colourLight", it, LIGHT_CHECKS) },
                    colourDark?.let { checks("colourDark", it, DARK_CHECKS) },
                ).flatten()
            } else {
                emptyList()
            }
        if (invalid.isNotEmpty() || problems.isNotEmpty()) throw BrandingRejectedException(problems, invalid)
        mapOf(LIGHT to colourLight?.lowercase(), DARK to colourDark?.lowercase(), LOGO to logoFileId?.toString()).forEach(::store)
        return view()
    }

    private fun invalidFields(
        colourLight: String?,
        colourDark: String?,
        logoFileId: UUID?,
    ): List<String> =
        buildList {
            if (colourLight != null && !Contrast.isColour(colourLight)) add("colourLight")
            if (colourDark != null && !Contrast.isColour(colourDark)) add("colourDark")
            if (logoFileId != null && files.info(logoFileId)?.purpose != TenantLogoPurpose.KEY) add("logoFileId")
        }

    /** Writes only a change (the audit shows real changes); null removes the setting. */
    private fun store(
        key: String,
        value: String?,
    ) {
        when (value) {
            settings.get(key)?.value -> Unit
            null -> settings.delete(key)
            else -> settings.put(key, value)
        }
    }

    private fun checks(
        field: String,
        colour: String,
        against: List<Pair<String, Double>>,
    ): List<ContrastProblem> =
        against.mapNotNull { (background, minimum) ->
            Contrast.ratio(colour, background).takeIf { it < minimum }?.let { ContrastProblem(field, background, it, minimum) }
        }

    companion object {
        const val LIGHT = "branding.color.light"
        const val DARK = "branding.color.dark"
        const val LOGO = "branding.logo.file"
        private val KEYS = listOf(LIGHT, DARK, LOGO)

        /** Light theme: white text on buttons (4.5:1) and the page background as a UI component (3:1). */
        val LIGHT_CHECKS = listOf("#ffffff" to TEXT, "#f6f7f9" to UI)

        /** Dark theme: the dark button text (4.5:1) and the dark background (3:1). */
        val DARK_CHECKS = listOf("#0d1726" to TEXT, "#10151d" to UI)
        private const val TEXT = 4.5
        private const val UI = 3.0
    }
}
