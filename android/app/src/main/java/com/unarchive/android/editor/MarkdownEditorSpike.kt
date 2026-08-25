package com.unarchive.android.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.unarchive.android.ui.markdown.MarkdownAssetResolver
import com.unarchive.android.ui.markdown.MarkdownImageAsset
import com.unarchive.android.ui.markdown.MarkdownProjectionRenderer
import com.unarchive.android.ui.markdown.markdownImageReferences
import kotlinx.coroutines.delay

/** Modes intentionally remain local to the editor Spike until the content schema is frozen. */
internal enum class MarkdownEditorSpikeMode { EDIT, PREVIEW }

internal data class MarkdownEditorImageOption(
    val link: String,
    val alt: String,
)

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
    imageOptions: List<MarkdownEditorImageOption> = emptyList(),
    initialMode: MarkdownEditorSpikeMode = MarkdownEditorSpikeMode.EDIT,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var previewMarkdown by remember { mutableStateOf(markdown) }
    var selectedImage by remember { mutableStateOf<MarkdownImageAsset?>(null) }

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
            MarkdownEditorSpikeMode.EDIT -> Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
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
                if (imageOptions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("markdown-editor-image-actions"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("插入本地截图", style = MaterialTheme.typography.labelLarge)
                        imageOptions.forEach { option ->
                            TextButton(
                                onClick = {
                                    val insertion = "\n\n![${option.alt}](${option.link})\n"
                                    onMarkdownChange(markdown.trimEnd() + insertion)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(option.alt.ifBlank { option.link })
                            }
                        }
                    }
                }
            }
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
                PreviewImageActions(
                    markdown = previewMarkdown,
                    assetResolver = assetResolver,
                    onOpenImage = { selectedImage = it },
                )
            }
        }
    }

    selectedImage?.let { image ->
        Dialog(onDismissRequest = { selectedImage = null }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        painter = image.imageData.painter,
                        contentDescription = image.imageData.contentDescription,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 560.dp),
                        contentScale = ContentScale.Fit,
                    )
                    TextButton(
                        onClick = { selectedImage = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("关闭大图") }
                }
            }
        }
    }
}

@Composable
private fun PreviewImageActions(
    markdown: String,
    assetResolver: MarkdownAssetResolver,
    onOpenImage: (MarkdownImageAsset) -> Unit,
) {
    val images = remember(markdown, assetResolver) {
        markdownImageReferences(markdown).mapNotNull { reference ->
            assetResolver.resolve(reference.link)?.let { asset -> reference to asset }
        }
    }
    if (images.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("markdown-editor-preview-images"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("截图操作", style = MaterialTheme.typography.titleSmall)
            images.forEach { (reference, asset) ->
                TextButton(
                    onClick = { onOpenImage(asset) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("markdown-editor-image-action"),
                ) { Text("查看大图：${reference.alt.ifBlank { asset.imageData.contentDescription }}") }
            }
        }
    }
}

private const val PREVIEW_DEBOUNCE_MS = 250L
