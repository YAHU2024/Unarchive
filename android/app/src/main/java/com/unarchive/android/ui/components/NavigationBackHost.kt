package com.unarchive.android.ui.components

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.unarchive.android.ui.theme.LocalMotionPolicy

// Only page wrappers consume this value; changing it must not recompose all
// business content when a prepared system gesture is confirmed or cancelled.
internal val LocalSystemBackForeground = compositionLocalOf<String?> { null }

/** Separate real system callbacks from synthetic content progress and keep touch coordinates fixed. */
@Composable
internal fun NavigationBackHost(
    routeId: String?,
    enabled: Boolean,
    canGoBack: Boolean,
    content: @Composable (ContentBackGestureController) -> Unit,
) {
    val realDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val owner = remember(lifecycleOwner) {
        object : OnBackPressedDispatcherOwner {
            override val lifecycle: Lifecycle = lifecycleOwner.lifecycle
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
        }
    }
    val controller = remember(owner, scope) { ContentBackGestureController(owner.onBackPressedDispatcher, scope) }
    val motion = LocalMotionPolicy.current
    SideEffect {
        controller.changeRoute(routeId)
        controller.enabled = enabled
        controller.systemBack.isEnabled = canGoBack
        controller.animateLayout = motion.animateLayout
        controller.returnDurationMillis = motion.secondaryPageExitDurationMillis
        controller.cancelDurationMillis = motion.secondaryPageCancelDurationMillis
    }
    CompositionLocalProvider(
        LocalOnBackPressedDispatcherOwner provides owner,
        LocalSystemBackForeground provides if (controller.systemPreparing) routeId else null,
    ) {
        Box(Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .appContentBackGesture(enabled, controller, LocalView.current, LocalDensity.current)
            .testTag("navigation-canvas")) {
            content(controller)
        }
    }
    DisposableEffect(realDispatcher, lifecycleOwner, controller) {
        realDispatcher.addCallback(lifecycleOwner, controller.systemBack)
        onDispose {
            controller.dispose()
            controller.systemBack.remove()
        }
    }
}
