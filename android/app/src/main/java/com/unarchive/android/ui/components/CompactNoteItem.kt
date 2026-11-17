package com.unarchive.android.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.state.NotesLibraryItem
import com.unarchive.android.ui.state.NotesThumbnailCandidate
import com.unarchive.android.ui.state.NotesThumbnailKind
import com.unarchive.android.ui.state.compactNotePreview
import com.unarchive.android.ui.theme.LocalMotionPolicy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared content only: callers own the independent-card or date-group surface. */
@Composable
internal fun CompactNoteItem(
    item: NotesLibraryItem,
    coverRetrying: Boolean,
    onOpen: () -> Unit,
    onRetryCover: () -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "notes",
) {
    val title = item.title.trim().replace(Regex("\\s+"), " ")
    val summary = item.summary.compactNotePreview().ifEmpty { "暂无摘要" }
    val source = when (item.document?.cardId?.platform) {
        "bilibili" -> "B 站"
        else -> item.document?.cardId?.platform.orEmpty().trim()
    }
    val duration = item.durationSeconds.takeIf { it > 0L }?.let(::compactDuration)
    val status = item.compactStatus.label
    val footerOptions = listOf(
        listOfNotNull(source.takeIf(String::isNotBlank), duration, status).joinToString(" · "),
        listOfNotNull(source.takeIf(String::isNotBlank), status).joinToString(" · "),
        status,
    ).distinct()
    val fullFooter = footerOptions.first()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "打开笔记", onClick = onOpen)
            .semantics { contentDescription = "$title。$summary。$fullFooter" }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CompactNoteThumbnail(item, coverRetrying, onRetryCover, tagPrefix)
        Column(
            modifier = Modifier.weight(1f).testTag("$tagPrefix-note-text-group-${item.stableKey}"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.testTag("$tagPrefix-note-title-${item.stableKey}")
                    .semantics { hideFromAccessibility() },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary,
                modifier = Modifier.testTag("$tagPrefix-note-summary-${item.stableKey}")
                    .semantics { hideFromAccessibility() },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val footerStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Normal)
            val textMeasurer = rememberTextMeasurer()
            val density = LocalDensity.current
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val availableWidth = with(density) { maxWidth.toPx() }
                val footer = footerOptions.firstOrNull {
                    textMeasurer.measure(it, style = footerStyle, softWrap = false, maxLines = 1)
                        .size.width <= availableWidth
                } ?: status
                val statusColor = if (item.compactStatus.isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                val footerText = buildAnnotatedString {
                    append(footer.removeSuffix(status))
                    withStyle(SpanStyle(color = statusColor)) { append(status) }
                }
                Text(
                    text = footerText,
                    modifier = Modifier.testTag("$tagPrefix-note-footer-${item.stableKey}")
                        .semantics { hideFromAccessibility() },
                    style = footerStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class CompactThumbnail(
    val bitmap: Bitmap? = null,
    val kind: NotesThumbnailKind? = null,
    val resolved: Boolean = false,
)

@Composable
private fun CompactNoteThumbnail(
    item: NotesLibraryItem,
    coverRetrying: Boolean,
    onRetry: () -> Unit,
    tagPrefix: String,
) {
    val targetPixels = with(LocalDensity.current) { 72.dp.roundToPx() }
    val candidates = item.thumbnailCandidates.ifEmpty {
        item.thumbnailPath?.let { listOf(NotesThumbnailCandidate(it, NotesThumbnailKind.CHAPTER_SCREENSHOT)) }
            .orEmpty()
    }.distinct().sortedBy { if (it.kind == NotesThumbnailKind.COVER) 0 else 1 }
    // Completion revision reloads even when the busy edge was coalesced into one frame
    // or a repaired local asset keeps the same filename. New notes start without an image.
    val thumbnail by key(item.stableKey) {
        produceState(
            initialValue = CompactThumbnail(),
            key1 = candidates,
            key2 = listOf(coverRetrying, item.coverReloadRevision, item.canRetryCover, item.coverStatusLabel, targetPixels),
        ) {
            value = withContext(Dispatchers.IO) {
                candidates.firstNotNullOfOrNull { candidate ->
                    decodeCompactThumbnail(candidate.path, targetPixels)?.let {
                        CompactThumbnail(it, candidate.kind, resolved = true)
                    }
                } ?: CompactThumbnail(resolved = true)
            }
        }
    }
    val expectsCover = item.coverRetrySupported && item.document?.cardId?.platform == "bilibili"
    val coverFailed = expectsCover && thumbnail.kind != NotesThumbnailKind.COVER &&
        (thumbnail.resolved || item.canRetryCover || item.coverStatusLabel != null)
    Box(
        modifier = Modifier.size(72.dp)
            .testTag("$tagPrefix-note-cover-bounds-${item.stableKey}")
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail.bitmap != null) {
            Image(
                bitmap = requireNotNull(thumbnail.bitmap).asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
                    .testTag("$tagPrefix-thumbnail-${requireNotNull(thumbnail.kind).name.lowercase()}-${item.stableKey}"),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize()
                    .testTag("$tagPrefix-thumbnail-placeholder-${item.stableKey}"),
                contentAlignment = Alignment.Center,
            ) {
                if (!coverFailed) AppIcon(
                    AppIcons.Notes,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (coverFailed) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.58f))
                    .testTag("$tagPrefix-cover-status-${item.stableKey}"),
                contentAlignment = Alignment.Center,
            ) {
                // Disabled nested buttons otherwise let a tap reach the parent row.
                // Consume this exact 48dp region while busy without swallowing scrolling.
                Box(
                    Modifier.size(48.dp).pointerInput(coverRetrying) {
                        if (coverRetrying) awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            waitForUpOrCancellation()?.consume()
                        }
                    },
                ) {
                    UnarchiveIconButton(
                        onClick = onRetry,
                        enabled = !coverRetrying,
                        modifier = Modifier.testTag("$tagPrefix-cover-retry-${item.stableKey}")
                            .semantics {
                                contentDescription = "重试封面，${item.title}"
                                stateDescription = if (coverRetrying) "封面重试中" else "封面未正常加载"
                                liveRegion = LiveRegionMode.Polite
                            },
                    ) {
                        if (coverRetrying && LocalMotionPolicy.current.animateLayout) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            AppIcon(AppIcons.Refresh, modifier = Modifier.size(24.dp),
                                tint = Color.White.copy(alpha = if (coverRetrying) 0.5f else 1f))
                        }
                    }
                }
            }
        }
    }
}

private fun decodeCompactThumbnail(path: String, targetPixels: Int): Bitmap? {
    if (!File(path).isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPixels && bounds.outHeight / (sample * 2) >= targetPixels) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun compactDuration(seconds: Long): String {
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainder = seconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, remainder)
    else "%02d:%02d".format(minutes, remainder)
}
