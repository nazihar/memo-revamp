package com.memo.notification.internal

import com.memo.notification.MailTemplate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.Properties

/** ADR-0030: every template in every language, subjects without parameters, HTML escaped and in the right direction. */
class MailTemplatesTest {
    private val source = MailTextsConfig().mailMessageSource()

    private fun bundle(suffix: String): Properties =
        Properties().apply {
            MailTemplatesTest::class.java.classLoader
                .getResourceAsStream("notification/messages$suffix.properties")!!
                .reader(Charsets.UTF_8)
                .use { load(it) }
        }

    @Test
    fun `every template has a subject and a body in every language, and the languages have the same keys`() {
        val english = bundle("")
        val arabic = bundle("_ar")

        assertThat(arabic.keys).isEqualTo(english.keys)
        MailTemplate.entries.forEach { template ->
            listOf(Locale.ENGLISH, Locale.forLanguageTag("ar")).forEach { locale ->
                assertThat(source.getMessage("mail.${template.name}.subject", null, locale)).isNotBlank()
                assertThat(source.getMessage("mail.${template.name}.body", null, locale)).isNotBlank()
            }
        }
    }

    @Test
    fun `every term has default forms in every language, and a template placeholder takes the tenant's term`() {
        listOf(bundle(""), bundle("_ar")).forEach { messages ->
            com.memo.tenancy.TermKey.entries.forEach { term ->
                assertThat(messages.getProperty("term.${term.key}.one")).describedAs(term.key).isNotBlank()
                assertThat(messages.getProperty("term.${term.key}.other")).describedAs(term.key).isNotBlank()
            }
        }
        assertThat(MailText.render("Your {unit} has new {memos}.", mapOf("unit" to "department", "memos" to "notes")))
            .isEqualTo("Your department has new notes.")
    }

    @Test
    fun `subjects take no parameters, so nothing sensitive can reach them`() {
        listOf(bundle(""), bundle("_ar")).forEach { messages ->
            messages.stringPropertyNames().filter { it.endsWith(".subject") }.forEach {
                assertThat(messages.getProperty(it)).describedAs(it).doesNotContain("{")
            }
        }
    }

    @Test
    fun `the HTML part escapes text, links URLs and follows the language's direction`() {
        val text = MailText.render("Hi {name}, open {link}", mapOf("name" to "<b>Nadia</b>", "link" to "https://a.example/x#t"))

        assertThat(text).isEqualTo("Hi <b>Nadia</b>, open https://a.example/x#t")
        assertThat(
            MailText.html(text),
        ).contains("&lt;b&gt;Nadia&lt;/b&gt;").contains("""<a href="https://a.example/x#t"""").contains("dir=\"ltr\"")
        assertThat(MailText.html("مرحبًا", Locale.forLanguageTag("ar"), "footer")).contains("lang=\"ar\"").contains("dir=\"rtl\"")
    }
}
