package com.unarchive.android.editor.projection

import com.unarchive.android.editor.session.DocumentProjection
import com.unarchive.android.editor.session.ImmutableDocumentSnapshot

/** Read and edit share exactly the same projection; mode does not reparse or replace the buffer. */
data class ReadingProjection(val document: DocumentProjection, val title: String, val version: Long) {
    companion object {
        fun from(snapshot: ImmutableDocumentSnapshot, fallbackTitle: String): ReadingProjection = ReadingProjection(
            snapshot.projection, DocumentTitleResolver.resolve(snapshot.projection, fallbackTitle), snapshot.version)
    }
}
