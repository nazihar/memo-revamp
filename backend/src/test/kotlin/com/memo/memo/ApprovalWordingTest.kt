package com.memo.memo

import com.memo.testsupport.repoRoot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.io.path.readText

/**
 * ADR-0051: what users read says "authenticated approval", never "digital signature" or "e-signature". Under the ICT
 * Act 2006 a digital signature is a certificate-based signature (research §B.5); our approvals are not that.
 */
class ApprovalWordingTest {
    private val catalogues =
        listOf(
            "web/src/i18n/messages/en.json",
            "web/src/i18n/messages/ar.json",
            "backend/src/main/resources/notification/messages.properties",
            "backend/src/main/resources/notification/messages_ar.properties",
        )
    private val forbidden =
        listOf(
            Regex("""digital\s+signature""", RegexOption.IGNORE_CASE),
            Regex("""e-?signature""", RegexOption.IGNORE_CASE),
            Regex("""توقيع\s+(رقمي|إلكتروني)"""),
        )

    @Test
    fun `no user-facing text calls an approval a digital signature`() {
        catalogues.forEach { path ->
            val text = repoRoot.resolve(path).readText()
            forbidden.forEach { word -> assertThat(word.containsMatchIn(text)).describedAs("$path: ${word.pattern}").isFalse() }
        }
    }
}
