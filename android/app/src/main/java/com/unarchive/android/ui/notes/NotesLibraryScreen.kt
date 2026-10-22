package com.unarchive.android.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.components.AppIcon
import com.unarchive.android.ui.components.AppIcons
import com.unarchive.android.ui.components.SecondaryPage
import com.unarchive.android.ui.state.NotesLibraryItem
import com.unarchive.android.ui.state.NotesLibraryItemKind
import com.unarchive.android.ui.state.NotesUiState

/** Full note library presentation. It intentionally reuses the Notes state assembled by the app. */
@Composable
internal fun NotesLibraryScreen(
    state: NotesUiState,
    onBack: () -> Unit,
    onOpenEditor: (com.unarchive.android.card.NoteDocument) -> Unit,
    onOpenDestinations: (com.unarchive.android.card.NoteDocument) -> Unit,
) {
    val items = state.libraryItems
    SecondaryPage(
        title = "完整笔记库",
        onBack = onBack,
        backTag = "notes-library-back",
        titleTag = "notes-library-title",
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("notes-library-screen"),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, top = 18.dp, bottom = 32.dp,
            ),
        ) {
            item {
                Text(
                    "全部内容",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 10.dp).semantics { heading() },
                )
            }
            if (items.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 72.dp).testTag("notes-library-empty"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AppIcon(AppIcons.Notes, contentDescription = null)
                        Text("还没有笔记或素材", style = MaterialTheme.typography.titleMedium)
                        Text("从创作页添加一段视频开始", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(items, key = { it.stableKey }) { item ->
                    NotesLibraryRow(
                        item = item,
                        onClick = item.document?.let { { onOpenEditor(it) } },
                        onOpenDestinations = item.document?.let { { onOpenDestinations(it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotesLibraryRow(
    item: NotesLibraryItem,
    onClick: (() -> Unit)?,
    onOpenDestinations: (() -> Unit)?,
) {
    val action = onClick ?: onOpenDestinations
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(if (action != null) Modifier.clickable(onClick = action) else Modifier)
            .padding(vertical = 14.dp)
            .testTag("notes-library-item:${item.stableKey}"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            AppIcon(AppIcons.Chevron, contentDescription = "打开", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (item.kind == NotesLibraryItemKind.MATERIAL) "待整理素材" else "笔记",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(item.localStatusLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.relationCount > 0) Text("关联 ${item.relationCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
