package com.unarchive.android.editor

import java.net.URI

object NoteLinkPolicy {
    fun isAllowed(destination: String): Boolean {
        if (destination.isBlank() || destination.any { it.isISOControl() }) return false
        if (destination.startsWith("#")) return destination.length > 1
        val uri = runCatching { URI(destination) }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> !uri.host.isNullOrBlank() && uri.rawUserInfo == null
            "bilibili" -> uri.host == "video" && uri.rawQuery?.contains("bvid=") == true
            else -> false
        }
    }
}
