package com.memo.testsupport

import com.memo.templating.FillFailure
import com.memo.templating.TemplateData
import com.memo.templating.TemplateFillException
import com.memo.templating.TemplateFiller
import com.memo.templating.internal.PoiTlFiller
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * The real filler, except that a test can make the next fill fail as a timeout would (ADR-0048: the memo is created
 * without a document and offers FILL_FROM_TEMPLATE). Picked up by component scanning in every test context.
 */
@Primary
@Component
internal class TestTemplateFiller(
    private val real: PoiTlFiller,
) : TemplateFiller {
    override fun fill(
        template: InputStream,
        data: TemplateData,
    ): ByteArray {
        next.getAndSet(null)?.let { throw TemplateFillException(it, "test: $it") }
        return real.fill(template, data)
    }

    companion object {
        private val next = AtomicReference<FillFailure?>()

        /** The next fill, in any test context, fails with [failure]. */
        fun failNext(failure: FillFailure) = next.set(failure)
    }
}
