package com.unarchive.android.platform.bilibili

import com.unarchive.android.platform.PlatformVideoId

data class BilibiliFavoriteFolder(
    val id: String,
    val title: String,
    val videoCount: Int,
)

data class BilibiliFavoriteVideo(
    val folderId: String,
    val title: String,
    val videoId: PlatformVideoId?,
    val durationSeconds: Long?,
    val author: String,
    val unavailableReason: String? = null,
) {
    val isAvailable: Boolean get() = videoId != null && unavailableReason == null
    val canonicalUrl: String? get() = videoId?.let { "https://www.bilibili.com/video/${it.value}" }
}

class BilibiliFavoritesRepository(private val api: BilibiliApi) {
    suspend fun fetchFolders(): List<BilibiliFavoriteFolder> = api.fetchFavoriteFolders()

    suspend fun fetchVideos(folderId: String): List<BilibiliFavoriteVideo> =
        api.fetchFavoriteVideos(folderId)
}
