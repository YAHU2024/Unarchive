package com.unarchive.android.graphspike

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.hypot

class CanvasGraphRenderer : GraphRenderer {
    @Composable
    override fun Render(
        projection: GraphProjection,
        modifier: Modifier,
        selectedNodeId: String?,
        onNodeSelected: (String) -> Unit,
        onRendererState: (GraphRendererState) -> Unit,
    ) {
        var scale by remember(projection.generationKey) { mutableStateOf(1f) }
        var translation by remember(projection.generationKey) { mutableStateOf(Offset.Zero) }
        val positions = remember(projection.generationKey) {
            mutableStateMapOf<String, NormalizedPoint>().apply {
                putAll(DeterministicGraphLayout.layout(projection))
            }
        }
        val textMeasurer = rememberTextMeasurer()

        LaunchedEffect(projection.generationKey) { onRendererState(GraphRendererState.READY) }

        Box(
            modifier
                .testTag("graph-canvas")
                .semantics {
                    contentDescription = "图谱画布：${projection.nodes.size} 个节点，${projection.edges.size} 条关系${if (projection.isTruncated) "，已截断" else ""}。下方列表提供同等操作。"
                }
                .pointerInput(projection.generationKey) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(.55f, 4f)
                        translation += pan
                    }
                }
                .pointerInput(projection.generationKey) {
                    detectTapGestures { tap ->
                        hitNode(projection, positions, tap, size.width.toFloat(), size.height.toFloat(), scale, translation)
                            ?.let(onNodeSelected)
                    }
                }
                .pointerInput(projection.generationKey) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, dragAmount ->
                            hitNode(projection, positions, change.position, size.width.toFloat(), size.height.toFloat(), scale, translation)
                                ?.let { id ->
                                    val old = positions[id] ?: return@let
                                    positions[id] = NormalizedPoint(
                                        (old.x + dragAmount.x / size.width / scale).coerceIn(.04f, .96f),
                                        (old.y + dragAmount.y / size.height / scale).coerceIn(.04f, .96f),
                                    )
                                }
                        },
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawGraph(projection, positions, selectedNodeId, scale, translation, textMeasurer)
            }
        }
    }

    private fun hitNode(
        projection: GraphProjection,
        positions: Map<String, NormalizedPoint>,
        tap: Offset,
        width: Float,
        height: Float,
        scale: Float,
        translation: Offset,
    ): String? = projection.nodes.firstOrNull { node ->
        val normalized = positions[node.id] ?: return@firstOrNull false
        val point = Offset(normalized.x * width * scale + translation.x, normalized.y * height * scale + translation.y)
        hypot((tap.x - point.x).toDouble(), (tap.y - point.y).toDouble()) <= 34.0
    }?.id
}

private fun DrawScope.drawGraph(
    projection: GraphProjection,
    positions: Map<String, NormalizedPoint>,
    selectedNodeId: String?,
    scale: Float,
    translation: Offset,
    textMeasurer: TextMeasurer,
) {
    fun point(id: String): Offset? = positions[id]?.let {
        Offset(it.x * size.width * scale + translation.x, it.y * size.height * scale + translation.y)
    }

    projection.resolvedEdges.groupBy { setOf(it.fromId, it.toId) }.values.forEach { group ->
        val edge = group.first()
        val from = point(edge.fromId) ?: return@forEach
        val to = point(edge.toId) ?: return@forEach
        val color = when (edge.type) {
            GraphRelationType.USER_LINK -> Color(0xFF6750A4)
            GraphRelationType.TAG -> Color(0xFF2E7D32)
            GraphRelationType.SOURCE_VIDEO -> Color(0xFF1565C0)
        }
        if (edge.fromId == edge.toId) {
            drawCircle(color, radius = 30f, center = from, style = Stroke(width = 4f))
        } else {
            drawLine(color, from, to, strokeWidth = if (group.size > 1) 5f else 3f)
            if (group.size > 1) {
                val midpoint = Offset((from.x + to.x) / 2f, (from.y + to.y) / 2f)
                drawText(textMeasurer, "${group.size} 条关系", midpoint, style = TextStyle(color = color, fontSize = 11.sp))
            }
        }
    }

    projection.nodes.forEach { node ->
        val center = point(node.id) ?: return@forEach
        val selected = node.id == selectedNodeId || node.id == projection.focusNodeId
        val fill = when (node.kind) {
            GraphNodeKind.NOTE -> Color(0xFFE8DEF8)
            GraphNodeKind.TAG -> Color(0xFFDDF3E4)
            GraphNodeKind.SOURCE_VIDEO -> Color(0xFFD9EAF7)
        }
        if (selected) drawCircle(Color(0xFFFF9800), radius = 31f, center = center, style = Stroke(width = 5f))
        when (node.kind) {
            GraphNodeKind.NOTE -> drawCircle(fill, radius = 24f, center = center)
            GraphNodeKind.TAG -> drawRect(fill, topLeft = Offset(center.x - 22f, center.y - 22f), size = androidx.compose.ui.geometry.Size(44f, 44f))
            GraphNodeKind.SOURCE_VIDEO -> {
                val path = Path().apply {
                    moveTo(center.x, center.y - 25f)
                    lineTo(center.x + 25f, center.y)
                    lineTo(center.x, center.y + 25f)
                    lineTo(center.x - 25f, center.y)
                    close()
                }
                drawPath(path, fill)
            }
        }
        val style = TextStyle(color = Color(0xFF1D1B20), fontSize = 11.sp)
        val label = node.title.take(22)
        val measured = textMeasurer.measure(label, style)
        drawText(textMeasurer, label, Offset(center.x - measured.size.width / 2f, center.y + 38f), style = style)
    }
}
