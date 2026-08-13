package com.unarchive.android.checkpoint

import com.unarchive.android.result.VideoResultKey
import java.security.MessageDigest

object LocalAudioIdentity {
    fun key(uri: String): VideoResultKey {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return VideoResultKey("local", digest)
    }
}
