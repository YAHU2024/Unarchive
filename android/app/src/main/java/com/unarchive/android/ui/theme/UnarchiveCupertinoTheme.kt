package com.unarchive.android.ui.theme

import androidx.compose.runtime.Composable
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme

/**
 * Project-owned theme boundary for Cupertino controls.
 *
 * Business pages depend on this boundary and on the project components rather
 * than on the third-party theme type. Material tokens remain available for
 * reading surfaces and existing infrastructure while interactive controls use
 * the Cupertino implementation.
 */
@Composable
internal fun UnarchiveCupertinoTheme(content: @Composable () -> Unit) {
    CupertinoTheme(content = content)
}
