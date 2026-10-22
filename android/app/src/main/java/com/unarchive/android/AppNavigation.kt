package com.unarchive.android

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.unarchive.android.card.CardRelationType
import com.unarchive.android.card.FileNoteContentRepository
import com.unarchive.android.card.GraphRelationItem
import com.unarchive.android.card.KnowledgeCardRepository
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteDocumentRepository
import com.unarchive.android.editor.MarkdownAiProposalGenerator
import com.unarchive.android.editor.MarkdownAiProposalRepository
import com.unarchive.android.editor.MarkdownEditorMigrationRoute
import com.unarchive.android.editor.NoteDocumentAiProposalGenerator
import com.unarchive.android.editor.NoteDocumentProposalRepository
import com.unarchive.android.editor.NoteEditorRoute
import com.unarchive.android.editor.markdownImageOptions
import com.unarchive.android.ui.batch.BatchCreateScreen
import com.unarchive.android.ui.components.GlassDock
import com.unarchive.android.ui.components.AppIcons
import com.unarchive.android.ui.components.GlassScaffold
import com.unarchive.android.ui.components.GlassDockItem
import com.unarchive.android.ui.components.AppIcon
import com.unarchive.android.ui.components.LibraryPageHeader
import com.unarchive.android.ui.components.ReadingSurface
import com.unarchive.android.ui.components.RootPage
import com.unarchive.android.ui.components.UnarchiveButton
import com.unarchive.android.ui.components.UnarchiveIconButton
import com.unarchive.android.ui.components.UnarchiveTextButton
import com.unarchive.android.ui.create.CreateScreen
import com.unarchive.android.ui.destination.DestinationScreen
import com.unarchive.android.ui.markdown.cardAssetMarkdownResolver
import com.unarchive.android.ui.me.MeScreen
import com.unarchive.android.ui.me.MoreToolsScreen
import com.unarchive.android.ui.notes.NotesScreen
import com.unarchive.android.ui.notes.NotesLibraryScreen
import com.unarchive.android.ui.state.BatchCreateEvent
import com.unarchive.android.ui.state.CreateEvent
import com.unarchive.android.ui.state.DestinationEvent
import com.unarchive.android.ui.state.GraphEvent
import com.unarchive.android.ui.state.GraphRelationOperationState
import com.unarchive.android.ui.state.GraphUiState
import com.unarchive.android.ui.state.MeEvent
import com.unarchive.android.ui.state.NotesEvent
import com.unarchive.android.ui.state.UnarchiveUiState
import com.unarchive.android.ui.theme.LocalMotionPolicy
import com.unarchive.android.ui.theme.pageEnter
import com.unarchive.android.ui.theme.pageExit

internal object AppRoutes {
    const val CREATE = "create"
    const val BATCH_CREATE = "create/batch"
    const val NOTES = "notes"
    const val NOTES_LIBRARY = "notes/library"
    const val GRAPH = "graph"
    const val ME = "me"
    const val APPEARANCE = "me/appearance"
    const val SETTINGS = "me/settings"
    const val SETTINGS_DETAIL = "me/settings/{section}"
    const val SECURITY_DETAIL = "security/{profile}"
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
    MainDestination(AppRoutes.CREATE, "创作", AppIcons.Add),
    MainDestination(AppRoutes.NOTES, "笔记", AppIcons.Notes),
    MainDestination(AppRoutes.GRAPH, "图谱", AppIcons.GraphNodes),
    MainDestination(AppRoutes.ME, "我的", AppIcons.Me),
)

private fun isMainDestinationChange(initialRoute: String?, targetRoute: String?): Boolean =
    mainDestinations.any { it.route == initialRoute } && mainDestinations.any { it.route == targetRoute }

@Composable
internal fun UnarchiveNavigationHost(
    state: UnarchiveUiState,
    onCreateEvent: (CreateEvent) -> Unit,
    onBatchCreateEvent: (BatchCreateEvent) -> Unit = {},
    onNotesEvent: (NotesEvent) -> Unit,
    onGraphEvent: (GraphEvent) -> Unit = {},
    onDestinationEvent: (DestinationEvent) -> Unit = {},
    onMeEvent: (MeEvent) -> Unit,
    aiProposalGenerator: NoteDocumentAiProposalGenerator? = null,
    proposalRepository: NoteDocumentProposalRepository? = null,
    markdownAiProposalGenerator: MarkdownAiProposalGenerator? = null,
    markdownAiProposalRepository: MarkdownAiProposalRepository? = null,
    legacyTestContent: @Composable (onBack: () -> Unit, onOpenLogs: () -> Unit) -> Unit,
    legacyResultsContent: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        Column(modifier = Modifier.padding(20.dp)) {
            Text("历史结果暂不可用")
            UnarchiveTextButton(onClick = onBack) { Text("返回") }
        }
    },
    legacyLogContent: @Composable (onBack: () -> Unit) -> Unit,
    legacySettingsContent: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        Column(modifier = Modifier.padding(20.dp)) {
            Text("设置暂不可用")
            UnarchiveTextButton(onClick = onBack) { Text("返回") }
        }
    },
    settingsDetailContent: (@Composable (section: com.unarchive.android.ui.settings.SettingsSection, onBack: () -> Unit) -> Unit)? = null,
    appearanceContent: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    securityDetailContent: (@Composable (profile: com.unarchive.android.security.SecurityProfile, onBack: () -> Unit) -> Unit)? = null,
    securityOverviewContent: (@Composable (onBack: () -> Unit, onOpen: (com.unarchive.android.security.SecurityProfile) -> Unit) -> Unit)? = null,
    securityContent: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        Column(modifier = Modifier.padding(20.dp)) {
            Text("安全设置暂不可用")
            UnarchiveTextButton(onClick = onBack) { Text("返回") }
        }
    },
    noteDocumentRepository: NoteDocumentRepository? = null,
    noteContentRepository: FileNoteContentRepository? = null,
    knowledgeCardRepository: KnowledgeCardRepository? = null,
) {
    val navController = rememberNavController()
    val motion = LocalMotionPolicy.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val selectedMainRoute = mainDestinations
        .firstOrNull { it.route == currentRoute }
        ?.route

    GlassScaffold(
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
    ) {
        NavHost(
            navController = navController,
            startDestination = AppRoutes.CREATE,
            enterTransition = {
                motion.pageEnter(
                    forward = true,
                    mainDestination = isMainDestinationChange(initialState.destination.route, targetState.destination.route),
                )
            },
            exitTransition = {
                motion.pageExit(
                    forward = true,
                    mainDestination = isMainDestinationChange(initialState.destination.route, targetState.destination.route),
                )
            },
            popEnterTransition = {
                motion.pageEnter(
                    forward = false,
                    mainDestination = isMainDestinationChange(initialState.destination.route, targetState.destination.route),
                )
            },
            popExitTransition = {
                motion.pageExit(
                    forward = false,
                    mainDestination = isMainDestinationChange(initialState.destination.route, targetState.destination.route),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(AppRoutes.CREATE) {
                CreateScreen(
                    state = state.create,
                    onEvent = onCreateEvent,
                    onOpenNotes = { navController.navigateMainDestination(AppRoutes.NOTES) },
                    onOpenLatestNote = { document -> navController.navigate(AppRoutes.noteEditor(document)) },
                    onOpenBatchCreate = { navController.navigate(AppRoutes.BATCH_CREATE) },
                    onOpenDeveloperTest = { navController.navigate(AppRoutes.LEGACY_TEST) },
                )
            }
            composable(AppRoutes.BATCH_CREATE) {
                BatchCreateScreen(
                    state = state.batchCreate,
                    onEvent = onBatchCreateEvent,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(AppRoutes.NOTES) {
                NotesScreen(
                    state = state.notes,
                    onEvent = onNotesEvent,
                    onOpenCreate = { navController.navigateMainDestination(AppRoutes.CREATE) },
                    onOpenLegacyNotes = { navController.navigate(AppRoutes.NOTES_LIBRARY) },
                    onOpenEditor = { document -> navController.navigate(AppRoutes.noteEditor(document)) },
                    onOpenDestinations = { document ->
                        onDestinationEvent(DestinationEvent.OpenCard(document.cardId.value, document.generation.cardVersion))
                        navController.navigate(AppRoutes.destinations(document))
                    },
                )
            }
            composable(AppRoutes.NOTES_LIBRARY) {
                NotesLibraryScreen(
                    state = state.notes,
                    onBack = { navController.popBackStack() },
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
                        UnarchiveTextButton(onClick = { navController.popBackStack() }) { Text("返回") }
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
                        onOpenDestinations = {
                            onDestinationEvent(DestinationEvent.OpenCard(document.cardId.value, document.generation.cardVersion))
                            navController.navigate(AppRoutes.destinations(document))
                        },
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
                        onOpenDestinations = {
                            onDestinationEvent(DestinationEvent.OpenCard(document.cardId.value, document.generation.cardVersion))
                            navController.navigate(AppRoutes.destinations(document))
                        },
                    )
                }
            }
            composable(AppRoutes.GRAPH) {
                GraphScreen(
                    state = state.graph,
                    onEvent = onGraphEvent,
                    onOpenNotes = { navController.navigateMainDestination(AppRoutes.NOTES) },
                )
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
                    onOpenAppearance = { navController.navigate(AppRoutes.APPEARANCE) },
                    onOpenSettings = { navController.navigate(AppRoutes.SETTINGS) },
                    noteCount = state.notes.noteCount,
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
            composable(AppRoutes.APPEARANCE) {
                if (appearanceContent != null) appearanceContent { navController.popBackStack() }
                else legacySettingsContent { navController.popBackStack() }
            }
            composable(AppRoutes.SETTINGS) {
                com.unarchive.android.ui.settings.SettingsOverviewScreen({ navController.popBackStack() }) { section -> navController.navigate("me/settings/${section.name}") }
            }
            composable(AppRoutes.SETTINGS_DETAIL) { entry ->
                val section = com.unarchive.android.ui.settings.SettingsSection.entries.firstOrNull { it.name == entry.arguments?.getString("section") }
                if (section != null && settingsDetailContent != null) settingsDetailContent(section) { navController.popBackStack() }
                else legacySettingsContent { navController.popBackStack() }
            }
            composable(AppRoutes.SECURITY) {
                if (securityOverviewContent != null) securityOverviewContent({ navController.popBackStack() }) { profile -> navController.navigate("security/${profile.name}") }
                else securityContent { navController.popBackStack() }
            }
            composable(AppRoutes.SECURITY_DETAIL) { entry ->
                val profile = com.unarchive.android.security.SecurityProfile.entries.firstOrNull { it.name == entry.arguments?.getString("profile") }
                if (profile != null && securityDetailContent != null) securityDetailContent(profile) { navController.popBackStack() }
                else securityContent { navController.popBackStack() }
            }
        }
    }
}

@Composable
private fun AppBottomNavigation(
    currentRoute: String,
    onSelect: (MainDestination) -> Unit,
) {
    GlassDock(
        selectedIndex = mainDestinations.indexOfFirst { it.route == currentRoute },
        itemCount = mainDestinations.size,
    ) {
        mainDestinations.forEach { destination ->
            GlassDockItem(
                label = destination.label,
                icon = destination.icon,
                selected = currentRoute == destination.route,
                testTag = "bottom-nav-${destination.route}",
                onClick = { onSelect(destination) },
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
    onOpenNotes: () -> Unit = {},
) {
    RootPage(
        title = "图谱",
        modifier = Modifier
            .fillMaxSize()
            .testTag("graph-scroll"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
    ) {
        item {
            LibraryPageHeader("图谱")
        }
        if (state.noteDocuments.isEmpty()) {
            item {
                GraphEmptyState(onOpenNotes = onOpenNotes)
            }
        } else {
            item {
                Text(
                    "当前笔记",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("局部关系", style = MaterialTheme.typography.titleMedium)
                    GraphPreview(state)
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("关系列表", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${state.relationCount} 条一跳关系 · 图形不可用时仍可操作",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    UnarchiveButton(
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
                    ReadingSurface(modifier = Modifier.fillMaxWidth()) {
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
                    ReadingSurface(modifier = Modifier.fillMaxWidth()) {
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
private fun GraphEmptyState(onOpenNotes: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .testTag("graph-empty-state")
            .semantics { contentDescription = "图谱为空：从一条关系开始" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppIcon(
            imageVector = AppIcons.GraphNodes,
            contentDescription = null,
            modifier = Modifier.height(48.dp).width(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "从一条关系开始",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "在笔记中连接相关内容，\n在这里查看它们的联系。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        UnarchiveButton(
            onClick = onOpenNotes,
            modifier = Modifier
                .padding(top = 16.dp)
                .testTag("graph-empty-open-notes"),
        ) {
            Text("查看笔记")
        }
    }
}

@Composable
private fun GraphPreview(state: GraphUiState) {
    val nodes = (state.outgoing.map { it.targetTitle } + state.incoming.map { it.sourceTitle })
        .distinct()
    val relationLineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    ReadingSurface(
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
    ReadingSurface(modifier = Modifier.fillMaxWidth().testTag("graph-relation-editor")) {
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
                UnarchiveButton(
                    onClick = { onEvent(GraphEvent.CreateUserLink) },
                    enabled = state.targetCardIdInput.isNotBlank() &&
                        state.operationState != GraphRelationOperationState.SAVING,
                    modifier = Modifier.testTag("graph-save-relation"),
                ) { Text(if (state.operationState == GraphRelationOperationState.SAVING) "保存中..." else "保存关联") }
                UnarchiveTextButton(onClick = { onEvent(GraphEvent.CancelAddRelation) }) { Text("取消") }
            }
        }
    }
}

@Composable
private fun RelationListItem(
    item: GraphRelationItem,
    onEvent: (GraphEvent) -> Unit,
) {
    ReadingSurface(
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
                    UnarchiveTextButton(
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
internal fun LegacyRouteFrame(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    com.unarchive.android.ui.components.SecondaryPage(title, onBack) {
        content()
    }
}
