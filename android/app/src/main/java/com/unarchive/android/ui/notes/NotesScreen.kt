package com.unarchive.android.ui.notes

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.unarchive.android.ui.components.RootPage
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.heading
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.unarchive.android.ui.theme.UnarchiveShapes
import androidx.compose.ui.unit.dp
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.result.StoredVideoResult
import com.unarchive.android.ui.components.AppIcon
import com.unarchive.android.ui.components.AppIcons
import com.unarchive.android.ui.components.CupertinoSegmentedControl
import com.unarchive.android.ui.components.LibraryPageHeader
import com.unarchive.android.ui.components.UnarchiveButton
import com.unarchive.android.ui.components.UnarchiveIconButton
import com.unarchive.android.ui.components.UnarchiveTextButton
import com.unarchive.android.ui.state.NoteVersionKey
import com.unarchive.android.ui.state.NotesEvent
import com.unarchive.android.ui.state.NotesFilter
import com.unarchive.android.ui.state.NotesLibraryItem
import com.unarchive.android.ui.state.NotesLibraryItemKind
import com.unarchive.android.ui.state.NotesThumbnailKind
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.buildNotesLibraryItems
import com.unarchive.android.ui.state.forFilter

@Composable
internal fun NotesScreen(
    state: NotesUiState,
    onEvent: (NotesEvent) -> Unit,
    onOpenLegacyNotes: () -> Unit,
    onOpenEditor: (NoteDocument) -> Unit,
    onOpenDestinations: (NoteDocument) -> Unit,
    onOpenCreate: () -> Unit = {},
) {
    var selectedFilter by rememberSaveable { mutableStateOf(NotesFilter.RECENT) }
    val libraryItems = state.libraryItems.ifEmpty {
        buildNotesLibraryItems(state.noteDocuments, state.noteCards, emptyList())
    }
    val visibleItems = libraryItems.forFilter(selectedFilter)

    RootPage(
        title = "笔记",
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag("notes-scroll"),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        actions = { NotesActions(onRefresh = { onEvent(NotesEvent.Refresh) }, onOpenLibrary = onOpenLegacyNotes) },
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
    ) {
        item {
            LibraryPageHeader("笔记", null)
        }
        item {
            CupertinoSegmentedControl(
                selectedIndex = when (selectedFilter) {
                    NotesFilter.RECENT -> 0
                    NotesFilter.NEEDS_ORGANIZING -> 1
                    NotesFilter.SAVED -> 2
                },
                items = listOf("最近", "待整理", "已保存"),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("notes-filter-row"),
                itemModifier = { index ->
                    Modifier.testTag(
                        when (index) {
                            0 -> "notes-filter-recent"
                            1 -> "notes-filter-materials"
                            else -> "notes-filter-saved"
                        },
                    )
                },
                onSelected = { index ->
                    selectedFilter = when (index) {
                        0 -> NotesFilter.RECENT
                        1 -> NotesFilter.NEEDS_ORGANIZING
                        else -> NotesFilter.SAVED
                    }
                },
            )
        }
        if (visibleItems.isEmpty()) {
            item {
                NotesEmptyState(filter = selectedFilter, onAction = {
                    if (selectedFilter == NotesFilter.RECENT) {
                        onOpenCreate()
                    } else {
                        selectedFilter = NotesFilter.RECENT
                    }
                })
            }
        } else {
            visibleItems.groupBy { notesDateGroup(it.updatedAtEpochMs) }.forEach { (date, entries) ->
                item(key = "date-$date") {
                    Text(date, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() })
                }
                itemsIndexed(entries, key = { _, entry -> entry.stableKey }) { index, item ->
                    NotesLibraryCard(
                        item = item,
                        first = index == 0,
                        last = index == entries.lastIndex,
                        canGenerateDraft = state.canGenerateDraft,
                        onOpenEditor = onOpenEditor,
                        onGenerateDraft = { material ->
                            onEvent(NotesEvent.GenerateDraft(material.key.platform, material.key.videoId))
                        },
                        onRetryCover = { document ->
                            onEvent(
                                NotesEvent.RetryCover(
                                    document.cardId.platform,
                                    document.cardId.videoId,
                                    document.generation.cardVersion,
                                ),
                            )
                        },
                        coverRetrying = item.document?.let(::documentKey)?.let { it in state.coverRetryKeys } == true,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotesActions(onRefresh: () -> Unit, onOpenLibrary: () -> Unit) {
    var moreExpanded by rememberSaveable { mutableStateOf(false) }
    UnarchiveIconButton(onClick = onRefresh,
        modifier = Modifier.testTag("notes-refresh").semantics { contentDescription = "刷新笔记" }) {
        AppIcon(AppIcons.Refresh)
    }
    Box {
        UnarchiveIconButton(onClick = { moreExpanded = true }, modifier = Modifier.testTag("notes-more")) {
            AppIcon(AppIcons.More, contentDescription = "更多")
        }
        DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false },
            modifier = Modifier.testTag("notes-more-menu")) {
            DropdownMenuItem(text = { Text("打开完整笔记库") },
                modifier = Modifier.testTag("notes-more-open-library"),
                onClick = { moreExpanded = false; onOpenLibrary() })
        }
    }
}

@Composable
private fun NotesEmptyState(filter: NotesFilter, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 280.dp).testTag("notes-empty-state"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AppIcon(AppIcons.Notes, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when (filter) {
            NotesFilter.RECENT -> "还没有笔记"
            NotesFilter.NEEDS_ORGANIZING -> "没有待整理素材"
            NotesFilter.SAVED -> "还没有已保存笔记"
        }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text("从一段视频开始", color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp))
        UnarchiveTextButton(onAction, Modifier.testTag("notes-empty-action")) {
            Text(if (filter == NotesFilter.RECENT) "去创作" else "查看最近")
        }
    }
}

@Composable
private fun NotesLibraryCard(
    item: NotesLibraryItem,
    first: Boolean,
    last: Boolean,
    canGenerateDraft: Boolean,
    onOpenEditor: (NoteDocument) -> Unit,
    onGenerateDraft: (StoredVideoResult) -> Unit,
    onRetryCover: (NoteDocument) -> Unit,
    coverRetrying: Boolean,
) {
    val document = item.document
    val cardModifier = Modifier
        .fillMaxWidth()
        .then(if (document != null) Modifier.clickable { onOpenEditor(document) } else Modifier)
        .testTag("notes-item-${item.stableKey}")
    Surface(modifier = cardModifier, color = MaterialTheme.colorScheme.surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = if (first) UnarchiveShapes.medium else 0.dp, topEnd = if (first) UnarchiveShapes.medium else 0.dp,
            bottomStart = if (last) UnarchiveShapes.medium else 0.dp, bottomEnd = if (last) UnarchiveShapes.medium else 0.dp)) {
        Column {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NotesThumbnail(item)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (item.summary.isNotBlank()) {
                            Text(
                                item.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        StatusSummary(item)
                        if (item.tags.isNotEmpty() || item.relationCount > 0) {
                            val tagSummary = item.tags.take(3).joinToString(" ") { "#$it" }
                            val relationSummary = item.relationCount.takeIf { it > 0 }?.let { "$it 个关联" }
                            Text(
                                listOfNotNull(tagSummary.takeIf(String::isNotBlank), relationSummary).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (document != null) AppIcon(AppIcons.Chevron, contentDescription = null,
                        modifier = Modifier.align(Alignment.CenterVertically).size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item.coverStatusLabel?.let { coverStatus ->
                    Text(
                        coverStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("notes-cover-status-${item.stableKey}"),
                    )
                }
                if (document != null) {
                    if (item.canRetryCover) {
                        UnarchiveTextButton(
                            enabled = !coverRetrying,
                            onClick = { onRetryCover(document) },
                            modifier = Modifier.testTag("notes-cover-retry-${item.stableKey}"),
                        ) {
                            Text(if (coverRetrying) "封面重试中..." else "重试封面")
                        }
                    }
                } else {
                    val material = requireNotNull(item.material)
                    UnarchiveButton(
                        enabled = canGenerateDraft,
                        onClick = { onGenerateDraft(material) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("notes-generate-${item.stableKey}"),
                    ) {
                        AppIcon(AppIcons.Create)
                        Spacer(Modifier.width(8.dp))
                        Text("生成笔记草稿")
                    }
                }
            }
            if (!last) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun StatusSummary(item: NotesLibraryItem) {
    val source = listOfNotNull(item.ownerName.takeIf(String::isNotBlank), item.durationSeconds.takeIf { it > 0L }?.let(::formatNotesDuration)).joinToString(" · ")
    val compactSource = listOfNotNull("B 站", item.durationSeconds.takeIf { it > 0L }?.let(::formatNotesDuration)).joinToString(" · ")
    val localError = item.localStatusLabel.contains("失败") || item.document?.editing?.lastSaveError != null
    FlowRow(Modifier.fillMaxWidth().testTag("notes-item-status-${item.stableKey}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (source.isNotBlank()) Text(compactSource, modifier = Modifier.semantics { contentDescription = source }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (item.localStatusLabel == "本地已保存") "已保存" else item.localStatusLabel, modifier = Modifier.semantics { contentDescription = item.localStatusLabel }, style = MaterialTheme.typography.bodySmall,
            color = if (localError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        item.destinationSummary?.let { full ->
            Text(full, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { contentDescription = full },
                color = if (listOf("失败", "待重试", "阻断").any(full::contains)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NotesThumbnail(item: NotesLibraryItem) {
    val resolved = androidx.compose.runtime.remember(item.thumbnailCandidates, item.thumbnailPath) {
        val candidates = item.thumbnailCandidates.ifEmpty {
            item.thumbnailPath?.let {
                listOf(com.unarchive.android.ui.state.NotesThumbnailCandidate(it, NotesThumbnailKind.CHAPTER_SCREENSHOT))
            }.orEmpty()
        }
        candidates.firstNotNullOfOrNull { candidate ->
            BitmapFactory.decodeFile(candidate.path)?.let { candidate.kind to it }
        }
    }
    if (resolved != null) {
        val (kind, bitmap) = resolved
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = if (kind == NotesThumbnailKind.COVER) {
                "${item.title} 的视频封面"
            } else {
                "${item.title} 的章节截图"
            },
            modifier = Modifier
                .width(64.dp)
                .height(64.dp)
                .clip(MaterialTheme.shapes.small)
                .testTag("notes-thumbnail-${kind.name.lowercase()}-${item.stableKey}"),
            contentScale = ContentScale.Crop,
        )
    } else if (item.coverStatusLabel != null || item.canRetryCover) {
        Surface(
            modifier = Modifier
                .width(64.dp)
                .height(64.dp)
                .testTag("notes-thumbnail-placeholder-${item.stableKey}"),
            shape = MaterialTheme.shapes.small,
            color = if (item.kind == NotesLibraryItemKind.SAVED_NOTE) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (item.kind == NotesLibraryItemKind.SAVED_NOTE) "笔记" else "素材",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun documentKey(document: NoteDocument): NoteVersionKey =
    NoteVersionKey(document.cardId, document.generation.cardVersion)

private fun formatNotesDuration(durationSeconds: Long): String {
    val hours = durationSeconds / 3_600L
    val minutes = (durationSeconds % 3_600L) / 60L
    val seconds = durationSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
