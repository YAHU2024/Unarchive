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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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

        SiliconFlowModelSettings(
            models = vm.siliconFlowModels,
            selectedModel = vm.selectedSiliconFlowModel,
            status = vm.siliconFlowModelStatus,
            enabled = vm.runningJob == null && vm.generateJob == null,
            onSelect = vm::selectSiliconFlowModel,
            onAdd = vm::addSiliconFlowModel,
        )

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

        Text("服务凭据", style = MaterialTheme.typography.titleMedium)
        Text(
            "凭据编辑、连接检查和清除确认已集中到“安全设置”。此开发者页不再显示或修改密钥。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
internal fun SiliconFlowModelSettings(
    models: List<String>,
    selectedModel: String,
    status: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onAdd: (String) -> Boolean,
) {
    var modelInput by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("siliconflow-model-section"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("SiliconFlow 模型", style = MaterialTheme.typography.titleSmall)
        Text(
            "仅在选择 SiliconFlow 云端引擎时使用。可添加服务商支持的模型名称并切换；修改后会作为新的转写配置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        models.forEachIndexed { index, model ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("siliconflow-model-option-$index"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    modifier = Modifier.testTag("siliconflow-model-radio-$index"),
                    selected = model == selectedModel,
                    onClick = { onSelect(model) },
                    enabled = enabled,
                )
                Text(model)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = modelInput,
                onValueChange = { modelInput = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("siliconflow-model-input"),
                enabled = enabled,
                singleLine = true,
                label = { Text("添加模型名称") },
            )
            Button(
                modifier = Modifier.testTag("siliconflow-model-add"),
                onClick = {
                    if (onAdd(modelInput)) modelInput = ""
                },
                enabled = enabled && modelInput.isNotBlank(),
            ) {
                Text("添加")
            }
        }
        if (status.isNotBlank()) {
            Text(
                status,
                modifier = Modifier.testTag("siliconflow-model-status"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
