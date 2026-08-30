package com.unarchive.android.graphspike

import kotlin.math.cos
import kotlin.math.sin

data class NormalizedPoint(val x: Float, val y: Float)

/** A deliberately boring, deterministic layout for the G0 renderer spike. */
object DeterministicGraphLayout {
    fun layout(projection: GraphProjection): Map<String, NormalizedPoint> {
        if (projection.nodes.isEmpty()) return emptyMap()
        val ordered = projection.nodes.sortedBy { it.id }
        val focusIndex = ordered.indexOfFirst { it.id == projection.focusNodeId }.takeIf { it >= 0 } ?: 0
        return ordered.mapIndexed { index, node ->
            val point = when {
                ordered.size == 1 || index == focusIndex -> NormalizedPoint(.5f, .5f)
                else -> {
                    val slot = if (index < focusIndex) index else index - 1
                    val slots = (ordered.size - 1).coerceAtLeast(1)
                    val angle = slot.toDouble() / slots.toDouble() * (Math.PI * 2.0) - Math.PI / 2.0
                    NormalizedPoint(
                        (0.5 + 0.38 * cos(angle)).toFloat(),
                        (0.5 + 0.38 * sin(angle)).toFloat(),
                    )
                }
            }
            node.id to point
        }.toMap(linkedMapOf())
    }
}
