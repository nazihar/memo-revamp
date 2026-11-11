package com.memo.docedit

import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import java.util.UUID

/** How a document is opened in the browser editor (ADR-0037). */
enum class EditMode { EDIT, VIEW }

/**
 * A file purpose whose files may be edited in the browser. [mayEdit] says who may change the file now (for memos: the
 * initiator while the memo is a draft or returned); viewing needs [mayRead]. Saves still go through [mayUpload] and the
 * purpose's version callbacks, like any upload.
 */
interface EditableFilePurpose : FilePurpose {
    fun mayEdit(file: FileInfo): Boolean
}

/**
 * Edit sessions for other modules (ADR-0037). Inside a tenant transaction.
 */
interface DocumentEditing {
    /** Whether editing is configured on this installation (a document server URL and secret are set). */
    val enabled: Boolean

    /** Whether someone has [fileId] open for editing now: a memo is not submitted while its document may still change. */
    fun beingEdited(fileId: UUID): Boolean
}
