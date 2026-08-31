package com.unarchive.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.unarchive.android.log.AppLogger
import com.unarchive.android.ui.diagnostics.DiagnosticsScreen

/** Compatibility entry kept while the developer tools navigation is migrated. */
@Composable
internal fun LogTab(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    DiagnosticsScreen(
        entries = synchronized(AppLogger.entries) { AppLogger.entries.toList() },
        lastCrash = AppLogger.lastCrashSummary,
        droppedWriteCount = AppLogger.droppedWriteCount,
        onBack = onBack,
        onClear = AppLogger::clear,
        onCopy = context::copyText,
        onExport = { text ->
            context.exportTextFile(
                "unarchive-diagnostics-${System.currentTimeMillis()}.txt",
                text,
                "Unarchive 诊断日志",
            )
        },
    )
}
