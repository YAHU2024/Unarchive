package com.unarchive.android.ui.notes

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.result.StoredVideoResult
import com.unarchive.android.ui.components.GlassSurface
import com.unarchive.android.ui.state.NoteVersionKey
import com.unarchive.android.ui.state.NotesEvent
import com.unarchive.android.ui.state.NotesFilter
import com.unarchive.android.ui.state.NotesLibraryItem
import com.unarchive.android.ui.state.NotesLibraryItemKind
import com.unarchive.android.ui.state.NotesThumbnailKind
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.buildNotesLibraryItems
import com.unarchive.android.ui.state.forFilter
import com.unarchive.android.ui.state.formatNotesUpdatedAt

@Composable
internal fun NotesScreen(
    state: NotesUiState,
    onEvent: (NotesEvent) -> Unit,
    onOpenLegacyNotes: () -> Unit,
    onOpenEditor: (NoteDocument) -> Unit,
    onOpenDestinations: (NoteDocument) -> Unit,
) {
    var selectedFilter by rememberSaveable { mutableStateOf(NotesFilter.RECENT) }
    val libraryItems = state.libraryItems.ifEmpty {
        buildNotesLibraryItems(state.noteDocuments, state.noteCards, emptyList())
    }
    val visibleItems = libraryItems.forFilter(selectedFilter)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag("notes-scroll"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp),
    ) {
        item {
            NotesHeader(
                noteCount = state.noteCount,
                materialCount = state.materialCount,
                onRefresh = { onEvent(NotesEvent.Refresh) },
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("notes-filter-row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill(
                    label = "最近",
                    selected = selectedFilter == NotesFilter.RECENT,
                    testTag = "notes-filter-recent",
                    onClick = { selectedFilter = NotesFilter.RECENT },
                )
                FilterPill(
                    label = "待整理",
                    selected = selectedFilter == NotesFilter.NEEDS_ORGANIZING,
                    testTag = "notes-filter-materials",
                    onClick = { selectedFilter = NotesFilter.NEEDS_ORGANIZING },
                )
                FilterPill(
                    label = "已保存",
                    selected = selectedFilter == NotesFilter.SAVED,
                    testTag = "notes-filter-saved",
                    onClick = { selectedFilter = NotesFilter.SAVED },
                )
            }
        }
        if (visibleItems.isEmpty()) {
            item {
                NotesEmptyState(filter = selectedFilter)
            }
        } else {
            items(visibleItems, key = NotesLibraryItem::stableKey) { item ->
                NotesLibraryCard(
                    item = item,
                    canGenerateDraft = state.canGenerateDraft,
                    onOpenEditor = onOpenEditor,
                    onOpenDestinations = onOpenDestinations,
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
        item {
            OutlinedButton(
                onClick = {
                    onEvent(NotesEvent.Refresh)
                    onOpenLegacyNotes()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("notes-open-full-library"),
            ) {
                Text("打开完整笔记库")
            }
        }
    }
}

@Composable
private fun NotesHeader(
    noteCount: Int,
    materialCount: Int,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("笔记", style = MaterialTheme.typography.headlineLarge)
            Text(
                "$noteCount 篇已保存 · $materialCount 项待整理",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onRefresh,
            modifier = Modifier
                .testTag("notes-refresh")
                .semantics { contentDescription = "刷新笔记" },
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
        }
    }
}

@Composable
private fun NotesEmptyState(filter: NotesFilter) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("notes-empty-state"),
        emphasized = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (filter) {
                    NotesFilter.RECENT -> "还没有知识笔记"
                    NotesFilter.NEEDS_ORGANIZING -> "没有待整理素材"
                    NotesFilter.SAVED -> "还没有已保存笔记"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                when (filter) {
                    NotesFilter.RECENT -> "从创作页添加一个 B 站视频，生成第一篇可编辑草稿。"
                    NotesFilter.NEEDS_ORGANIZING -> "已有转写在生成笔记后会从这里移入已保存。"
                    NotesFilter.SAVED -> "先从最近或待整理素材生成一篇笔记草稿。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    FilterChip(
        modifier = Modifier.testTag(testTag),
        onClick = onClick,
        selected = selected,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Edit, contentDescription = null) }
        } else {
            null
        },
    )
}

@Composable
private fun NotesLibraryCard(
    item: NotesLibraryItem,
    canGenerateDraft: Boolean,
    onOpenEditor: (NoteDocument) -> Unit,
    onOpenDestinations: (NoteDocument) -> Unit,
    onGenerateDraft: (StoredVideoResult) -> Unit,
    onRetryCover: (NoteDocument) -> Unit,
    coverRetrying: Boolean,
) {
    val document = item.document
    val cardModifier = Modifier
        .fillMaxWidth()
        .then(if (document != null) Modifier.clickable { onOpenEditor(document) } else Modifier)
        .testTag("notes-item-${item.stableKey}")
    GlassSurface(modifier = cardModifier, emphasized = document != null) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    val sourceMetadata = listOfNotNull(
                        item.ownerName.takeIf(String::isNotBlank),
                        item.durationSeconds.takeIf { it > 0L }?.let(::formatNotesDuration),
                        formatNotesUpdatedAt(item.updatedAtEpochMs),
                    ).joinToString(" · ")
                    Text(
                        sourceMetadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            }
            StatusSummary(item)
            item.coverStatusLabel?.let { coverStatus ->
                Text(
                    coverStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("notes-cover-status-${item.stableKey}"),
                )
            }
            if (document != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onOpenEditor(document) },
                        modifier = Modifier.weight(1f),
                    ) { Text("编辑") }
                    OutlinedButton(
                        onClick = { onOpenDestinations(document) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("分享与去向", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (item.canRetryCover) {
                    TextButton(
                        enabled = !coverRetrying,
                        onClick = { onRetryCover(document) },
                        modifier = Modifier.testTag("notes-cover-retry-${item.stableKey}"),
                    ) {
                        Text(if (coverRetrying) "封面重试中..." else "重试封面")
                    }
                }
            } else {
                val material = requireNotNull(item.material)
                Button(
                    enabled = canGenerateDraft,
                    onClick = { onGenerateDraft(material) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("notes-generate-${item.stableKey}"),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("生成笔记草稿")
                }
            }
        }
    }
}

@Composable
private fun StatusSummary(item: NotesLibraryItem) {
    val label = listOfNotNull(item.localStatusLabel, item.destinationSummary).joinToString(" · ")
    val isError = label.contains("失败")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("notes-item-status-${item.stableKey}"),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        )
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
                .width(96.dp)
                .height(76.dp)
                .clip(MaterialTheme.shapes.small)
                .testTag("notes-thumbnail-${kind.name.lowercase()}-${item.stableKey}"),
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            modifier = Modifier
                .width(96.dp)
                .height(76.dp),
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
