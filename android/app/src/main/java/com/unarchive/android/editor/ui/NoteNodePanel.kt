package com.unarchive.android.editor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unarchive.android.editor.session.DocumentNode
import com.unarchive.android.ui.components.UnarchiveTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Complete accessible text/table view complements the single-buffer native atom. */
@Composable
internal fun NoteNodePanel(node: DocumentNode, editing: Boolean, onClose: () -> Unit, onSource: () -> Unit,
    onDelete: () -> Unit, onReplace: (() -> Unit)?, onCopy: () -> Unit,
    resolveImage: (suspend (String, Int) -> Bitmap?)?) {
    val atom = node.atomic
    var bitmap by remember(node.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(node.id) { if (atom?.kind == "image" && atom.destination != null)
        bitmap = withContext(Dispatchers.IO) { resolveImage?.invoke(atom.destination, 1080) } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth()) {
                    UnarchiveTextButton(onClick = onClose) { Text("返回") }
                    UnarchiveTextButton(onClick = onSource) { Text("查看源码") }
                    UnarchiveTextButton(onClick = onCopy) { Text("复制引用") }
                }
                if (editing) Row {
                    onReplace?.let { UnarchiveTextButton(onClick = it) { Text("替换图片") } }
                    UnarchiveTextButton(onClick = onDelete) { Text("删除此块") }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    when {
                        atom?.kind == "image" -> {
                            bitmap?.let { Image(it.asImageBitmap(), contentDescription = atom.text, modifier = Modifier.fillMaxWidth()) }
                                ?: Text("图片暂不可用，可复制引用或查看源码")
                            Text(atom.text)
                        }
                        atom?.kind == "table" -> SelectionContainer {
                            Column(Modifier.horizontalScroll(rememberScrollState())) {
                                atom.rows.forEachIndexed { index, row ->
                                    Row { row.forEach { cell -> Text(cell, Modifier.widthIn(min = 120.dp).padding(12.dp),
                                        style = if (index == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge) } }
                                }
                            }
                        }
                        node.kind == "code_fence" || node.kind == "code_block" -> SelectionContainer {
                            Text(node.text, Modifier.horizontalScroll(rememberScrollState()), softWrap = false,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        else -> SelectionContainer { Text(atom?.text ?: node.text) }
                    }
                }
            }
        }
    }
}
