package com.unarchive.android

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.unarchive.android.ui.components.GlassSurface
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteDocumentRepository
import com.unarchive.android.card.FileNoteContentRepository
import com.unarchive.android.card.KnowledgeCardRepository
import com.unarchive.android.card.CardRelationType
import com.unarchive.android.card.GraphRelationItem
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.toNoteDocument
import com.unarchive.android.editor.NoteEditorRoute
import com.unarchive.android.editor.MarkdownEditorMigrationRoute
import com.unarchive.android.editor.markdownImageOptions
import com.unarchive.android.ui.markdown.cardAssetMarkdownResolver
import com.unarchive.android.editor.NoteDocumentAiProposalGenerator
import com.unarchive.android.editor.NoteDocumentProposalRepository
import com.unarchive.android.editor.MarkdownAiProposalGenerator
import com.unarchive.android.editor.MarkdownAiProposalRepository
import com.unarchive.android.ui.create.CreateScreen
import com.unarchive.android.ui.state.CreateEvent
import com.unarchive.android.ui.state.DestinationEvent
import com.unarchive.android.ui.state.DestinationUiState
import com.unarchive.android.ui.state.GraphUiState
import com.unarchive.android.ui.state.GraphEvent
import com.unarchive.android.ui.state.GraphRelationOperationState
import com.unarchive.android.ui.state.MeEvent
import com.unarchive.android.ui.state.MeUiState
import com.unarchive.android.ui.state.NotesEvent
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.UnarchiveUiState
import com.unarchive.android.ui.destination.DestinationScreen
import com.unarchive.android.ui.notes.NotesScreen

internal object AppRoutes {
    const val CREATE = "create"
    const val NOTES = "notes"
    const val GRAPH = "graph"
    const val ME = "me"
    const val MORE_TOOLS = "more-tools"
    const val NOTE_EDITOR = "notes/{platform}/{videoId}/{cardVersion}/edit"
    const val DESTINATIONS = "destinations/{platform}/{videoId}/{cardVersion}"
    const val LEGACY_TEST = "legacy/test"
    const val LEGACY_RESULTS = "legacy/results"
    const val LEGACY_LOG = "legacy/log"
    const val LEGACY_SETTINGS = "legacy/settings"
    const val SECURITY = "me/security"

    fun noteEditor(document: NoteDocument): String =
        "notes/${Uri.encode(document.cardId.platform)}/${Uri.encode(document.cardId.videoId)}/${Uri.encode(document.generation.cardVersion)}/edit"

    fun destinations(document: NoteDocument): String =
        "destinations/${Uri.encode(document.cardId.platform)}/${Uri.encode(document.cardId.videoId)}/${Uri.encode(document.generation.cardVersion)}"
}

private data class MainDestination(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val mainDestinations = listOf(
    MainDestination(AppRoutes.CREATE, "创作", Icons.Filled.Edit),
    MainDestination(AppRoutes.NOTES, "笔记", Icons.AutoMirrored.Filled.List),
    MainDestination(AppRoutes.GRAPH, "图谱", Icons.Filled.Share),
    MainDestination(AppRoutes.ME, "我的", Icons.Filled.AccountCircle),
)

@Composable
internal fun UnarchiveNavigationHost(
    state: UnarchiveUiState,
    onCreateEvent: (CreateEvent) -> Unit,
    onNotesEvent: (NotesEvent) -> Unit,
    onGraphEvent: (GraphEvent) -> Unit = {},
    onDestinationEvent: (DestinationEvent) -> Unit = {},
    onMeEvent: (MeEvent) -> Unit,
    aiProposalGenerator: NoteDocumentAiProposalGenerator? = null,
    proposalRepository: NoteDocumentProposalRepository? = null,
    markdownAiProposalGenerator: MarkdownAiProposalGenerator? = null,
    markdownAiProposalRepository: MarkdownAiProposalRepository? = null,
    legacyTestContent: @Composable (onBack: () -> Unit, onOpenLogs: () -> Unit) -> Unit,
    legacyResultsContent: @Composable (onBack: () -> Unit) -> Unit,
    legacyLogContent: @Composable (onBack: () -> Unit) -> Unit,
    legacySettingsContent: @Composable (onBack: () -> Unit) -> Unit,
    securityContent: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        Column(modifier = Modifier.padding(20.dp)) {
            Text("安全设置暂不可用")
            TextButton(onClick = onBack) { Text("返回") }
        }
    },
    noteDocumentRepository: NoteDocumentRepository? = null,
    noteContentRepository: FileNoteContentRepository? = null,
    knowledgeCardRepository: KnowledgeCardRepository? = null,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val selectedMainRoute = mainDestinations
        .firstOrNull { it.route == currentRoute }
        ?.route

    Scaffold(
        bottomBar = {
            if (selectedMainRoute != null) {
                AppBottomNavigation(
                    currentRoute = selectedMainRoute,
                    onSelect = { destination ->
                        navController.navigateMainDestination(destination.route)
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppRoutes.CREATE,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(AppRoutes.CREATE) {
                CreateScreen(
                    state = state.create,
                    onEvent = onCreateEvent,
                    onOpenNotes = { navController.navigateMainDestination(AppRoutes.NOTES) },
                    onOpenLatestNote = { document -> navController.navigate(AppRoutes.noteEditor(document)) },
                    onOpenDeveloperTest = { navController.navigate(AppRoutes.LEGACY_TEST) },
                )
            }
            composable(AppRoutes.NOTES) {
                NotesScreen(
                    state = state.notes,
                    onEvent = onNotesEvent,
                    onOpenLegacyNotes = { navController.navigate(AppRoutes.LEGACY_RESULTS) },
                    onOpenEditor = { document -> navController.navigate(AppRoutes.noteEditor(document)) },
                    onOpenDestinations = { document ->
                        onDestinationEvent(DestinationEvent.OpenCard(document.cardId.value, document.generation.cardVersion))
                        navController.navigate(AppRoutes.destinations(document))
                    },
                )
            }
            composable(AppRoutes.NOTE_EDITOR) { entry ->
                val platform = entry.arguments?.getString("platform")
                val videoId = entry.arguments?.getString("videoId")
                val cardVersion = entry.arguments?.getString("cardVersion")
                val document = state.notes.noteDocumentVersions
                    .ifEmpty { state.notes.noteDocuments }
                    .firstOrNull {
                    it.cardId.platform == platform &&
                        it.cardId.videoId == videoId &&
                        it.generation.cardVersion == cardVersion
                }
                if (document == null || noteDocumentRepository == null) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("找不到这篇笔记")
                        TextButton(onClick = { navController.popBackStack() }) { Text("返回") }
                    }
                } else if (noteContentRepository != null) {
                    val card = state.notes.noteCards.firstOrNull { candidate ->
                        candidate.cardId == document.cardId &&
                            candidate.cardVersion == document.generation.cardVersion
                    }
                    MarkdownEditorMigrationRoute(
                        document = document,
                        contentRepository = noteContentRepository,
                        documentRepository = noteDocumentRepository,
                        aiProposalGenerator = markdownAiProposalGenerator,
                        aiProposalRepository = markdownAiProposalRepository,
                        onBack = { navController.popBackStack() },
                        assetResolver = if (card != null && knowledgeCardRepository != null) {
                            cardAssetMarkdownResolver(card, knowledgeCardRepository)
                        } else {
                            com.unarchive.android.ui.markdown.NoOpMarkdownAssetResolver
                        },
                        imageOptions = card?.assets?.let(::markdownImageOptions).orEmpty(),
                    )
                } else {
                    NoteEditorRoute(
                        document = document,
                        repository = noteDocumentRepository,
                        aiProposalGenerator = aiProposalGenerator,
                        proposalRepository = proposalRepository,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(AppRoutes.GRAPH) {
                GraphScreen(state = state.graph, onEvent = onGraphEvent)
            }
            composable(AppRoutes.DESTINATIONS) { entry ->
                val card = state.destinations.card ?: run {
                    val platform = entry.arguments?.getString("platform")
                    val videoId = entry.arguments?.getString("videoId")
                    val cardVersion = entry.arguments?.getString("cardVersion")
                    state.notes.noteCards.firstOrNull {
                        it.cardId.platform == platform &&
                            it.cardId.videoId == videoId &&
                            it.cardVersion == cardVersion
                    }
                }
                if (state.destinations.card == null && card != null) {
                    LaunchedEffect(card.cardId.value, card.cardVersion) {
                        onDestinationEvent(DestinationEvent.OpenCard(card.cardId.value, card.cardVersion))
                    }
                }
                DestinationScreen(
                    state = state.destinations.copy(card = card),
                    onEvent = onDestinationEvent,
                    onBack = { navController.popBackStack() },
                    onOpenSecuritySettings = { navController.navigate(AppRoutes.SECURITY) },
                )
            }
            composable(AppRoutes.ME) {
                MeScreen(
                    state = state.me,
                    onEvent = onMeEvent,
                    onOpenSecurity = { navController.navigate(AppRoutes.SECURITY) },
                    onOpenMoreTools = { navController.navigate(AppRoutes.MORE_TOOLS) },
                )
            }
            composable(AppRoutes.MORE_TOOLS) {
                MoreToolsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTest = { navController.navigate(AppRoutes.LEGACY_TEST) },
                    onOpenResults = { navController.navigate(AppRoutes.LEGACY_RESULTS) },
                    onOpenLogs = { navController.navigate(AppRoutes.LEGACY_LOG) },
                    onOpenSettings = { navController.navigate(AppRoutes.LEGACY_SETTINGS) },
                )
            }
            composable(AppRoutes.LEGACY_TEST) {
                legacyTestContent(
                    { navController.popBackStack() },
                    { navController.navigate(AppRoutes.LEGACY_LOG) },
                )
            }
            composable(AppRoutes.LEGACY_RESULTS) {
                legacyResultsContent { navController.popBackStack() }
            }
            composable(AppRoutes.LEGACY_LOG) {
                legacyLogContent { navController.popBackStack() }
            }
            composable(AppRoutes.LEGACY_SETTINGS) {
                legacySettingsContent { navController.popBackStack() }
            }
            composable(AppRoutes.SECURITY) {
                securityContent { navController.popBackStack() }
            }
        }
    }
}

@Composable
private fun AppBottomNavigation(
    currentRoute: String,
    onSelect: (MainDestination) -> Unit,
) {
    NavigationBar(modifier = Modifier.navigationBarsPadding()) {
        mainDestinations.forEach { destination ->
            NavigationBarItem(
                modifier = Modifier.testTag("bottom-nav-${destination.route}"),
                selected = currentRoute == destination.route,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = "导航：${destination.label}",
                    )
                },
                label = { Text(destination.label) },
            )
        }
    }
}

private fun NavHostController.navigateMainDestination(route: String) {
    navigate(route) {
        popUpTo(AppRoutes.CREATE) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun GraphScreen(
    state: GraphUiState,
    onEvent: (GraphEvent) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag("graph-scroll"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp),
    ) {
        item {
            Text("图谱", style = MaterialTheme.typography.headlineLarge)
            Text(
                "从一篇笔记出发，查看可解释、可编辑的一跳连接。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.noteDocuments.isEmpty()) {
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), emphasized = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("局部关系", style = MaterialTheme.typography.titleLarge)
                        Text("还没有可显示的关系", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "先从创作页生成一篇笔记，再添加第一个关联。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            item {
                Text("当前笔记", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.noteDocuments.forEach { document ->
                        AssistChip(
                            onClick = { onEvent(GraphEvent.SelectNote(document.cardId.value)) },
                            label = { Text(document.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag("graph-note-${document.cardId.value}"),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (document.cardId.value == state.selectedCardId) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
                                },
                            ),
                        )
                    }
                }
            }
            item {
                GraphPreview(state)
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("关系列表", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${state.relationCount} 条一跳关系 · 图形不可用时仍可操作",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { onEvent(GraphEvent.StartAddRelation) },
                        enabled = !state.isAddingRelation && state.noteDocuments.size > 1,
                        modifier = Modifier.testTag("graph-add-relation"),
                    ) { Text("添加关联") }
                }
            }
            if (state.isAddingRelation) {
                item {
                    RelationEditor(state = state, onEvent = onEvent)
                }
            }
            if (state.errorMessage != null) {
                item {
                    GlassSurface(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            state.errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .testTag("graph-error")
                                .semantics { contentDescription = "关系操作失败：${state.errorMessage}" },
                        )
                    }
                }
            }
            if (state.operationState == GraphRelationOperationState.SAVED) {
                item {
                    Text(
                        "关系已保存",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .testTag("graph-saved")
                            .semantics { contentDescription = "关系操作状态：已保存" },
                    )
                }
            }
            if (state.outgoing.isEmpty() && state.incoming.isEmpty()) {
                item {
                    GlassSurface(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "还没有可显示的关系。可以先添加一个用户关联。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(state.outgoing, key = { "out-${it.relation.relationId}" }) { item ->
                    RelationListItem(item, onEvent)
                }
                items(state.incoming, key = { "in-${it.relation.relationId}-${it.sourceCardId}" }) { item ->
                    RelationListItem(item, onEvent)
                }
            }
        }
    }
}

@Composable
private fun GraphPreview(state: GraphUiState) {
    val nodes = (state.outgoing.map { it.targetTitle } + state.incoming.map { it.sourceTitle })
        .distinct()
    val relationLineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(188.dp)
            .testTag("graph-one-hop")
            .semantics {
                contentDescription = buildString {
                    append("当前笔记局部图谱：${state.selectedTitle.orEmpty()}。")
                    if (nodes.isEmpty()) append("暂无连接。")
                    else append("连接到：${nodes.joinToString("、") }。")
                }
            },
        emphasized = true,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val centerY = size.height / 2f
                val centerX = size.width / 2f
                nodes.forEachIndexed { index, _ ->
                    val x = size.width * (0.18f + (index % 4) * 0.22f)
                    drawLine(
                        color = relationLineColor,
                        start = androidx.compose.ui.geometry.Offset(centerX, centerY),
                        end = androidx.compose.ui.geometry.Offset(x, 48f + (index / 4) * 82f),
                        strokeWidth = 3f,
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    state.selectedTitle.orEmpty(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                nodes.take(4).forEach { node ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            node,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RelationEditor(
    state: GraphUiState,
    onEvent: (GraphEvent) -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth().testTag("graph-relation-editor")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("添加用户关联", style = MaterialTheme.typography.titleMedium)
            Text(
                "关系只写入当前笔记，不会修改目标笔记或外部副本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("选择目标笔记", style = MaterialTheme.typography.labelLarge)
            state.noteDocuments
                .filter { it.cardId.value != state.selectedCardId }
                .forEach { document ->
                    AssistChip(
                        onClick = { onEvent(GraphEvent.TargetChanged(document.cardId.value)) },
                        label = { Text(document.title) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("graph-target-${document.cardId.value}"),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (document.cardId.value == state.targetCardIdInput) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
                            },
                        ),
                    )
                }
            OutlinedTextField(
                value = state.relationLabelInput,
                onValueChange = { onEvent(GraphEvent.LabelChanged(it)) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "关系名称" },
                label = { Text("关系名称（可选）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = state.relationDescriptionInput,
                onValueChange = { onEvent(GraphEvent.DescriptionChanged(it)) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "关系说明" },
                label = { Text("关系说明（可选）") },
                minLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onEvent(GraphEvent.CreateUserLink) },
                    enabled = state.targetCardIdInput.isNotBlank() &&
                        state.operationState != GraphRelationOperationState.SAVING,
                    modifier = Modifier.testTag("graph-save-relation"),
                ) { Text(if (state.operationState == GraphRelationOperationState.SAVING) "保存中..." else "保存关联") }
                TextButton(onClick = { onEvent(GraphEvent.CancelAddRelation) }) { Text("取消") }
            }
        }
    }
}

@Composable
private fun RelationListItem(
    item: GraphRelationItem,
    onEvent: (GraphEvent) -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("graph-relation-${item.relation.relationId}")
            .semantics {
                contentDescription = buildString {
                    val displayTitle = if (item.direction.name == "INCOMING") item.sourceTitle else item.targetTitle
                    append(if (item.direction.name == "INCOMING") "反向链接：" else "关系：")
                    append(displayTitle)
                    append("，${relationTypeLabel(item.relation.type)}")
                    if (item.relation.description.isNotBlank()) append("，${item.relation.description}")
                }
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (item.direction.name == "INCOMING") "反向链接 · ${item.sourceTitle}" else item.targetTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        relationTypeLabel(item.relation.type),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.canDelete && item.direction.name == "OUTGOING") {
                    TextButton(
                        onClick = { onEvent(GraphEvent.RemoveRelation(item.relation.relationId)) },
                        modifier = Modifier.testTag("graph-remove-${item.relation.relationId}"),
                    ) { Text("移除") }
                } else {
                    Text("来源事实", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (item.relation.description.isNotBlank()) {
                Text(item.relation.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun relationTypeLabel(type: CardRelationType): String = when (type) {
    CardRelationType.USER_LINK -> "用户关联"
    CardRelationType.TAG -> "标签关系"
    CardRelationType.SOURCE_VIDEO -> "来源视频"
    CardRelationType.FAVORITE_FOLDER -> "收藏夹关系"
}

@Composable
private fun MeScreen(
    state: MeUiState,
    onEvent: (MeEvent) -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenMoreTools: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("我的", style = MaterialTheme.typography.headlineLarge)
        Text(
            "管理同步去向、本地能力和开发者工具。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("本地优先", style = MaterialTheme.typography.titleMedium)
                Text("笔记先保存在本机；同步和导出由你明确触发。")
            }
        }
        OutlinedButton(onClick = onOpenSecurity, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("安全设置")
        }
        OutlinedButton(
            onClick = {
                onEvent(MeEvent.OpenDeveloperOptions)
                onOpenMoreTools()
            },
            enabled = state.developerToolsAvailable,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("me-more-tools"),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("更多工具")
        }
    }
}

@Composable
private fun MoreToolsScreen(
    onBack: () -> Unit,
    onOpenTest: () -> Unit,
    onOpenResults: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LegacyRouteFrame(title = "更多工具", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "高级处理、历史数据和诊断入口集中在这里。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onOpenTest,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("more-tools-test"),
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("打开处理测试台")
            }
            OutlinedButton(
                onClick = onOpenResults,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("more-tools-results"),
            ) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("查看历史结果")
            }
            OutlinedButton(
                onClick = onOpenLogs,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("more-tools-logs"),
            ) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("查看运行日志")
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("more-tools-settings"),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("开发者选项")
            }
        }
    }
}

@Composable
internal fun LegacyRouteFrame(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                )
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}
