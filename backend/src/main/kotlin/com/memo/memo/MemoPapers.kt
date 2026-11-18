package com.memo.memo

import com.memo.memo.internal.MemoAttachmentStore
import com.memo.memo.internal.MemoStore
import com.memo.memo.internal.rendition.MemoRenditionQueries
import org.springframework.stereotype.Component
import java.util.UUID

/** Which of a memo's files a paper is (ADR-0061). */
enum class MemoPaperSource {
    /** The memo's document; members read its PDF rendition. */
    DOCUMENT,
    ATTACHMENT,

    /** The memo's final record (ADR-0053), offered instead of the document once one is made. */
    RECORD,
}

/** One file of a memo's pack, with the level that decides who may open it (an attachment's own when stricter). */
data class MemoPaperFile(
    val fileId: UUID,
    val source: MemoPaperSource,
    val title: String,
    val classificationId: UUID,
)

/** What a committee is given of a memo (ADR-0061, design E03 §1.8): its number, subject and level, and its files. */
data class MemoPack(
    val memoId: UUID,
    val reference: String?,
    val subject: String,
    val classificationId: UUID,
    /** The final record or the document first, then the attachments in their order. */
    val files: List<MemoPaperFile>,
)

/**
 * A memo's pack for the meeting module (ADR-0061): the files a submitted memo can put before a committee. Members read
 * these papers, never the memo itself. No access check: the meeting module decides who may place and read papers. Inside
 * a tenant transaction.
 */
@Component
class MemoPapers(
    private val memos: MemoStore,
    private val attachments: MemoAttachmentStore,
    private val records: MemoRenditionQueries,
) {
    /** The pack of [memoId]; null when there is no such memo. */
    fun pack(memoId: UUID): MemoPack? {
        val memo = memos.find(memoId) ?: return null
        val name = memo.reference ?: memo.subject
        val record = records.latestReady(memo.id)
        val document = memo.documentFileId
        val main =
            when {
                record != null -> {
                    MemoPaperFile(
                        record.first,
                        MemoPaperSource.RECORD,
                        "Paper: $name",
                        record.second ?: memo.classificationId,
                    )
                }

                document != null -> {
                    MemoPaperFile(document, MemoPaperSource.DOCUMENT, "Paper: $name", memo.classificationId)
                }

                else -> {
                    null
                }
            }
        val annexes =
            attachments.present(memo.id).map { a ->
                val own = a.classificationId?.takeIf { (a.classificationRank ?: 0) > memo.classificationRank }
                MemoPaperFile(a.fileId, MemoPaperSource.ATTACHMENT, a.title, own ?: memo.classificationId)
            }
        return MemoPack(memo.id, memo.reference, memo.subject, memo.classificationId, listOfNotNull(main) + annexes)
    }
}
