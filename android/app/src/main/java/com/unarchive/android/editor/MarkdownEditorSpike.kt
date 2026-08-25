package com.unarchive.android.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.unarchive.android.card.CardAsset
import com.unarchive.android.card.CardAssetKind
import com.unarchive.android.ui.markdown.MarkdownAssetResolver
import com.unarchive.android.ui.markdown.MarkdownImageAsset
import com.unarchive.android.ui.markdown.MarkdownProjectionRenderer
import com.unarchive.android.ui.markdown.markdownImageReferences

/** Modes intentionally remain local to the editor Spike until the content schema is frozen. */
internal enum class MarkdownEditorSpikeMode { EDIT, PREVIEW }

internal data class MarkdownEditorImageOption(
    val link: String,
    val alt: String,
)

internal fun markdownImageOptions(assets: List<CardAsset>): List<MarkdownEditorImageOption> = assets
    .asSequence()
    .filter { it.kind != CardAssetKind.COVER }
    .distinctBy(CardAsset::relativePath)
    .sortedWith(compareBy<CardAsset> { it.chapterIndex ?: Int.MAX_VALUE }.thenBy { it.relativePath })
    .map { asset -> MarkdownEditorImageOption(asset.relativePath, asset.assetId) }
    .toList()

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
    initialMode: MarkdownEditorSpikeMode = MarkdownEditorSpikeMode.PREVIEW,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var editingBlock by remember { mutableStateOf<MarkdownLiveBlock?>(null) }
    var editingMarkdown by remember { mutableStateOf("") }
    var editingDocument by remember { mutableStateOf("") }
    var renderedMarkdown by remember { mutableStateOf(markdown) }
    var selectedImage by remember { mutableStateOf<MarkdownImageAsset?>(null) }
    val blocks = remember(renderedMarkdown) { MarkdownLiveBlockParser.parse(renderedMarkdown) }

    // Reparse only stable content. While a block is focused, each IME update
    // changes that block's draft source without reprocessing a long document.
    LaunchedEffect(markdown, editingBlock) {
        if (editingBlock == null) renderedMarkdown = markdown
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
                ) { Text("源码") }
            } else {
                TextButton(
                    onClick = { mode = MarkdownEditorSpikeMode.EDIT },
                    modifier = Modifier.testTag("markdown-editor-edit-tab"),
                ) { Text("源码") }
            }
            if (mode == MarkdownEditorSpikeMode.PREVIEW) {
                Button(
                    onClick = { mode = MarkdownEditorSpikeMode.PREVIEW },
                    modifier = Modifier.testTag("markdown-editor-preview-tab"),
                ) { Text("实时预览") }
            } else {
                TextButton(
                    onClick = { mode = MarkdownEditorSpikeMode.PREVIEW },
                    modifier = Modifier.testTag("markdown-editor-preview-tab"),
                ) { Text("实时预览") }
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
                        .testTag("markdown-editor-source"),
                    label = { Text("Markdown 源码") },
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
            MarkdownEditorSpikeMode.PREVIEW -> LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .testTag("markdown-editor-preview"),
            ) {
                item(key = "markdown-preview-heading") {
                    Text(
                        "实时预览",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(
                    items = blocks,
                    key = MarkdownLiveBlock::id,
                ) { block ->
                    if (editingBlock?.id == block.id && block.editable) {
                        OutlinedTextField(
                            value = editingMarkdown,
                            onValueChange = { replacement ->
                                val current = requireNotNull(editingBlock)
                                val updated = MarkdownLiveBlockParser.replace(editingDocument, current, replacement)
                                editingDocument = updated
                                editingMarkdown = replacement
                                editingBlock = current.copy(
                                    endOffset = current.startOffset + replacement.length,
                                    markdown = replacement,
                                )
                                onMarkdownChange(updated)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("markdown-live-edit-${block.id}"),
                            label = { Text("编辑此块") },
                        )
                        TextButton(
                            onClick = {
                                editingBlock = null
                                renderedMarkdown = markdown
                            },
                            modifier = Modifier.testTag("markdown-live-edit-done-${block.id}"),
                        ) { Text("完成") }
                    } else {
                        if (block.type == MarkdownLiveBlockType.FRONT_MATTER) {
                            Text(
                                block.markdown,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("markdown-live-block-${block.id}"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            MarkdownProjectionRenderer(
                                markdown = block.markdown,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = block.editable) {
                                        editingDocument = markdown
                                        editingMarkdown = block.markdown
                                        editingBlock = block
                                    }
                                    .testTag("markdown-live-block-${block.id}"),
                                assetResolver = assetResolver,
                            )
                        }
                    }
                }
                item(key = "markdown-preview-image-actions") {
                    PreviewImageActions(
                        markdown = markdown,
                        assetResolver = assetResolver,
                        onOpenImage = { selectedImage = it },
                    )
                }
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
