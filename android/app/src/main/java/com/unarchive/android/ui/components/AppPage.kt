package com.unarchive.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Action-width measurement remains local to its root page. */
internal val LocalRootTitleActionWidth = staticCompositionLocalOf { 0.dp }

// Replaces the old 48dp toolbar reservation: titles and following content rise by 16dp.
private val RootHeaderTopSpace = 32.dp
private val RootTitleShrinkDistance = 80.dp

/** Title, actions and background collapse together while the body's scroll geometry stays stable. */
@Composable
internal fun RootPage(
    title: String,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    contentPadding: PaddingValues = PaddingValues(top = 8.dp, bottom = 24.dp),
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val state = rememberLazyListState()
    var expandedHeaderHeight by remember(title, LocalDensity.current.fontScale) { mutableStateOf(0) }
    var actionWidth by remember(title) { mutableStateOf(0) }
    val density = LocalDensity.current
    val titleTop = RootHeaderTopSpace + contentPadding.calculateTopPadding()
    val shrinkDistance = with(density) { RootTitleShrinkDistance.toPx() }
    val trailingSpace = with(density) { actionWidth.toDp() }
    val collapseProgress = remember(state, shrinkDistance) {
        {
            // Item zero is a stable expanded-header spacer. Changing the visible bar's
            // height must not change the scroll distance that drives its collapse.
            if (state.firstVisibleItemIndex > 0) 1f
                else (state.firstVisibleItemScrollOffset / shrinkDistance).coerceIn(0f, 1f)
        }
    }
    val expandedSpace = if (expandedHeaderHeight > 0) with(density) { expandedHeaderHeight.toDp() }
        else (titleTop + (45f * density.fontScale.coerceAtMost(1.4f)).dp).coerceAtLeast(RootTitleShrinkDistance)
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalRootTitleActionWidth provides trailingSpace,
        ) {
            LazyColumn(
                state = state,
                modifier = modifier.fillMaxSize(),
                verticalArrangement = verticalArrangement,
                contentPadding = PaddingValues(
                    start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
                    top = 0.dp,
                    bottom = contentPadding.calculateBottomPadding() + LocalDockClearance.current,
                ),
            ) {
                item(key = "root-title-space") {
                    Spacer(Modifier.fillMaxWidth().height(expandedSpace))
                }
                content()
            }
        }
        CompositionLocalProvider(LocalRootTitleActionWidth provides trailingSpace) {
            RootHeaderChrome(
                title = title,
                titleTop = titleTop,
                collapseProgress = collapseProgress,
                onExpandedHeightChanged = { expandedHeaderHeight = it },
                onActionWidthChanged = { actionWidth = it },
                modifier = Modifier
                    .scrollable(state, Orientation.Vertical, reverseDirection = true)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown().consume()
                            waitForUpOrCancellation()?.consume()
                        }
                    },
                actions = actions,
            )
        }
    }
}

/** One layout owns the real bar height and a shared center for title and live actions. */
@Composable
private fun RootHeaderChrome(
    title: String,
    titleTop: Dp,
    collapseProgress: () -> Float,
    onExpandedHeightChanged: (Int) -> Unit,
    onActionWidthChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtMost(1.4f)
    Layout(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .testTag("root-title-header"),
        content = {
            Box(Modifier.padding(horizontal = 20.dp)) {
                LibraryPageHeader(title, titleScale = {
                    1f - (1f - 24f / 34f) * collapseProgress()
                })
            }
            Row(Modifier.testTag("root-actions").onSizeChanged { onActionWidthChanged(it.width) },
                verticalAlignment = Alignment.CenterVertically, content = actions)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val titleRow = measurables[0].measure(loose)
        val actionRow = measurables[1].measure(loose)
        val expandedSpaceHeight = maxOf(titleTop.roundToPx() + titleRow.height,
            RootTitleShrinkDistance.roundToPx())
        onExpandedHeightChanged(expandedSpaceHeight)
        val expandedCenter = titleTop.roundToPx() + titleRow.height / 2f
        val expandedHeight = maxOf(expandedSpaceHeight,
            ceil(expandedCenter + actionRow.height / 2f).toInt())
        // Preserve comfortable top/bottom breathing room around unchanged 48dp targets.
        // All four pages use 68dp normally and 72dp at the capped large-title scale.
        val minimumHeight = maxOf((68f + 10f * (fontScale - 1f)).dp.roundToPx(),
            actionRow.height + 16.dp.roundToPx())
        val progress = collapseProgress()
        val barHeight = (expandedHeight + (minimumHeight - expandedHeight) * progress).roundToInt()
        val center = expandedCenter + (minimumHeight / 2f - expandedCenter) * progress
        layout(constraints.maxWidth, barHeight) {
            titleRow.placeRelative(0, (center - titleRow.height / 2f).roundToInt())
            actionRow.placeRelative(constraints.maxWidth - actionRow.width,
                (center - actionRow.height / 2f).roundToInt())
        }
    }
}

/** One stable centered title and one back action for every secondary destination. */
@Composable
internal fun SecondaryPage(title: String, onBack: () -> Unit, backTag: String = "page-back", titleTag: String = "secondary-page-title", actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize(),
    ) {
        PageTopBar(title, onBack, backTag, titleTag, actions)
        content()
    }
}

@Composable
private fun PageTopBar(title: String, onBack: (() -> Unit)? = null, backTag: String = "page-back", tag: String, actions: @Composable RowScope.() -> Unit = {}) {
    val density = LocalDensity.current
    val titleScale = density.fontScale.coerceAtMost(1.12f)
    var actionWidth by remember { mutableStateOf(0) }
    val titleInset = with(LocalDensity.current) { actionWidth.toDp() }.coerceAtLeast(64.dp)
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(MaterialTheme.colorScheme.background)) {
        if (onBack != null) UnarchiveIconButton(onBack, Modifier.align(Alignment.CenterStart).testTag(backTag)) {
            AppIcon(AppIcons.Back, contentDescription = "返回")
        }
        Row(Modifier.align(Alignment.CenterEnd).onSizeChanged { actionWidth = it.width }, content = actions)
        if (title.isNotEmpty()) Text(
            title, style = MaterialTheme.typography.titleMedium.copy(fontSize = with(density) { (17f * titleScale).dp.toSp() }, lineHeight = with(density) { (24f * titleScale).dp.toSp() }),
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = titleInset, vertical = if (density.fontScale > 1.4f) 4.dp else 10.dp)
                .testTag(tag).semantics { heading() },
        )
    }
}
