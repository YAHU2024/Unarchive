package com.unarchive.android.ui.components

import androidx.activity.compose.PredictiveBackHandler
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer

/** Header measurement is local to its root page; no screen state or navigation ownership. */
internal val LocalRootTitleHeight = staticCompositionLocalOf<(Int) -> Unit> { {} }

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
    val topPadding = with(LocalDensity.current) { contentPadding.calculateTopPadding().roundToPx() }
    val collapsed by remember(state, topPadding) { derivedStateOf {
        state.firstVisibleItemIndex > 0 ||
            (titleHeight > 0 && state.firstVisibleItemScrollOffset >= topPadding + titleHeight)
    } }
    Column(Modifier.fillMaxSize()) {
        PageTopBar(if (collapsed) title else "", tag = "root-compact-title", actions = actions)
        CompositionLocalProvider(LocalRootTitleHeight provides { titleHeight = it }) {
            LazyColumn(
                state = state,
                modifier = modifier.weight(1f),
                verticalArrangement = verticalArrangement,
                contentPadding = PaddingValues(
                    start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + LocalDockClearance.current,
                ),
                content = content,
            )
        }
    }
}

/** One stable centered title and one back action for every secondary destination. */
@Composable
internal fun SecondaryPage(title: String, onBack: () -> Unit, backTag: String = "page-back", titleTag: String = "secondary-page-title", actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { progress ->
        var completed = false
        try {
            progress.collect { event -> backProgress = event.progress }
            completed = true
        } finally {
            backProgress = 0f
            if (completed) onBack()
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = size.width * backProgress * 0.28f
                alpha = 1f - backProgress * 0.06f
            },
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
