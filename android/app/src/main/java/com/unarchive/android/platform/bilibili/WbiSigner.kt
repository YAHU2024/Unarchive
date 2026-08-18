package com.unarchive.android.platform.bilibili

import java.security.MessageDigest

/**
 * Pure Kotlin implementation of Bilibili's WBI request signing.
 *
 * Mirrors the front-end `encWbi` algorithm exactly:
 *  1. sort keys, filter the `!'()*` characters from every value,
 *  2. build the query string with `encodeURIComponent` semantics
 *     (space -> `%20`, NOT `+` — `URLEncoder` is wrong here and breaks
 *     the signature),
 *  3. `w_rid = md5(query + mixin_key)`.
 */
object WbiSigner {
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )

    /** Derives the 32-char mixin key from the nav `img_key` + `sub_key`. */
    fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        return MIXIN_KEY_ENC_TAB
            .take(raw.length)
            .joinToString("") { raw[it].toString() }
            .take(32)
    }

    /**
     * Returns the fully signed query string (sorted, `encodeURIComponent`
     * encoded, plus `wts` and `w_rid`), ready to append after `?...` on the
     * request URL.
     */
    fun sign(params: Map<String, String>, mixinKey: String, wts: Long): String {
        val withWts = params + ("wts" to wts.toString())
        val sortedKeys = withWts.keys.sorted()
        val query = sortedKeys.joinToString("&") { key ->
            val value = filterValue(withWts.getValue(key))
            "${encodeURIComponent(key)}=${encodeURIComponent(value)}"
        }
        val wRid = md5Hex(query + mixinKey)
        return "$query&w_rid=$wRid"
    }

    private fun filterValue(value: String): String =
        value.filterNot { it in "!'()*" }

    /** `encodeURIComponent`-equivalent: leaves `A-Z a-z 0-9 - _ . ! ~ * ' ( )` intact. */
    private fun encodeURIComponent(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.!~*'()") {
                sb.append(ch)
            } else {
                for (byte in ch.toString().toByteArray(Charsets.UTF_8)) {
                    sb.append('%')
                    sb.append(HEX_UPPER[(byte.toInt() shr 4) and 0x0F])
                    sb.append(HEX_UPPER[byte.toInt() and 0x0F])
                }
            }
        }
        return sb.toString()
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) {
            sb.append(HEX_LOWER[(byte.toInt() shr 4) and 0x0F])
            sb.append(HEX_LOWER[byte.toInt() and 0x0F])
        }
        return sb.toString()
    }

    // `encodeURIComponent` emits uppercase percent-escapes; md5 hexdigest is lowercase.
    private const val HEX_UPPER = "0123456789ABCDEF"
    private const val HEX_LOWER = "0123456789abcdef"
}
