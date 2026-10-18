package com.memo.audit

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import java.util.UUID

/**
 * TESTING_STRATEGY §3.1: any modification, deletion, insertion or reordering of audit entries is detected by the verifier,
 * at the first affected sequence number; truncation leaves a shorter chain that no longer reaches the head.
 */
class AuditChainVerifierProperties {
    private val chain = UUID.fromString("01900000-0000-7000-8000-0000000000cc")

    private fun buildChain(actions: List<String>): List<StoredAuditEntry> {
        var prev = AuditCanonicalForm.genesis(chain)
        return actions.mapIndexed { i, action ->
            val entry =
                AuditEntry(
                    tenantId = chain,
                    seq = i + 1L,
                    occurredAt = Instant.parse("2026-10-18T00:00:00Z").plusSeconds(i.toLong()),
                    actorType = "SYSTEM",
                    actorId = "gen",
                    actingAsId = null,
                    clientIp = null,
                    clientUserAgent = null,
                    correlationId = null,
                    action = action,
                    objectType = "test",
                    objectId = "o$i",
                    before = null,
                    after = mapOf("n" to i.toLong()),
                )
            val canonical = AuditCanonicalForm.encode(entry)
            val hash = AuditCanonicalForm.hash(prev, canonical)
            StoredAuditEntry(entry.seq, canonical, prev, hash, columnsOf(entry)).also { prev = hash }
        }
    }

    private fun columnsOf(e: AuditEntry) =
        AuditColumns(
            e.tenantId,
            e.occurredAt,
            e.actorType,
            e.actorId,
            e.actingAsId,
            e.clientIp,
            e.clientUserAgent,
            e.correlationId,
            e.action,
            e.objectType,
            e.objectId,
        )

    private fun verify(entries: List<StoredAuditEntry>) = AuditChainVerifier.verify(chain, ChainPoint.genesis(chain), entries.asSequence())

    @Provide
    fun chains(): Arbitrary<List<String>> =
        Arbitraries
            .of("a.created", "a.changed", "b.deleted")
            .list()
            .ofMinSize(2)
            .ofMaxSize(40)

    @Provide
    fun chainWithIndex(): Arbitrary<Pair<List<String>, Int>> =
        chains().flatMap { actions -> Arbitraries.integers().between(0, actions.size - 1).map { actions to it } }

    @Property
    fun untouchedChainVerifiesToItsLastEntry(
        @ForAll("chains") actions: List<String>,
    ) {
        val result = verify(buildChain(actions))
        assertThat(result).isInstanceOf(AuditVerification.Ok::class.java)
        assertThat((result as AuditVerification.Ok).last.seq).isEqualTo(actions.size.toLong())
    }

    @Property
    fun modifiedCanonicalTextIsDetectedAtThatEntry(
        @ForAll("chainWithIndex") input: Pair<List<String>, Int>,
    ) {
        val (actions, i) = input
        val entries = buildChain(actions).toMutableList()
        val e = entries[i]
        entries[i] = StoredAuditEntry(e.seq, e.canonical.replace("\"n\":$i", "\"n\":${i + 1000}"), e.prevHash, e.hash, e.columns)
        assertThat(verify(entries)).isEqualTo(AuditVerification.Broken(e.seq, "hash does not match the canonical text"))
    }

    @Property
    fun columnEditedWithoutCanonicalTextIsDetectedAtThatEntry(
        @ForAll("chainWithIndex") input: Pair<List<String>, Int>,
    ) {
        val (actions, i) = input
        val entries = buildChain(actions).toMutableList()
        val e = entries[i]
        entries[i] = StoredAuditEntry(e.seq, e.canonical, e.prevHash, e.hash, e.columns.copy(action = "x.forged"))
        val result = verify(entries)
        assertThat(result).isInstanceOf(AuditVerification.Broken::class.java)
        assertThat((result as AuditVerification.Broken).seq).isEqualTo(e.seq)
    }

    @Property
    fun deletedEntryIsDetectedWhereItIsMissing(
        @ForAll("chainWithIndex") input: Pair<List<String>, Int>,
    ) {
        val (actions, i) = input
        val entries = buildChain(actions).toMutableList()
        val removed = entries.removeAt(i)
        val result = verify(entries)
        if (i == actions.size - 1) {
            // Tail deletion verifies, but no longer reaches the head: the caller compares with the head and the anchor.
            assertThat((result as AuditVerification.Ok).last.seq).isEqualTo(removed.seq - 1)
        } else {
            assertThat((result as AuditVerification.Broken).seq).isEqualTo(removed.seq)
        }
    }

    @Property
    fun swappingTwoAdjacentEntriesIsDetected(
        @ForAll("chainWithIndex") input: Pair<List<String>, Int>,
    ) {
        val (actions, i) = input
        val entries = buildChain(actions).toMutableList()
        val j = if (i == entries.size - 1) i - 1 else i
        java.util.Collections.swap(entries, j, j + 1)
        val result = verify(entries)
        assertThat((result as AuditVerification.Broken).seq).isEqualTo(j + 1L)
    }

    @Property
    fun insertedForgedEntryIsDetected(
        @ForAll("chainWithIndex") input: Pair<List<String>, Int>,
        @ForAll("forgedHash") forgedHash: ByteArray,
    ) {
        val (actions, i) = input
        val entries = buildChain(actions).toMutableList()
        val at = entries[i]
        entries.add(i, StoredAuditEntry(at.seq, at.canonical, at.prevHash, forgedHash, at.columns))
        val result = verify(entries)
        assertThat(result).isInstanceOf(AuditVerification.Broken::class.java)
        assertThat((result as AuditVerification.Broken).seq).isLessThanOrEqualTo(at.seq + 1)
    }

    @Provide
    fun forgedHash(): Arbitrary<ByteArray> =
        Combinators
            .combine(Arbitraries.bytes().array(ByteArray::class.java).ofSize(32), Arbitraries.just(0))
            .`as` { bytes, _ -> bytes }
}
