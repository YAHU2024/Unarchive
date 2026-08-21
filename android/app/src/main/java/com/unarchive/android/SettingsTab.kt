package com.unarchive.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.model.ModelManagementSection
import com.unarchive.android.storage.formatStorageBytes
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun SettingsTab(vm: UnarchiveViewModel) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)

        BilibiliLoginSection(
            authStore = vm.authStore,
            loginClient = vm.loginClient,
            loggedIn = vm.loggedIn,
            onLoggedIn = { vm.onLoggedIn() },
            onLoggedOut = { vm.onLoggedOut() },
        )

        HorizontalDivider()

        Text("识别引擎", style = MaterialTheme.typography.titleMedium)
        AsrEngineKind.entries.filter { it.selectable && it.available }.forEach { engine ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = engine == vm.selectedEngine,
                    onClick = { vm.setEngine(engine) },
                    enabled = vm.runningJob == null,
                )
                Text(engine.displayName)
            }
        }

        HorizontalDivider()

        Text("推理线程数（性能对比用）", style = MaterialTheme.typography.titleMedium)
        listOf(null to "自动（按大核数）", 1 to "1", 2 to "2", 4 to "4").forEach { (threads, label) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = vm.selectedThreads == threads,
                    onClick = { vm.setThreads(threads) },
                    enabled = vm.runningJob == null,
                )
                Text(label)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("启用 VAD 静音检测（实验性，可能崩溃）")
            Spacer(Modifier.weight(1f))
            Switch(
                checked = vm.selectedEnableVad,
                onCheckedChange = { vm.setEnableVad(it) },
                enabled = vm.runningJob == null,
            )
        }

        Text(
            "VAD 最大段长：${vm.selectedVadMaxSeconds} 秒（越小越精细，停顿更平滑）",
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = vm.selectedVadMaxSeconds.toFloat(),
            onValueChange = { vm.selectedVadMaxSeconds = it.roundToInt() },
            enabled = vm.runningJob == null,
            valueRange = 1f..30f,
            steps = 28,
        )

        HorizontalDivider()

        Text("ima 同步（单卡）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = vm.imaClientIdInput, onValueChange = { vm.imaClientIdInput = it }, modifier = Modifier.fillMaxWidth(), enabled = vm.runningJob == null && vm.generateJob == null, label = { Text("ima Client ID") })
        OutlinedTextField(value = vm.imaApiKeyInput, onValueChange = { vm.imaApiKeyInput = it }, modifier = Modifier.fillMaxWidth(), enabled = vm.runningJob == null && vm.generateJob == null, label = { Text("ima API Key") }, visualTransformation = PasswordVisualTransformation())
        ImaKnowledgeBaseSelector(vm)
        ImaFolderSelector(vm)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = vm.runningJob == null && vm.generateJob == null, onClick = vm::saveImaSettings) { Text("保存 ima 设置") }
            OutlinedButton(enabled = vm.runningJob == null && vm.generateJob == null, onClick = vm::checkImaConnection) { Text("检查连接") }
            OutlinedButton(enabled = vm.runningJob == null && vm.generateJob == null, onClick = vm::discoverImaFolders) { Text("查看文件夹") }
            OutlinedButton(enabled = vm.runningJob == null && vm.generateJob == null, onClick = vm::clearImaSettings) { Text("清除 ima") }
        }
        if (vm.imaDiscoveryStatus.isNotBlank()) Text(vm.imaDiscoveryStatus, style = MaterialTheme.typography.bodySmall)
        if (vm.imaFolderDiscoveryStatus.isNotBlank()) Text(vm.imaFolderDiscoveryStatus, style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("云端转写（可选）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = vm.siliconFlowKeyInput,
            onValueChange = { vm.siliconFlowKeyInput = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = vm.runningJob == null,
            label = { Text("SiliconFlow API Key") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                enabled = vm.runningJob == null && vm.siliconFlowKeyInput.isNotBlank(),
                onClick = { vm.saveSiliconFlowKey() },
            ) {
                Text("保存 Key")
            }
            OutlinedButton(
                enabled = vm.runningJob == null,
                onClick = { vm.clearSiliconFlowKey() },
            ) {
                Text("清除")
            }
        }

        HorizontalDivider()

        Text("AI 卡片（可选）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = vm.apiKeyInput,
            onValueChange = { vm.apiKeyInput = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = vm.runningJob == null && vm.generateJob == null,
            label = { Text("DeepSeek API Key") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                enabled = vm.runningJob == null && vm.generateJob == null && vm.apiKeyInput.isNotBlank(),
                onClick = { vm.saveApiKey() },
            ) {
                Text("保存 Key")
            }
            OutlinedButton(
                enabled = vm.runningJob == null && vm.generateJob == null,
                onClick = { vm.clearApiKey() },
            ) {
                Text("清除")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("思考模式（更慢但更深入）")
            Spacer(Modifier.weight(1f))
            Switch(
                checked = vm.thinkingEnabled,
                onCheckedChange = { vm.updateThinkingEnabled(it) },
                enabled = vm.runningJob == null && vm.generateJob == null,
            )
        }

        HorizontalDivider()

        StorageManagementSection(vm)

        HorizontalDivider()

        ModelManagementSection(
            modelsDirectory = File(context.filesDir, "models"),
            enabled = vm.runningJob == null,
        )
    }
}

@Composable
private fun StorageManagementSection(vm: UnarchiveViewModel) {
    Text("存储", style = MaterialTheme.typography.titleMedium)
    val snapshot = vm.storageSnapshot
    when {
        vm.storageLoading && snapshot == null -> Text("正在读取存储信息……", style = MaterialTheme.typography.bodySmall)
        snapshot != null -> {
            Text(
                "设备可用 ${formatStorageBytes(snapshot.freeBytes)} · 当前可分配 ${formatStorageBytes(snapshot.allocatableBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "应用：代码 ${formatStorageBytes(snapshot.appCodeBytes)} · 数据 ${formatStorageBytes(snapshot.appDataBytes)} · 缓存 ${formatStorageBytes(snapshot.appCacheBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "持久数据：模型 ${formatStorageBytes(snapshot.modelsBytes)} · 知识卡片 ${formatStorageBytes(snapshot.knowledgeCardsBytes)} · 转录结果 ${formatStorageBytes(snapshot.resultsBytes)} · 日志 ${formatStorageBytes(snapshot.logsBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "可重建缓存：音频 ${formatStorageBytes(snapshot.audioCacheBytes)} · 解码 ${formatStorageBytes(snapshot.decodedCacheBytes)} · 导出 ${formatStorageBytes(snapshot.exportCacheBytes)} · 视频 ${formatStorageBytes(snapshot.videoCacheBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        vm.storageError.isNotBlank() -> Text(vm.storageError, color = MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = vm::refreshStorageStats, enabled = !vm.storageLoading) {
            Text(if (vm.storageLoading) "正在刷新" else "刷新存储信息")
        }
        OutlinedButton(onClick = vm::openSystemStorageSettings) {
            Text("系统存储设置")
        }
    }
    OutlinedButton(
        onClick = vm::clearRebuildableCache,
        enabled = !vm.cacheClearing && vm.runningJob == null && vm.generateJob == null && vm.exportJob == null && !vm.imaSyncing,
    ) {
        Text(if (vm.cacheClearing) "正在清理" else "清理可重建缓存")
    }
    Text(
        "仅清理导出临时文件、解码缓存、音频缓存和视频缓存；不会删除模型、知识卡片、转录结果、批次恢复信息或凭据。",
        style = MaterialTheme.typography.bodySmall,
    )
    if (vm.cacheClearStatus.isNotBlank()) {
        Text(vm.cacheClearStatus, style = MaterialTheme.typography.bodySmall)
    }

    Text(
        "缓存预算：${formatStorageBytes(vm.effectiveCacheBudgetBytes)}",
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        "预算只管理可重建缓存，不会自动删除模型、知识卡片或转录结果。",
        style = MaterialTheme.typography.bodySmall,
    )
    val options = listOf(0L to "自动（容量的 2%，1～4 GiB）", 1L to "1 GiB", 2L to "2 GiB", 4L to "4 GiB", 8L to "8 GiB")
    options.forEach { (gibibytes, label) ->
        val bytes = gibibytes * 1024L * 1024L * 1024L
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = if (gibibytes == 0L) vm.configuredCacheBudgetBytes == 0L else vm.configuredCacheBudgetBytes == bytes,
                onClick = {
                    if (gibibytes == 0L) vm.useAutomaticCacheBudget() else vm.usePresetCacheBudget(gibibytes)
                },
            )
            Text(label)
        }
    }
    OutlinedTextField(
        value = vm.customCacheBudgetInput,
        onValueChange = { vm.customCacheBudgetInput = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("自定义缓存预算（GiB）") },
        supportingText = { Text("允许范围 0.5～16 GiB") },
        singleLine = true,
    )
    OutlinedButton(onClick = vm::saveCustomCacheBudget) { Text("保存自定义预算") }
    if (vm.cacheBudgetStatus.isNotBlank()) {
        Text(vm.cacheBudgetStatus, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ImaKnowledgeBaseSelector(vm: UnarchiveViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = vm.imaKnowledgeBases.firstOrNull { it.id == vm.imaKnowledgeBaseId }?.name
        ?.takeIf(String::isNotBlank)
        ?: if (vm.imaKnowledgeBases.isEmpty()) "请先点击“检查连接”" else "请选择测试知识库"
    val enabled = vm.runningJob == null && vm.generateJob == null && vm.imaKnowledgeBases.isNotEmpty()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("测试知识库", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onClick = { expanded = true },
        ) {
            Text(selectedName, modifier = Modifier.weight(1f), maxLines = 1)
            androidx.compose.material3.Icon(Icons.Default.ArrowDropDown, contentDescription = "选择测试知识库")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            vm.imaKnowledgeBases.forEach { base ->
                DropdownMenuItem(text = { Text(base.name.ifBlank { "未命名知识库" }) }, onClick = {
                    vm.selectImaKnowledgeBase(base.id)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun ImaFolderSelector(vm: UnarchiveViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = vm.imaFolders.firstOrNull { it.id == vm.imaFolderId }?.name
        ?.takeIf(String::isNotBlank) ?: "根目录"
    val enabled = vm.runningJob == null && vm.generateJob == null && vm.imaKnowledgeBaseId.isNotBlank()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("目标文件夹", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onClick = { expanded = true },
        ) {
            Text(selectedName, modifier = Modifier.weight(1f), maxLines = 1)
            androidx.compose.material3.Icon(Icons.Default.ArrowDropDown, contentDescription = "选择目标文件夹")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("根目录") }, onClick = { vm.selectImaFolder(""); expanded = false })
            vm.imaFolders.forEach { folder ->
                DropdownMenuItem(text = { Text(folder.name.ifBlank { "未命名文件夹" }) }, onClick = {
                    vm.selectImaFolder(folder.id)
                    expanded = false
                })
            }
        }
    }
}
