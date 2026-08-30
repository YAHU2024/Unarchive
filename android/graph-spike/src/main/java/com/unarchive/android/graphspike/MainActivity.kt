package com.unarchive.android.graphspike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GraphSpikeApp() }
    }
}

@Composable
fun GraphSpikeApp() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) { GraphSpikeScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GraphSpikeScreen() {
    var fixtureName by remember { mutableStateOf(FixtureGraphs.names.first()) }
    var showGraph by remember { mutableStateOf(true) }
    var forceFailure by remember { mutableStateOf(false) }
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var projection by remember(fixtureName) { mutableStateOf(FixtureGraphs.byName(fixtureName)) }
    val filteredNodes = remember(projection, query) {
        if (query.isBlank()) projection.nodes else projection.nodes.filter { it.title.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Graph G0 Spike") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            Text("固定脱敏数据 · 仅验证渲染与降级", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FixtureGraphs.names.forEach { name ->
                    FilterChip(selected = fixtureName == name, onClick = { fixtureName = name }, label = { Text(name) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = showGraph, onClick = { showGraph = true }, label = { Text("Canvas") }, modifier = Modifier.testTag("canvas-tab"))
                FilterChip(selected = !showGraph, onClick = { showGraph = false }, label = { Text("列表") }, modifier = Modifier.testTag("list-tab"))
                OutlinedButton(onClick = { projection = FixtureGraphs.byName(fixtureName); selectedNodeId = null }) { Text("重新生成") }
                OutlinedButton(onClick = { forceFailure = !forceFailure }) { Text(if (forceFailure) "恢复画布" else "模拟故障") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索节点") },
                modifier = Modifier.fillMaxWidth().testTag("graph-search"),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${projection.nodes.size} 个节点 · ${projection.edges.size} 条原始关系 · ${projection.unresolvedEdges.size} 条缺失端点关系",
                modifier = Modifier.semantics { contentDescription = "图谱统计" },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (projection.isTruncated) {
                Text("已截断：展示 ${projection.nodes.size}/${projection.rawNodeCount} 个节点、${projection.edges.size}/${projection.rawEdgeCount} 条关系", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(4.dp))
            if (showGraph && !forceFailure) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CanvasGraphRenderer().Render(
                        projection = projection,
                        modifier = Modifier.fillMaxSize(),
                        selectedNodeId = selectedNodeId,
                        onNodeSelected = { selectedNodeId = it },
                        onRendererState = { },
                    )
                    Row(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { selectedNodeId = projection.focusNodeId }) { Text("居中焦点") }
                        OutlinedButton(onClick = { projection = FixtureGraphs.byName(fixtureName) }) { Text("重置布局") }
                    }
                }
            } else {
                if (showGraph && forceFailure) {
                    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("Canvas 渲染失败，已自动保留同源列表。", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
                GraphRelationList(
                    nodes = filteredNodes,
                    edges = projection.edges,
                    selectedNodeId = selectedNodeId,
                    onNodeSelected = { selectedNodeId = it },
                    modifier = Modifier.weight(1f),
                )
            }
            selectedNodeId?.let { id ->
                val node = projection.nodes.firstOrNull { it.id == id }
                if (node != null) {
                    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(node.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${node.kind.name} · ${node.id}")
                            projection.edges.filter { it.fromId == id || it.toId == id }.take(4).forEach { edge ->
                                Text("${edge.label}: ${edge.description ?: "无说明"}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphRelationList(
    nodes: List<GraphNode>,
    edges: List<GraphEdge>,
    selectedNodeId: String?,
    onNodeSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.testTag("graph-list").semantics { contentDescription = "图谱关系列表，可滚动" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("节点列表（列表是完整操作面）", style = MaterialTheme.typography.titleSmall) }
        items(nodes, key = { it.id }) { node ->
            val relationCount = edges.count { it.fromId == node.id || it.toId == node.id }
            Card(onClick = { onNodeSelected(node.id) }, modifier = Modifier.fillMaxWidth().testTag("node-${node.id}")) {
                Column(Modifier.padding(10.dp)) {
                    Text("${node.kind.name} · ${node.title}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${node.id} · $relationCount 条关系", style = MaterialTheme.typography.bodySmall)
                    if (selectedNodeId == node.id) Text("已选中", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text("原始关系列表", style = MaterialTheme.typography.titleSmall)
        }
        items(edges, key = { it.relationId }) { edge ->
            val from = nodes.firstOrNull { it.id == edge.fromId }?.title ?: "缺失端点"
            val to = nodes.firstOrNull { it.id == edge.toId }?.title ?: "缺失端点"
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text("${edge.type.name} · $from → $to")
                    Text(edge.description ?: edge.label, style = MaterialTheme.typography.bodySmall)
                    if (edge.evidence != null) Text("证据：${edge.evidence.blockId ?: edge.evidence.sourceUrl ?: "本地"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
