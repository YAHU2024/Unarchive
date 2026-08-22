package com.unarchive.android

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.unarchive.android.ui.components.GlassSurface
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteDocumentRepository
import com.unarchive.android.card.toNoteDocument
import com.unarchive.android.editor.NoteEditorRoute
import com.unarchive.android.ui.state.CreateEvent
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.GraphUiState
import com.unarchive.android.ui.state.MeEvent
import com.unarchive.android.ui.state.MeUiState
import com.unarchive.android.ui.state.NotesEvent
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.UnarchiveUiState

internal object AppRoutes {
    const val CREATE = "create"
    const val NOTES = "notes"
    const val GRAPH = "graph"
    const val ME = "me"
    const val NOTE_EDITOR = "notes/{platform}/{videoId}/{cardVersion}/edit"
    const val LEGACY_TEST = "legacy/test"
    const val LEGACY_RESULTS = "legacy/results"
    const val LEGACY_LOG = "legacy/log"
    const val LEGACY_SETTINGS = "legacy/settings"

    fun noteEditor(document: NoteDocument): String =
        "notes/${Uri.encode(document.cardId.platform)}/${Uri.encode(document.cardId.videoId)}/${Uri.encode(document.generation.cardVersion)}/edit"
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
    onMeEvent: (MeEvent) -> Unit,
    legacyTestContent: @Composable (onBack: () -> Unit, onOpenLogs: () -> Unit) -> Unit,
    legacyResultsContent: @Composable (onBack: () -> Unit) -> Unit,
    legacyLogContent: @Composable (onBack: () -> Unit) -> Unit,
    legacySettingsContent: @Composable (onBack: () -> Unit) -> Unit,
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
                    onOpenDeveloperTest = { navController.navigate(AppRoutes.LEGACY_TEST) },
                )
            }
            composable(AppRoutes.NOTES) {
                NotesScreen(
                    state = state.notes,
                    onEvent = onNotesEvent,
                    onOpenLegacyNotes = { navController.navigate(AppRoutes.LEGACY_RESULTS) },
                    onOpenEditor = { document -> navController.navigate(AppRoutes.noteEditor(document)) },
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
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(AppRoutes.GRAPH) {
                GraphScreen(state = state.graph)
            }
            composable(AppRoutes.ME) {
                MeScreen(
                    state = state.me,
                    onEvent = onMeEvent,
                    onOpenDeveloper = { navController.navigate(AppRoutes.LEGACY_SETTINGS) },
                    onOpenLogs = { navController.navigate(AppRoutes.LEGACY_LOG) },
                    onOpenTest = { navController.navigate(AppRoutes.LEGACY_TEST) },
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
                            .semantics { contentDescription = "B站视频链接或BV号" },
                        enabled = !state.isProcessing,
                        label = { Text("BV 号 / B站链接") },
                        singleLine = false,
                        minLines = 2,
                    )
                    Button(
                        onClick = { onEvent(CreateEvent.ProcessVideo) },
                        enabled = state.videoReference.isNotBlank() && !state.isProcessing,
                        modifier = Modifier.fillMaxWidth(),
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
                        Text(state.statusMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill(label = "最近", selected = true)
                FilterPill(label = "待整理")
                FilterPill(label = "已保存")
            }
        }
        if (state.noteTitles.isEmpty()) {
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("还没有知识笔记", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "从创作页添加一个 B 站视频，生成第一篇可编辑草稿。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            val documents = state.noteDocuments.ifEmpty { state.noteCards.map { it.toNoteDocument() } }
            items(documents, key = { "${it.cardId.value}:${it.generation.cardVersion}" }) { document ->
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenEditor(document) }
                        .testTag("note-card-${document.cardId.value}"),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(document.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${document.source.ownerName} · 点击编辑结构化笔记",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
private fun FilterPill(label: String, selected: Boolean = false) {
    AssistChip(
        onClick = {},
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
            },
        ),
    )
}

@Composable
private fun GraphScreen(state: GraphUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("图谱", style = MaterialTheme.typography.headlineLarge)
        Text(
            "从当前笔记出发，查看可解释的局部连接。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GlassSurface(modifier = Modifier.fillMaxWidth(), emphasized = true) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("局部关系", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (state.noteCount == 0) {
                        "生成第一篇笔记后，这里会显示来源视频、标签和手动关联。"
                    } else {
                        "当前有 ${state.noteCount} 篇笔记，关系图谱将在 D3 接入。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("首期同时提供关系列表，确保放大字体和 TalkBack 下仍可操作。")
            }
        }
    }
}

@Composable
private fun MeScreen(
    state: MeUiState,
    onEvent: (MeEvent) -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenTest: () -> Unit,
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
