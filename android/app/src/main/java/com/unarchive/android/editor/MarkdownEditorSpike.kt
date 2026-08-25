package com.unarchive.android.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.markdown.MarkdownAssetResolver
import com.unarchive.android.ui.markdown.MarkdownProjectionRenderer
import kotlinx.coroutines.delay

/** Modes intentionally remain local to the editor Spike until the content schema is frozen. */
internal enum class MarkdownEditorSpikeMode { EDIT, PREVIEW }

/**
 * Isolated Markdown source editor and live preview. It does not persist content
 * or replace NoteEditorScreen; callers own the source string and asset scope.
 */
@Composable
internal fun MarkdownEditorSpike(
    markdown: String,
    onMarkdownChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    assetResolver: MarkdownAssetResolver,
    initialMode: MarkdownEditorSpikeMode = MarkdownEditorSpikeMode.EDIT,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var previewMarkdown by remember { mutableStateOf(markdown) }

    LaunchedEffect(markdown) {
        delay(PREVIEW_DEBOUNCE_MS)
        previewMarkdown = markdown
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("markdown-editor-spike"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (mode == MarkdownEditorSpikeMode.EDIT) {
                Button(
                    onClick = { mode = MarkdownEditorSpikeMode.EDIT },
                    modifier = Modifier.testTag("markdown-editor-edit-tab"),
                ) { Text("编辑") }
            } else {
                TextButton(
                    onClick = { mode = MarkdownEditorSpikeMode.EDIT },
                    modifier = Modifier.testTag("markdown-editor-edit-tab"),
                ) { Text("编辑") }
            }
            if (mode == MarkdownEditorSpikeMode.PREVIEW) {
                Button(
                    onClick = { mode = MarkdownEditorSpikeMode.PREVIEW },
                    modifier = Modifier.testTag("markdown-editor-preview-tab"),
                ) { Text("预览") }
            } else {
                TextButton(
                    onClick = { mode = MarkdownEditorSpikeMode.PREVIEW },
                    modifier = Modifier.testTag("markdown-editor-preview-tab"),
                ) { Text("预览") }
            }
        }

        when (mode) {
            MarkdownEditorSpikeMode.EDIT -> OutlinedTextField(
                value = markdown,
                onValueChange = onMarkdownChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag("markdown-editor-source"),
                label = { Text("Markdown") },
                supportingText = {
                    Text(
                        "输入停止后实时更新预览",
                        modifier = Modifier.semantics { contentDescription = "实时预览提示" },
                    )
                },
                minLines = 12,
            )
            MarkdownEditorSpikeMode.PREVIEW -> Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp)
                    .testTag("markdown-editor-preview"),
            ) {
                Text(
                    "Markdown 预览",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                MarkdownProjectionRenderer(
                    markdown = previewMarkdown,
                    modifier = Modifier.fillMaxWidth(),
                    assetResolver = assetResolver,
                )
            }
        }
    }
}

private const val PREVIEW_DEBOUNCE_MS = 250L
