package com.memo.templating.internal

import com.deepoove.poi.XWPFTemplate
import com.deepoove.poi.config.Configure
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy
import com.deepoove.poi.policy.RenderPolicy
import com.deepoove.poi.render.compute.EnvModel
import com.deepoove.poi.render.compute.RenderDataCompute
import com.deepoove.poi.resolver.DefaultElementTemplateFactory
import com.deepoove.poi.template.run.RunTemplate
import com.memo.templating.FillFailure
import com.memo.templating.TemplateData
import com.memo.templating.TemplateFillException
import com.memo.templating.TemplateFiller
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration
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
 * value fails the fill. Runs on [THREADS] threads per node with a short queue and a [timeout].
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
        val config =
            Configure
                .builder()
                .buildGrammerRegex(NAME_GRAMMAR)
                .setElementTemplateFactory(SectionNames)
                .setRenderDataComputeFactory { env -> Lookup(data, env) }
                .apply { REFUSED_SIGNS.forEach { addPlugin(it, Refuse) } }
                .apply { data.rows.keys.forEach { bind(it, LoopRowTableRenderPolicy()) } }
                .build()
        XWPFTemplate.compile(ByteArrayInputStream(bytes), config).use { template ->
            template.render(Root)
            return ByteArrayOutputStream().also { template.write(it) }.toByteArray()
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
    ) : RenderDataCompute {
        override fun compute(el: String): Any {
            val row = env.root as? Map<*, *>
            val value =
                when {
                    row != null -> row[el]
                    el.startsWith(SECTION) -> data.values[el.substring(1)]?.isNotBlank()
                    else -> data.values[el] ?: data.rows[el]
                }
            return value ?: throw Missing(if (row != null) "[$el]" else "{{$el}}")
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
         * POI's zip limits are JVM-wide statics: entries at most 64 MiB inflated and compressed at most 100 to 1 (the
         * ratio the upload pipeline enforces), so a template that passed upload but is a bomb inside still fails here.
         */
        private const val MAX_ENTRY_BYTES = 64L shl 20
        private const val MIN_INFLATE_RATIO = 0.01

        fun limitZips() {
            ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES)
            ZipSecureFile.setMinInflateRatio(MIN_INFLATE_RATIO)
        }

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
