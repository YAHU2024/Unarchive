package com.unarchive.android.editor.projection

import com.unarchive.android.editor.session.DocumentProjection

object DocumentTitleResolver {
    fun resolve(projection: DocumentProjection, fallback: String): String = projection.nodes
        .firstOrNull { it.node.headingLevel == 1 && it.node.text.isNotBlank() }
        ?.node?.text?.trim() ?: fallback
}
