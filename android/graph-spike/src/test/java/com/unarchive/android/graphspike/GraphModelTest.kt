package com.unarchive.android.graphspike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphModelTest {
    @Test
    fun idsAreNamespacedAndDisplayTitlesDoNotAffectIdentity() {
        assertEquals("note:abc", GraphIds.note("abc"))
        assertEquals("tag:kotlin", GraphIds.tag("kotlin"))
        assertEquals("source:hash", GraphIds.source("hash"))
        val projection = FixtureGraphs.byName("All edge cases")
        assertEquals(5, projection.nodes.size)
        assertEquals(2, projection.nodes.count { it.title == "Same title" })
        assertTrue(projection.nodes.map { it.id }.distinct().size == projection.nodes.size)
    }

    @Test
    fun missingEndpointsRemainVisibleToTheAuthoritativeList() {
        val projection = FixtureGraphs.byName("All edge cases")
        assertEquals(1, projection.unresolvedEdges.size)
        assertEquals("missing", projection.unresolvedEdges.single().relationId)
        assertEquals(projection.edges.size - 1, projection.resolvedEdges.size)
    }

    @Test
    fun cyclesSelfLoopsAndParallelRelationsArePreserved() {
        val projection = FixtureGraphs.byName("All edge cases")
        assertTrue(projection.edges.any { it.fromId == it.toId })
        assertEquals(2, projection.edges.count { it.fromId == GraphIds.note("current") && it.toId == GraphIds.note("duplicate-a") })
        assertEquals(8, projection.edges.size)
    }

    @Test
    fun benchmarkFixtureHasTheFrozenSpikeSize() {
        val projection = FixtureGraphs.byName("100 nodes / 200 edges")
        assertEquals(100, projection.nodes.size)
        assertEquals(200, projection.edges.size)
        assertEquals(200, projection.rawEdgeCount)
    }

    @Test
    fun layoutIsDeterministicAndFocusIsCentered() {
        val projection = FixtureGraphs.byName("Two hop")
        val first = DeterministicGraphLayout.layout(projection)
        val second = DeterministicGraphLayout.layout(projection)
        assertEquals(first, second)
        assertEquals(NormalizedPoint(.5f, .5f), first[projection.focusNodeId])
    }
}
