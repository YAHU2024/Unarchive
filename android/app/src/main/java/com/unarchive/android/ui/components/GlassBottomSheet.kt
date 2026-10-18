package com.unarchive.android.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Short modal action surface. Android's Dialog owns back/outside dismissal and modal focus.
 * Entrance uses our policy; dismissal removes the surface immediately, never after an animation.
 * No drag handle: explicit actions and system back are the supported dismissal paths.
 */
@Composable
internal fun GlassBottomSheet(
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val focusRequester = remember { FocusRequester() }
        Box(
            Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding(),
        ) {
            Box(Modifier.matchParentSize().pointerInput(onDismissRequest) {
                detectTapGestures { onDismissRequest() }
            })
            GlassSurface(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(12.dp).fillMaxWidth()
                    .then(rememberStateMotionModifier(title, animateOnEntry = true, sheet = true))
                    .semantics { paneTitle = title }
                    .testTag("glass-bottom-sheet"),
                emphasized = true,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.focusRequester(focusRequester).focusable().semantics { heading() },
                    )
                    content()
                }
            }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}
