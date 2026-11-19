package com.memo.templating.internal

import com.deepoove.poi.XWPFTemplate
import com.deepoove.poi.config.Configure
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy
import com.deepoove.poi.policy.RenderPolicy
import com.deepoove.poi.render.compute.EnvModel
import com.deepoove.poi.render.compute.RenderDataCompute
import com.deepoove.poi.resolver.DefaultElementTemplateFactory
import com.deepoove.poi.template.run.RunTemplate
import com.memo.common.poi.PoiLimits
import com.memo.templating.FillFailure
import com.memo.templating.TemplateData
import com.memo.templating.TemplateFillException
import com.memo.templating.TemplateFiller
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fills templates with poi-tl (ADR-0048) and none of its expression languages: tags are looked up in a flat map of
 * text by our own [Lookup] (no reflection, no SpEL), picture, table, list and include tags are refused, and a missing
 * value fails the fill. A section over a block repeats per element (ADR-0066), with each element's block wrapped in its
 * content control when it has one. Runs on [THREADS] threads per node with a short queue and a [timeout].
 */
@Component
internal class PoiTlFiller(
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : TemplateFiller,
    DisposableBean {
    private val pool =
        ThreadPoolExecutor(
            THREADS,
            THREADS,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(QUEUE),
            object : ThreadFactory {
                private val count = AtomicInteger()

                override fun newThread(r: Runnable) = Thread(r, "templates-fill-${count.incrementAndGet()}").apply { isDaemon = true }
            },
        )

    init {
        limitZips()
    }

    override fun fill(
        template: InputStream,
        data: TemplateData,
    ): ByteArray {
        val task = submit(bounded(template), data)
        return try {
            task.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            throw TemplateFillException(FillFailure.TIMEOUT, "fill took longer than $timeout", e)
        } catch (e: ExecutionException) {
            throw failure(e.cause ?: e)
        }
    }

    private fun bounded(template: InputStream): ByteArray {
        val bytes = template.readNBytes(MAX_TEMPLATE_BYTES + 1)
        if (bytes.size > MAX_TEMPLATE_BYTES) throw TemplateFillException(FillFailure.INVALID_TEMPLATE, "template too large")
        return bytes
    }

    private fun submit(
        bytes: ByteArray,
        data: TemplateData,
    ) = try {
        pool.submit<ByteArray> { fillNow(bytes, data) }
    } catch (e: RejectedExecutionException) {
        throw TemplateFillException(FillFailure.BUSY, "too many fills running", e)
    }

    private fun fillNow(
        bytes: ByteArray,
        data: TemplateData,
    ): ByteArray {
        val controlled = data.blocks.filterValues { elements -> elements.any { it.control != null } }.keys
        val markers = if (controlled.isEmpty()) null else Markers(UUID.randomUUID().toString())
        val rowNames =
            data.rows.keys +
                data.blocks.values
                    .flatten()
                    .flatMap { it.rows.keys }
        val config =
            Configure
                .builder()
                .buildGrammerRegex(NAME_GRAMMAR)
                .setElementTemplateFactory(SectionNames)
                .setRenderDataComputeFactory { env -> Lookup(data, env, markers) }
                .apply { REFUSED_SIGNS.forEach { addPlugin(it, Refuse) } }
                .apply { rowNames.forEach { bind(it, LoopRowTableRenderPolicy()) } }
                .build()
        val source = if (markers == null) bytes else Markers.mark(bytes, controlled)
        XWPFTemplate.compile(ByteArrayInputStream(source), config).use { template ->
            template.render(Root)
            val filled = ByteArrayOutputStream().also { template.write(it) }.toByteArray()
            return if (markers == null) filled else ControlXml.wrapMarked(filled, markers.start, markers.end)
        }
    }

    /**
     * Where each element of a block with content controls starts and ends (ADR-0066): [mark] puts a paragraph with
     * [START_TAG] first and one with [END_TAG] last inside the block, so each copy poi-tl makes of the block begins and
     * ends with them; they are filled with this fill's random [token] (and the element's tag and title), so no value can
     * pass for one, and [ControlXml.wrapMarked] turns each pair into a control.
     */
    private class Markers(
        token: String,
    ) {
        val start = "$token<"
        val end = "$token>"

        fun startOf(element: TemplateData): String = start + element.control?.let { it.tag + SEPARATOR + it.title }.orEmpty()

        companion object {
            const val SEPARATOR = ' '
            const val START_TAG = "memoControl.start"
            const val END_TAG = "memoControl.end"

            fun mark(
                bytes: ByteArray,
                blocks: Set<String>,
            ): ByteArray =
                XWPFDocument(ByteArrayInputStream(bytes)).use { document ->
                    blocks.forEach { name ->
                        val starts = document.paragraphs.filter { "{{?$name}}" in it.text }
                        val ends = document.paragraphs.filter { "{{/$name}}" in it.text }
                        check(starts.size == 1 && ends.size == 1 && starts[0] != ends[0]) {
                            "a block with content controls needs its start and end each in a paragraph of the body"
                        }
                        starts[0].ctp.newCursor().use { c ->
                            check(c.toNextSibling())
                            document.insertNewParagraph(c).createRun().setText("{{$START_TAG}}")
                        }
                        ends[0].ctp.newCursor().use { c -> document.insertNewParagraph(c).createRun().setText("{{$END_TAG}}") }
                    }
                    ByteArrayOutputStream().also { document.write(it) }.toByteArray()
                }
        }
    }

    override fun destroy() {
        pool.shutdownNow()
    }

    /** The data root; [Lookup] never reads it (tags resolve against [TemplateData] by name). */
    private object Root

    /** A value is missing: a template tag the data does not have. */
    private class Missing(
        tag: String,
    ) : RuntimeException("no value for $tag")

    /** A tag kind we do not offer (picture, table, list, include). */
    private class Refused(
        tag: String,
    ) : RuntimeException("tag not allowed: $tag")

    /**
     * Resolves tag names: in a loop row, the row's fields; a renamed section start or end (`?name`), whether the value
     * is not blank; otherwise the value itself, or the loop's rows. Unknown names fail.
     */
    private class Lookup(
        private val data: TemplateData,
        private val env: EnvModel,
        private val markers: Markers?,
    ) : RenderDataCompute {
        override fun compute(el: String): Any {
            val root = env.root
            val value =
                when (root) {
                    is Map<*, *> -> root[el]
                    is TemplateData -> element(root, el)
                    else -> resolve(data, el) ?: if (el.startsWith(SECTION)) data.blocks[el.substring(1)] else null
                }
            return value ?: throw Missing(if (root is Map<*, *>) "[$el]" else "{{$el}}")
        }

        /** In a block element: its markers, then its own values, sections and loops, then the document's values. */
        private fun element(
            element: TemplateData,
            el: String,
        ): Any? =
            when (el) {
                Markers.START_TAG -> markers?.startOf(element)
                Markers.END_TAG -> markers?.end
                else -> resolve(element, el) ?: data.values[el]
            }

        private fun resolve(
            scope: TemplateData,
            el: String,
        ): Any? =
            if (el.startsWith(SECTION)) {
                scope.values[el.substring(1)]?.isNotBlank()
            } else {
                scope.values[el] ?: scope.rows[el]
            }
    }

    /**
     * Section tags `{{?name}}` and `{{/name}}` resolve to `?name`, so [Lookup] answers them with a flag while `{{name}}`
     * inside the block still gets the text. Both ends are renamed alike, so poi-tl still pairs them.
     */
    private object SectionNames : DefaultElementTemplateFactory() {
        override fun createRunTemplate(
            config: Configure,
            tag: String,
            run: XWPFRun,
        ): RunTemplate =
            super.createRunTemplate(config, tag, run).also { template ->
                val sign = template.sign
                if (sign == '?' || sign == '/') template.tagName = SECTION + template.tagName
            }
    }

    private object Refuse : RenderPolicy {
        override fun render(
            eleTemplate: com.deepoove.poi.template.ElementTemplate,
            data: Any?,
            template: XWPFTemplate,
        ): Unit = throw Refused(eleTemplate.source)
    }

    companion object {
        const val THREADS = 2
        const val QUEUE = 8
        const val MAX_TEMPLATE_BYTES = 10 shl 20
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private const val SECTION = "?"

        /** Tag names poi-tl may match: ASCII, dotted (the scanner reports anything else as malformed). */
        private const val NAME_GRAMMAR = "([A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*)?"
        private val REFUSED_SIGNS = listOf('@', '#', '*', '+')

        /**
         * POI's zip limits are JVM-wide statics, shared with the import's workbooks ([PoiLimits]): a template that passed
         * upload but is a bomb inside still fails here.
         */
        fun limitZips() = PoiLimits.apply()

        /** Maps what went wrong inside poi-tl or POI to a [FillFailure]. */
        fun failure(cause: Throwable): TemplateFillException {
            val chain = generateSequence(cause) { it.cause }.take(MAX_CAUSES).toList()
            val missing = chain.firstOrNull { it is Missing || it is Refused }
            return when {
                cause is TemplateFillException -> cause
                missing != null -> TemplateFillException(FillFailure.MISSING_VALUE, missing.message.orEmpty(), cause)
                else -> TemplateFillException(FillFailure.INVALID_TEMPLATE, cause.javaClass.simpleName, cause)
            }
        }

        private const val MAX_CAUSES = 10
    }
}
