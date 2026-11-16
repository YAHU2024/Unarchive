package com.unarchive.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/** Header measurement is local to its root page; no screen state or navigation ownership. */
internal val LocalRootTitleHeight = staticCompositionLocalOf<(Int) -> Unit> { {} }
internal val LocalRootTitleActionWidth = staticCompositionLocalOf { 0.dp }

// Replaces the old 48dp toolbar reservation: titles and following content rise by 16dp.
private val RootHeaderTopSpace = 32.dp

/** Root content scrolls behind the floating Dock; only its end clears measured Chrome. */
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
    var titleHeight by remember(title) { mutableStateOf(0) }
    var actionWidth by remember(title) { mutableStateOf(0) }
    val density = LocalDensity.current
    val titleTop = RootHeaderTopSpace + contentPadding.calculateTopPadding()
    val topPadding = with(density) { titleTop.roundToPx() }
    val trailingSpace = with(density) { actionWidth.toDp() }
    val collapsed by remember(state, topPadding) { derivedStateOf {
        state.firstVisibleItemIndex > 0 ||
            (titleHeight > 0 && state.firstVisibleItemScrollOffset >= topPadding + titleHeight)
    } }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalRootTitleHeight provides { titleHeight = it },
            LocalRootTitleActionWidth provides trailingSpace,
        ) {
            LazyColumn(
                state = state,
                modifier = modifier.fillMaxSize(),
                verticalArrangement = verticalArrangement,
                contentPadding = PaddingValues(
                    start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
                    top = titleTop,
                    bottom = contentPadding.calculateBottomPadding() + LocalDockClearance.current,
                ),
                content = content,
            )
        }
        RootHeaderChrome(
            title = title,
            collapsed = collapsed,
            actionWidth = trailingSpace,
            onActionWidthChanged = { actionWidth = it },
            expandedTitleCenter = {
                // Placement reads scroll state, so moving the one action row does not
                // recompose its buttons or reset an anchored menu on every scroll frame.
                val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
                val height = titleHeight.takeIf { it > 0 }
                    ?: with(density) { (41f * density.fontScale.coerceAtMost(1.4f)).dp.roundToPx() }
                if (item == null) 0 else topPadding + item.offset + height / 2
            },
            actions = actions,
        )
    }
}

/** One persistent action row follows the large title, then pins beside the compact title. */
@Composable
private fun RootHeaderChrome(
    title: String,
    collapsed: Boolean,
    actionWidth: Dp,
    onActionWidthChanged: (Int) -> Unit,
    expandedTitleCenter: () -> Int,
    actions: @Composable RowScope.() -> Unit,
) {
    val density = LocalDensity.current
    val titleScale = density.fontScale.coerceAtMost(1.12f)
    val titleInset = actionWidth.coerceAtLeast(64.dp)
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            Row(Modifier.testTag("root-actions").onSizeChanged { onActionWidthChanged(it.width) },
                verticalAlignment = Alignment.CenterVertically, content = actions)
            if (collapsed) {
                Box(Modifier.background(MaterialTheme.colorScheme.background))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = with(density) { (17f * titleScale).dp.toSp() },
                        lineHeight = with(density) { (24f * titleScale).dp.toSp() },
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = if (density.fontScale > 1.4f) 4.dp else 10.dp)
                        .testTag("root-compact-title").semantics { heading() },
                )
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val actionRow = measurables[0].measure(loose)
        val compactTitle = if (collapsed) measurables[2].measure(loose.copy(
            maxWidth = (constraints.maxWidth - 2 * titleInset.roundToPx()).coerceAtLeast(0),
        )) else null
        val barHeight = maxOf(48.dp.roundToPx(), actionRow.height, compactTitle?.height ?: 0)
        val background = if (collapsed) measurables[1].measure(
            Constraints.fixed(constraints.maxWidth, barHeight),
        ) else null
        layout(constraints.maxWidth, constraints.maxHeight) {
            background?.placeRelative(0, 0)
            compactTitle?.placeRelative((constraints.maxWidth - compactTitle.width) / 2,
                (barHeight - compactTitle.height) / 2)
            val center = expandedTitleCenter().coerceAtLeast(barHeight / 2)
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
