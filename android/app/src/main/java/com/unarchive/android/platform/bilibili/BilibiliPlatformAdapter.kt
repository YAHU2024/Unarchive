package com.unarchive.android.platform.bilibili

import com.unarchive.android.platform.VideoPlatformAdapter
import com.unarchive.android.platform.VideoReference
import com.unarchive.android.platform.VideoMetadata
import com.unarchive.android.platform.AudioStream
import com.unarchive.android.platform.VideoStream

class BilibiliPlatformAdapter(
    private val redirectResolver: BilibiliRedirectResolver = BilibiliRedirectResolver(
        HttpsRedirectTransport(),
    ),
    private val api: BilibiliApi = BilibiliApi(HttpsTextTransport()),
) : VideoPlatformAdapter {
    override val platform = "bilibili"

    override fun parseReference(input: String): VideoReference = BilibiliReferenceParser.parse(input)

    override suspend fun resolveReference(input: String): VideoReference.Canonical =
        when (val reference = parseReference(input)) {
            is VideoReference.Canonical -> reference
            is VideoReference.Redirect -> redirectResolver.resolve(reference)
        }

    override suspend fun fetchMetadata(reference: VideoReference.Canonical): VideoMetadata =
        api.fetchMetadata(reference.id)

    override suspend fun resolveAudio(metadata: VideoMetadata): AudioStream =
        api.resolveAudio(metadata)

    override suspend fun resolveVideo(metadata: VideoMetadata): VideoStream =
        api.resolveVideo(metadata)
}
