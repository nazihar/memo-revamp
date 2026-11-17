package com.memo.imports.internal.sheet

import java.io.InputStream
import java.nio.charset.CharacterCodingException

/** What both readers share: the size bound and the catch-all that turns any failure into a batch problem. */
internal object SheetReads {
    private const val MAX_CAUSES = 20

    /** The whole source, at most [maxBytes] (the purpose refuses larger uploads; this keeps the reader safe on its own). */
    fun bounded(
        input: InputStream,
        maxBytes: Long,
    ): ByteArray {
        val bytes = input.readNBytes(Math.toIntExact(maxBytes + 1))
        if (bytes.size > maxBytes) throw SheetStop(SheetProblem(SheetProblemCode.MALFORMED, params = mapOf("limit" to maxBytes)))
        return bytes
    }

    /**
     * Runs a read: a [SheetStop] anywhere in the cause chain becomes its problem, invalid UTF-8 is `NOT_UTF8`, and anything
     * else (POI's and Commons CSV's exceptions, and errors such as a stack overflow in a hostile file) is `MALFORMED`.
     */
    @Suppress("TooGenericExceptionCaught")
    fun guarded(read: () -> SheetResult): SheetResult =
        try {
            read()
        } catch (failure: Throwable) {
            SheetResult.failed(problemOf(failure))
        }

    fun problemOf(failure: Throwable): SheetProblem {
        val chain = generateSequence(failure) { it.cause }.take(MAX_CAUSES).toList()
        val stop = chain.filterIsInstance<SheetStop>().firstOrNull()
        return when {
            stop != null -> stop.problem
            chain.any { it is CharacterCodingException } -> SheetProblem(SheetProblemCode.NOT_UTF8)
            else -> SheetProblem(SheetProblemCode.MALFORMED, params = mapOf("error" to failure.javaClass.simpleName.take(MAX_NAME)))
        }
    }

    inline fun <reified T : Throwable> causeOf(failure: Throwable): T? =
        generateSequence(failure) { it.cause }.take(MAX_CAUSE_DEPTH).filterIsInstance<T>().firstOrNull()

    const val MAX_CAUSE_DEPTH = 20
    private const val MAX_NAME = 100
}
