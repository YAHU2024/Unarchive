package com.unarchive.android

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.unarchive.android.ui.components.GlassSurface
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteDocumentRepository
import com.unarchive.android.card.CardRelationType
import com.unarchive.android.card.GraphRelationItem
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeSyncState
import com.unarchive.android.card.toNoteDocument
import com.unarchive.android.editor.NoteEditorRoute
import com.unarchive.android.editor.NoteDocumentAiProposalGenerator
import com.unarchive.android.editor.NoteDocumentProposalRepository
import com.unarchive.android.ui.state.CreateEvent
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.DestinationEvent
import com.unarchive.android.ui.state.DestinationTargetOption
import com.unarchive.android.ui.state.DestinationUiState
import com.unarchive.android.ui.state.DestinationOperationState
import com.unarchive.android.ui.state.GraphUiState
import com.unarchive.android.ui.state.GraphEvent
import com.unarchive.android.ui.state.GraphRelationOperationState
import com.unarchive.android.ui.state.MeEvent
import com.unarchive.android.ui.state.MeUiState
import com.unarchive.android.ui.state.NotesEvent
import com.unarchive.android.ui.state.NotesFilter
import com.unarchive.android.ui.state.NotesLibraryItem
import com.unarchive.android.ui.state.NotesLibraryItemKind
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.UnarchiveUiState
import com.unarchive.android.ui.state.buildNotesLibraryItems
import com.unarchive.android.ui.state.forFilter
import com.unarchive.android.ui.state.formatNotesUpdatedAt

internal object AppRoutes {
    const val CREATE = "create"
    const val NOTES = "notes"
    const val GRAPH = "graph"
    const val ME = "me"
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
                val document = state.notes.noteDocuments.firstOrNull {
                    it.cardId.platform == platform &&
                        it.cardId.videoId == videoId &&
                        it.generation.cardVersion == cardVersion
                }
                if (document == null || noteDocumentRepository == null) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("找不到这篇笔记")
                        TextButton(onClick = { navController.popBackStack() }) { Text("返回") }
                    }
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
                    onOpenDeveloper = { navController.navigate(AppRoutes.LEGACY_SETTINGS) },
                    onOpenLogs = { navController.navigate(AppRoutes.LEGACY_LOG) },
                    onOpenTest = { navController.navigate(AppRoutes.LEGACY_TEST) },
                    onOpenSecurity = { navController.navigate(AppRoutes.SECURITY) },
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
private fun CreateScreen(
    state: CreateUiState,
    onEvent: (CreateEvent) -> Unit,
    onOpenNotes: () -> Unit,
    onOpenLatestNote: (NoteDocument) -> Unit,
    onOpenDeveloperTest: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp),
    ) {
        item {
            Text("创作", style = MaterialTheme.typography.headlineLarge)
            Text(
                "把一个 B 站视频，整理成真正能复习和连接的知识笔记。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                emphasized = true,
                contentPadding = PaddingValues(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("添加 B 站视频", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "支持 BV 号、av 号或完整链接。处理完成后，你可以继续编辑 AI 草稿。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.videoReference,
                        onValueChange = { onEvent(CreateEvent.VideoReferenceChanged(it)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("create-video-reference")
                            .semantics { contentDescription = "B站视频链接或BV号" },
                        enabled = !state.isProcessing,
                        label = { Text("BV 号 / B站链接") },
                        singleLine = false,
                        minLines = 2,
                    )
                    Button(
                        onClick = { onEvent(CreateEvent.GenerateNoteDraft) },
                        enabled = state.videoReference.isNotBlank() && !state.isProcessing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("create-generate-note"),
                    ) {
                        Text(if (state.isProcessing) "处理中..." else "开始生成笔记草稿")
                    }
                }
            }
        }
        if (state.isProcessing || state.statusMessage.isNotBlank()) {
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (state.isProcessing) "正在处理视频" else "最近状态",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            state.statusMessage,
                            modifier = Modifier.testTag("create-status"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.isProcessing) {
                            OutlinedButton(onClick = { onEvent(CreateEvent.CancelProcessing) }) {
                                Text("取消处理")
                            }
                        }
                    }
                }
            }
        }
        if (state.hasRecovery) {
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("发现未完成任务", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "已保留可以继续使用的处理中成果。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { onEvent(CreateEvent.ResumeBatch) }) {
                            Text("继续处理")
                        }
                    }
                }
            }
        }
        state.latestNoteDocument?.let { document ->
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("最近的知识笔记", style = MaterialTheme.typography.titleMedium)
                        Text(
                            document.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedButton(
                            onClick = { onOpenLatestNote(document) },
                            enabled = !state.isProcessing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("create-latest-note"),
                        ) {
                            Text("继续编辑笔记")
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onOpenNotes) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("查看笔记")
                }
                TextButton(onClick = onOpenDeveloperTest) {
                    Icon(Icons.Filled.Settings, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("开发者测试")
                }
            }
        }
    }
}

@Composable
private fun NotesScreen(
    state: NotesUiState,
    onEvent: (NotesEvent) -> Unit,
    onOpenLegacyNotes: () -> Unit,
    onOpenEditor: (NoteDocument) -> Unit,
    onOpenDestinations: (NoteDocument) -> Unit,
) {
    var selectedFilter by rememberSaveable { mutableStateOf(NotesFilter.RECENT) }
    val libraryItems = state.libraryItems.ifEmpty {
        buildNotesLibraryItems(state.noteDocuments, state.noteCards, emptyList())
    }
    val visibleItems = libraryItems.forFilter(selectedFilter)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp),
    ) {
        item {
            Text("笔记", style = MaterialTheme.typography.headlineLarge)
            Text(
                "${state.noteCount} 篇已保存 · ${state.materialCount} 项待整理素材",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill(
                    label = "最近",
                    selected = selectedFilter == NotesFilter.RECENT,
                    testTag = "notes-filter-recent",
                    onClick = { selectedFilter = NotesFilter.RECENT },
                )
                FilterPill(
                    label = "待整理",
                    selected = selectedFilter == NotesFilter.NEEDS_ORGANIZING,
                    testTag = "notes-filter-materials",
                    onClick = { selectedFilter = NotesFilter.NEEDS_ORGANIZING },
                )
                FilterPill(
                    label = "已保存",
                    selected = selectedFilter == NotesFilter.SAVED,
                    testTag = "notes-filter-saved",
                    onClick = { selectedFilter = NotesFilter.SAVED },
                )
            }
        }
        if (visibleItems.isEmpty()) {
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            when (selectedFilter) {
                                NotesFilter.RECENT -> "还没有知识笔记"
                                NotesFilter.NEEDS_ORGANIZING -> "没有待整理素材"
                                NotesFilter.SAVED -> "还没有已保存笔记"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            when (selectedFilter) {
                                NotesFilter.RECENT -> "从创作页添加一个 B 站视频，生成第一篇可编辑草稿。"
                                NotesFilter.NEEDS_ORGANIZING -> "已有转写在生成笔记后会从这里移入已保存。"
                                NotesFilter.SAVED -> "先从最近或待整理素材生成一篇笔记草稿。"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(visibleItems, key = NotesLibraryItem::stableKey) { item ->
                NotesLibraryCard(
                    item = item,
                    canGenerateDraft = state.canGenerateDraft,
                    onOpenEditor = onOpenEditor,
                    onOpenDestinations = onOpenDestinations,
                    onGenerateDraft = { material ->
                        onEvent(NotesEvent.GenerateDraft(material.key.platform, material.key.videoId))
                    },
                )
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    onEvent(NotesEvent.Refresh)
                    onOpenLegacyNotes()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开完整笔记库")
            }
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    FilterChip(
        modifier = Modifier.testTag(testTag),
        onClick = onClick,
        selected = selected,
        label = { Text(label) },
    )
}

@Composable
private fun NotesLibraryCard(
    item: NotesLibraryItem,
    canGenerateDraft: Boolean,
    onOpenEditor: (NoteDocument) -> Unit,
    onOpenDestinations: (NoteDocument) -> Unit,
    onGenerateDraft: (com.unarchive.android.result.StoredVideoResult) -> Unit,
) {
    val document = item.document
    val cardModifier = Modifier
        .fillMaxWidth()
        .then(if (document != null) Modifier.clickable { onOpenEditor(document) } else Modifier)
        .testTag("notes-item-${item.stableKey}")
    GlassSurface(modifier = cardModifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NotesThumbnail(item)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.summary.isNotBlank()) {
                        Text(
                            item.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val sourceMetadata = listOfNotNull(
                        item.ownerName.takeIf(String::isNotBlank),
                        item.durationSeconds.takeIf { it > 0L }?.let(::formatNotesDuration),
                        formatNotesUpdatedAt(item.updatedAtEpochMs),
                    ).joinToString(" · ")
                    Text(
                        sourceMetadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.tags.isNotEmpty() || item.relationCount > 0) {
                        val tagSummary = item.tags.take(3).joinToString(" ") { "#$it" }
                        val relationSummary = item.relationCount.takeIf { it > 0 }?.let { "$it 个关联" }
                        Text(
                            listOfNotNull(tagSummary.takeIf(String::isNotBlank), relationSummary).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(
                listOfNotNull(item.localStatusLabel, item.destinationSummary).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.localStatusLabel.contains("失败")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag("notes-item-status-${item.stableKey}"),
            )
            if (document != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpenEditor(document) }) { Text("编辑") }
                    TextButton(onClick = { onOpenDestinations(document) }) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("分享与去向")
                    }
                }
            } else {
                val material = item.material!!
                Button(
                    enabled = canGenerateDraft,
                    onClick = { onGenerateDraft(material) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("notes-generate-${item.stableKey}"),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("生成笔记草稿")
                }
            }
        }
    }
}

@Composable
private fun NotesThumbnail(item: NotesLibraryItem) {
    val bitmap = remember(item.thumbnailPath) {
        item.thumbnailPath?.let(BitmapFactory::decodeFile)
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "${item.title} 的章节截图",
            modifier = Modifier
                .width(96.dp)
                .height(76.dp),
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            modifier = Modifier
                .width(96.dp)
                .height(76.dp),
            shape = MaterialTheme.shapes.small,
            color = if (item.kind == NotesLibraryItemKind.SAVED_NOTE) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (item.kind == NotesLibraryItemKind.SAVED_NOTE) "笔记" else "素材",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun formatNotesDuration(durationSeconds: Long): String {
    val hours = durationSeconds / 3_600L
    val minutes = (durationSeconds % 3_600L) / 60L
    val seconds = durationSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
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
private fun DestinationScreen(
    state: DestinationUiState,
    onEvent: (DestinationEvent) -> Unit,
    onBack: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
) {
    val card = state.card
    if (card == null) {
        LegacyRouteFrame(title = "分享与去向", onBack = onBack) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("找不到这篇本地笔记", style = MaterialTheme.typography.titleLarge)
                Text("本地笔记可能已被移动或尚未完成保存。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag("destination-scroll"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("分享与去向", style = MaterialTheme.typography.headlineSmall)
                    Text(card.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            GlassSurface(modifier = Modifier.fillMaxWidth(), emphasized = true) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地笔记", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.localSaved) "已保存在本机。外部同步失败不会影响这份笔记。"
                        else "本地笔记尚未保存。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { onEvent(DestinationEvent.ExportMarkdown) },
                        enabled = state.localSaved && state.exportState != DestinationOperationState.RUNNING,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("destination-export"),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.exportState == DestinationOperationState.RUNNING) "正在准备分享..." else "导出并分享 Markdown")
                    }
                    if (state.exportMessage.isNotBlank()) {
                        Text(
                            state.exportMessage,
                            color = if (state.exportState == DestinationOperationState.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .testTag("destination-export-status")
                                .semantics { contentDescription = "导出状态：${state.exportMessage}" },
                        )
                    }
                    if (state.exportEmbeddedAssetCount > 0 || state.exportMissingAssetCount > 0) {
                        Text(
                            "图片：已内嵌 ${state.exportEmbeddedAssetCount} 张" +
                                if (state.exportMissingAssetCount > 0) "，${state.exportMissingAssetCount} 张缺失" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            GlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ima", style = MaterialTheme.typography.titleMedium)
                    if (!state.imaConfigured) {
                        Text("尚未配置 ima 凭据。本地笔记不受影响。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onOpenSecuritySettings, modifier = Modifier.fillMaxWidth()) {
                            Text("打开安全设置")
                        }
                    } else {
                        Text(
                            "当前目标：${state.currentTargetLabel}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        state.imaStateWarning?.let { warning ->
                            Text(
                                warning,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("destination-state-warning"),
                            )
                        }
                        DestinationTargetChooser(state.targetOptions, onEvent)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onEvent(DestinationEvent.SyncIma) },
                                enabled = !state.imaSyncing,
                                modifier = Modifier.testTag("destination-sync-ima"),
                            ) { Text(if (state.imaSyncing) "同步中..." else "立即同步") }
                            OutlinedButton(onClick = onOpenSecuritySettings) { Text("管理凭据") }
                        }
                    }
                    if (state.targetRecords.isEmpty()) {
                        Text("尚未同步到任何目标。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("目标状态", style = MaterialTheme.typography.titleSmall)
                        state.targetRecords.forEachIndexed { index, target ->
                            DestinationTargetRow(target, onEvent, index)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationTargetChooser(
    options: List<DestinationTargetOption>,
    onEvent: (DestinationEvent) -> Unit,
) {
    if (options.isEmpty()) {
        Text("连接 ima 后可选择知识库和文件夹。", style = MaterialTheme.typography.bodySmall)
        return
    }
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("更换目标", modifier = Modifier.weight(1f))
            Text("选择")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option.knowledgeBaseName} / ${option.folderName}") },
                    onClick = {
                        onEvent(DestinationEvent.SelectTarget(option.knowledgeBaseId, option.folderId))
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun DestinationTargetRow(
    target: com.unarchive.android.ui.state.DestinationTargetUiState,
    onEvent: (DestinationEvent) -> Unit,
    index: Int,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("destination-target-$index")
            .semantics {
                contentDescription = "${target.targetLabel}，${target.folderLabel}，${target.stateLabel}"
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(target.targetLabel, style = MaterialTheme.typography.titleSmall)
                    Text(target.folderLabel, style = MaterialTheme.typography.bodySmall)
                }
                Text(target.stateLabel, color = destinationStateColor(target.state))
            }
            target.detail?.takeIf(String::isNotBlank)?.let {
                Text("原因：${it.take(160)}", color = MaterialTheme.colorScheme.error, maxLines = 3)
            }
            if (target.canRetry) {
                target.retryRef?.let { ref ->
                    TextButton(onClick = { onEvent(DestinationEvent.RetryIma(ref)) }) {
                        Text(if (target.state == KnowledgeSyncState.BLOCKED) "修复后重试" else "重试")
                    }
                }
            }
        }
    }
}

@Composable
private fun destinationStateColor(state: KnowledgeSyncState) = when (state) {
    KnowledgeSyncState.SYNCED -> MaterialTheme.colorScheme.primary
    KnowledgeSyncState.BLOCKED, KnowledgeSyncState.PERMANENT_FAILURE -> MaterialTheme.colorScheme.error
    KnowledgeSyncState.RETRYABLE_FAILURE -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun MeScreen(
    state: MeUiState,
    onEvent: (MeEvent) -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenTest: () -> Unit,
    onOpenSecurity: () -> Unit,
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
        Text("开发者能力", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = {
                onEvent(MeEvent.OpenDeveloperOptions)
                onOpenDeveloper()
            },
            enabled = state.developerToolsAvailable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("开发者选项")
        }
        OutlinedButton(onClick = onOpenTest, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Edit, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("打开处理测试台")
        }
        OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Info, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("查看运行日志")
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
