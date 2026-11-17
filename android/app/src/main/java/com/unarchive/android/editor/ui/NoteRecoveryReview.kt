package com.unarchive.android.editor.ui

import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unarchive.android.ui.components.UnarchiveTextButton

@Composable
internal fun NoteRecoveryReview(text: String, onBack: () -> Unit, onCopy: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row { UnarchiveTextButton(onClick = onBack) { Text("返回") }; UnarchiveTextButton(onClick = onCopy) { Text("复制完整内容") } }
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    TextView(context).apply { setText(text); setTextIsSelectable(true); textSize = 16f
                        movementMethod = android.text.method.ScrollingMovementMethod()
                        setPadding(24, 24, 24, 80) }
                }, update = { it.setTextColor(colors.onSurface.toArgb()); it.setBackgroundColor(colors.surface.toArgb()) })
            }
        }
    }
}
