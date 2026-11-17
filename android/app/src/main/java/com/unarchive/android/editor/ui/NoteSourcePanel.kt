package com.unarchive.android.editor.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unarchive.android.editor.NoteSourceScratch
import com.unarchive.android.ui.components.UnarchiveTextButton

/** The panel has a temporary buffer; only Apply creates one session transaction. */
@Composable
internal fun NoteSourcePanel(scratch: NoteSourceScratch, onChange: (String) -> Unit,
    onApply: () -> Unit, onCancel: () -> Unit, onCopy: () -> Unit, readOnly: Boolean = false, error: String? = null) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("markdown-note-source-panel")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    UnarchiveTextButton(onClick = onCancel) { Text("取消") }
                    UnarchiveTextButton(onClick = onCopy) { Text("复制源码") }
                    UnarchiveTextButton(onClick = onApply, enabled = !readOnly, modifier = Modifier.testTag("markdown-note-apply-source")) { Text("应用") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                    EditText(context).apply {
                        gravity = Gravity.TOP or Gravity.START
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        if (readOnly) { keyListener = null; setTextIsSelectable(true) }
                        setHorizontallyScrolling(false)
                        setText(scratch.text)
                        setPadding(24, 24, 24, 80)
                        typeface = android.graphics.Typeface.MONOSPACE
                        textSize = 16f
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onChange(s.toString()) }
                            override fun afterTextChanged(s: Editable?) = Unit
                        })
                    }
                }, update = { it.setTextColor(colors.onSurface.toArgb()); it.setBackgroundColor(colors.surface.toArgb()) })
            }
        }
    }
}
