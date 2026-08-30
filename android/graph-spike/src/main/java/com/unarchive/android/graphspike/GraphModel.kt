package com.unarchive.android.graphspike

enum class GraphNodeKind { NOTE, TAG, SOURCE_VIDEO }

enum class GraphRelationType { USER_LINK, TAG, SOURCE_VIDEO }

data class GraphEvidence(
    val blockId: String? = null,
    val sourceUrl: String? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
)

data class GraphCapabilities(
    val canOpen: Boolean = true,
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
)

data class GraphNode(
    val id: String,
    val kind: GraphNodeKind,
    val entityId: String,
    val title: String,
    val sourceRevision: String? = null,
)

data class GraphEdge(
    val relationId: String,
    val fromId: String,
    val toId: String,
    val type: GraphRelationType,
    val label: String,
    val description: String? = null,
    val createdAtEpochMs: Long = 0L,
    val evidence: GraphEvidence? = null,
    val capabilities: GraphCapabilities = GraphCapabilities(),
)

data class GraphProjection(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val focusNodeId: String? = null,
    val rawNodeCount: Int = nodes.size,
    val rawEdgeCount: Int = edges.size,
    val isTruncated: Boolean = false,
    val generationKey: String = "fixture",
) {
    private val nodeIds: Set<String> = nodes.mapTo(linkedSetOf()) { it.id }

    val resolvedEdges: List<GraphEdge>
        get() = edges.filter { it.fromId in nodeIds && it.toId in nodeIds }

    val unresolvedEdges: List<GraphEdge>
        get() = edges.filterNot { it.fromId in nodeIds && it.toId in nodeIds }

    init {
        require(nodes.map { it.id }.distinct().size == nodes.size) { "Graph node IDs must be unique" }
        require(edges.map { it.relationId }.distinct().size == edges.size) { "Graph relation IDs must be unique" }
        require(nodes.all { it.id.substringBefore(':') in setOf("note", "tag", "source") }) {
            "Graph node IDs must be namespaced"
        }
    }
}

object GraphIds {
    fun note(cardId: String) = "note:$cardId"
    fun tag(normalizedTag: String) = "tag:$normalizedTag"
    fun source(stableHash: String) = "source:$stableHash"
}

object FixtureGraphs {
    val names = listOf("All edge cases", "Empty", "Small", "One hop", "Two hop", "100 nodes / 200 edges")

    fun byName(name: String): GraphProjection = when (name) {
        "Empty" -> GraphProjection(emptyList(), emptyList(), generationKey = "empty")
        "Small" -> small()
        "One hop" -> oneHop()
        "Two hop" -> twoHop()
        "100 nodes / 200 edges" -> benchmark()
        else -> edgeCases()
    }

    private fun small(): GraphProjection {
        val n1 = GraphNode(GraphIds.note("current"), GraphNodeKind.NOTE, "current", "Current note")
        val n2 = GraphNode(GraphIds.note("related"), GraphNodeKind.NOTE, "related", "Related note")
        val tag = GraphNode(GraphIds.tag("kotlin"), GraphNodeKind.TAG, "kotlin", "#kotlin")
        return GraphProjection(
            listOf(n1, n2, tag),
            listOf(
                GraphEdge("r-link", n1.id, n2.id, GraphRelationType.USER_LINK, "User link", "Manually added"),
                GraphEdge("r-tag", n1.id, tag.id, GraphRelationType.TAG, "Tag", "Shared tag"),
            ),
            n1.id,
            generationKey = "small",
        )
    }

    private fun oneHop() = small().copy(generationKey = "one-hop")

    private fun twoHop(): GraphProjection {
        val nodes = (0..4).map { GraphNode(GraphIds.note("hop-$it"), GraphNodeKind.NOTE, "hop-$it", "Hop $it") }
        val edges = (0 until 4).map {
            GraphEdge("hop-edge-$it", nodes[it].id, nodes[it + 1].id, GraphRelationType.USER_LINK, "User link")
        }
        return GraphProjection(nodes, edges, nodes.first().id, generationKey = "two-hop")
    }

    private fun edgeCases(): GraphProjection {
        val current = GraphNode(GraphIds.note("current"), GraphNodeKind.NOTE, "current", "A very long current note title that must remain readable")
        val duplicateA = GraphNode(GraphIds.note("duplicate-a"), GraphNodeKind.NOTE, "duplicate-a", "Same title")
        val duplicateB = GraphNode(GraphIds.note("duplicate-b"), GraphNodeKind.NOTE, "duplicate-b", "Same title")
        val tag = GraphNode(GraphIds.tag("research"), GraphNodeKind.TAG, "research", "#research")
        val source = GraphNode(GraphIds.source("video-42"), GraphNodeKind.SOURCE_VIDEO, "video-42", "Source video")
        val missing = GraphIds.note("deleted")
        val edges = listOf(
            GraphEdge("link-1", current.id, duplicateA.id, GraphRelationType.USER_LINK, "User link", "First explanation", capabilities = GraphCapabilities(canEdit = true, canDelete = true)),
            GraphEdge("link-2", current.id, duplicateA.id, GraphRelationType.TAG, "Tag", "Parallel relation"),
            GraphEdge("loop", current.id, current.id, GraphRelationType.USER_LINK, "Self link"),
            GraphEdge("cycle-a", duplicateA.id, duplicateB.id, GraphRelationType.USER_LINK, "Cycle"),
            GraphEdge("cycle-b", duplicateB.id, current.id, GraphRelationType.USER_LINK, "Cycle"),
            GraphEdge("tag", current.id, tag.id, GraphRelationType.TAG, "Tag", evidence = GraphEvidence(blockId = "block-1")),
            GraphEdge("source", current.id, source.id, GraphRelationType.SOURCE_VIDEO, "Source video", evidence = GraphEvidence(sourceUrl = "https://example.invalid/video", startMs = 1200, endMs = 3400)),
            GraphEdge("missing", current.id, missing, GraphRelationType.USER_LINK, "Missing target"),
        )
        return GraphProjection(listOf(current, duplicateA, duplicateB, tag, source), edges, current.id, generationKey = "edge-cases")
    }

    private fun benchmark(): GraphProjection {
        val nodes = (0 until 100).map { index ->
            when {
                index < 90 -> GraphNode(GraphIds.note("n-$index"), GraphNodeKind.NOTE, "n-$index", if (index % 17 == 0) "Long benchmark title $index with extra words" else "Note $index")
                index < 95 -> GraphNode(GraphIds.tag("tag-$index"), GraphNodeKind.TAG, "tag-$index", "#tag-$index")
                else -> GraphNode(GraphIds.source("video-$index"), GraphNodeKind.SOURCE_VIDEO, "video-$index", "Video $index")
            }
        }
        val edges = (0 until 200).map { index ->
            val from = nodes[index % nodes.size]
            val to = nodes[(index * 7 + 3) % nodes.size]
            val type = when (index % 3) {
                0 -> GraphRelationType.USER_LINK
                1 -> GraphRelationType.TAG
                else -> GraphRelationType.SOURCE_VIDEO
            }
            GraphEdge("benchmark-$index", from.id, to.id, type, type.name, "Fixture relation $index")
        }
        return GraphProjection(nodes, edges, nodes.first().id, generationKey = "benchmark-100-200")
    }
}
