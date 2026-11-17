package com.unarchive.android.editor.clipboard

import android.content.ClipData
import android.content.Context
import android.os.PersistableBundle
import android.text.Html
import android.text.Spanned

fun ClipboardNormalizer.fromClipData(context: Context, clip: ClipData?, plainOnly: Boolean = false): ClipboardPayload {
    if (clip == null || clip.itemCount == 0) return ClipboardPayload("", null, "")
    val item = clip.getItemAt(0)
    val text = item.coerceToText(context)
    val html = if (plainOnly) null else item.htmlText ?: (text as? Spanned)?.let { Html.toHtml(it, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL) }
    return normalize(text?.toString(), html, plainOnly)
}

/** Internal metadata is validated and never used as external plain text or HTML. */
fun ClipboardPayload.toClipData(sessionId: String): ClipData {
    val clip = if (htmlText != null) ClipData.newHtmlText("笔记选区", plainText, htmlText) else ClipData.newPlainText("笔记选区", plainText)
    clip.description.extras = PersistableBundle().apply {
        putString("unarchive_fragment_version", "1")
        putString("unarchive_fragment_session", sessionId)
        putString("unarchive_fragment_source", markdown)
        putString("unarchive_fragment_hash", ClipboardNormalizer.fingerprint(this@toClipData))
    }
    return clip
}

fun ClipboardNormalizer.validatedInternalFragment(clip: ClipData, sessionId: String): ClipboardPayload? {
    val extras = clip.description.extras ?: return null
    if (extras.getString("unarchive_fragment_version") != "1" || extras.getString("unarchive_fragment_session") != sessionId || clip.itemCount != 1) return null
    val source = extras.getString("unarchive_fragment_source") ?: return null
    val plain = clip.getItemAt(0).text?.toString() ?: return null
    if ('\uFFFC' in source || '\uFFFC' in plain || source.length > 2 * 1024 * 1024) return null
    val payload = ClipboardPayload(plain, clip.getItemAt(0).htmlText, source)
    return payload.takeIf { fingerprint(it) == extras.getString("unarchive_fragment_hash") }
}
