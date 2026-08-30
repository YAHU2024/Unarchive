package com.unarchive.android.graphspike

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class GraphRendererState { LOADING, READY, FAILED }

interface GraphRenderer {
    @Composable
    fun Render(
        projection: GraphProjection,
        modifier: Modifier = Modifier,
        selectedNodeId: String?,
        onNodeSelected: (String) -> Unit,
        onRendererState: (GraphRendererState) -> Unit,
    )
}
