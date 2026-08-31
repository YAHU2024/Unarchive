package com.unarchive.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.model.ModelManagementSection
import com.unarchive.android.storage.AppStorageSnapshot
import com.unarchive.android.storage.formatStorageBytes
import com.unarchive.android.ui.state.SettingsEvent
import com.unarchive.android.ui.state.SettingsUiState
import java.io.File
import kotlin.math.roundToInt

/** Presentation-only settings surface. Business mutations are typed events. */
@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    modelsDirectory: File,
    loginContent: @Composable () -> Unit,
    onEvent: (SettingsEvent) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .testTag("settings-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("设置", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
        loginContent()
        HorizontalDivider()

        Text("识别引擎", style = MaterialTheme.typography.titleMedium)
        state.availableEngines.forEach { engine ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = engine == state.selectedEngine,
                    onClick = { onEvent(SettingsEvent.SelectEngine(engine)) },
                    enabled = !state.isBusy,
                )
                Text(engine.displayName)
            }
        }
        SiliconFlowModelSettings(state, onEvent)

        HorizontalDivider()
        Text("推理线程数（性能对比用）", style = MaterialTheme.typography.titleMedium)
        listOf(null to "自动（按大核数）", 1 to "1", 2 to "2", 4 to "4").forEach { (threads, label) ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = state.selectedThreads == threads,
                    onClick = { onEvent(SettingsEvent.SetThreads(threads)) },
                    enabled = !state.isBusy,
                )
                Text(label)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("启用 VAD 静音检测（实验性，可能崩溃）")
            Spacer(Modifier.weight(1f))
            Switch(
                checked = state.selectedEnableVad,
                onCheckedChange = { onEvent(SettingsEvent.SetEnableVad(it)) },
                enabled = !state.isBusy,
            )
        }
        Text("VAD 最大段长：${state.selectedVadMaxSeconds} 秒（越小越精细，停顿更平滑）")
        Slider(
            value = state.selectedVadMaxSeconds.toFloat(),
            onValueChange = { onEvent(SettingsEvent.SetVadMaxSeconds(it.roundToInt())) },
            enabled = !state.isBusy,
            valueRange = 1f..30f,
            steps = 28,
        )

        HorizontalDivider()
        Text("服务凭据", style = MaterialTheme.typography.titleMedium)
        Text(
            "凭据编辑、连接检查和清除确认已集中到“安全设置”。此页不显示或修改密钥。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("思考模式（更慢但更深入）")
            Spacer(Modifier.weight(1f))
            Switch(
                checked = state.thinkingEnabled,
                onCheckedChange = { onEvent(SettingsEvent.SetThinkingEnabled(it)) },
                enabled = !state.isBusy,
            )
        }

        HorizontalDivider()
        StorageManagementSection(state, onEvent)
        HorizontalDivider()
        ModelManagementSection(modelsDirectory = modelsDirectory, enabled = !state.isBusy)
    }
}

@Composable
private fun SiliconFlowModelSettings(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    var modelInput by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("siliconflow-model-section"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("SiliconFlow 模型", style = MaterialTheme.typography.titleSmall)
        Text(
            "仅在选择 SiliconFlow 云端引擎时使用。可添加服务商支持的模型名称并切换。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.siliconFlowModels.forEachIndexed { index, model ->
            Row(modifier = Modifier.fillMaxWidth().testTag("siliconflow-model-option-$index"), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    modifier = Modifier.testTag("siliconflow-model-radio-$index"),
                    selected = model == state.selectedSiliconFlowModel,
                    onClick = { onEvent(SettingsEvent.SelectSiliconFlowModel(model)) },
                    enabled = !state.isBusy,
                )
                Text(model)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = modelInput,
                onValueChange = { modelInput = it },
                modifier = Modifier.weight(1f).testTag("siliconflow-model-input"),
                enabled = !state.isBusy,
                singleLine = true,
                label = { Text("添加模型名称") },
            )
            Button(
                modifier = Modifier.testTag("siliconflow-model-add"),
                onClick = {
                    onEvent(SettingsEvent.AddSiliconFlowModel(modelInput))
                    modelInput = ""
                },
                enabled = !state.isBusy && modelInput.isNotBlank(),
            ) { Text("添加") }
        }
        if (state.siliconFlowModelStatus.isNotBlank()) Text(state.siliconFlowModelStatus, modifier = Modifier.testTag("siliconflow-model-status"), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StorageManagementSection(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    Text("存储", style = MaterialTheme.typography.titleMedium)
    when {
        state.storageLoading && state.storageSnapshot == null -> Text("正在读取存储信息……", style = MaterialTheme.typography.bodySmall)
        state.storageSnapshot != null -> StorageSnapshotText(state.storageSnapshot!!)
        state.storageError.isNotBlank() -> Text(state.storageError, color = MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { onEvent(SettingsEvent.RefreshStorage) }, enabled = !state.storageLoading) { Text(if (state.storageLoading) "正在刷新" else "刷新存储信息") }
        OutlinedButton(onClick = { onEvent(SettingsEvent.OpenSystemStorageSettings) }) { Text("系统存储设置") }
    }
    OutlinedButton(onClick = { onEvent(SettingsEvent.ClearRebuildableCache) }, enabled = !state.cacheClearing && !state.isBusy) { Text(if (state.cacheClearing) "正在清理" else "清理可重建缓存") }
    Text("仅清理导出临时文件、解码缓存、音频缓存和视频缓存；不会删除模型、知识卡片、转录结果或凭据。", style = MaterialTheme.typography.bodySmall)
    if (state.cacheClearStatus.isNotBlank()) Text(state.cacheClearStatus, style = MaterialTheme.typography.bodySmall)
    Text("缓存预算：${formatStorageBytes(state.effectiveCacheBudgetBytes)}", style = MaterialTheme.typography.titleSmall)
    Text("预算只管理可重建缓存，不会自动删除模型、知识卡片或转录结果。", style = MaterialTheme.typography.bodySmall)
    listOf(0L to "自动（容量的 2%，1～4 GiB）", 1L to "1 GiB", 2L to "2 GiB", 4L to "4 GiB", 8L to "8 GiB").forEach { (gibibytes, label) ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = if (gibibytes == 0L) state.configuredCacheBudgetBytes == 0L else state.configuredCacheBudgetBytes == gibibytes * 1024L * 1024L * 1024L,
                onClick = { if (gibibytes == 0L) onEvent(SettingsEvent.UseAutomaticCacheBudget) else onEvent(SettingsEvent.UsePresetCacheBudget(gibibytes)) },
                enabled = !state.isBusy,
            )
            Text(label)
        }
    }
    OutlinedTextField(
        value = state.customCacheBudgetInput,
        onValueChange = { onEvent(SettingsEvent.CustomCacheBudgetChanged(it)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("自定义缓存预算（GiB）") },
        supportingText = { Text("允许范围 0.5～16 GiB") },
        singleLine = true,
        enabled = !state.isBusy,
    )
    OutlinedButton(onClick = { onEvent(SettingsEvent.SaveCustomCacheBudget) }, enabled = !state.isBusy) { Text("保存自定义预算") }
    if (state.cacheBudgetStatus.isNotBlank()) Text(state.cacheBudgetStatus, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun StorageSnapshotText(snapshot: AppStorageSnapshot) {
    Text("设备可用 ${formatStorageBytes(snapshot.freeBytes)} · 当前可分配 ${formatStorageBytes(snapshot.allocatableBytes)}", style = MaterialTheme.typography.bodySmall)
    Text("应用：代码 ${formatStorageBytes(snapshot.appCodeBytes)} · 数据 ${formatStorageBytes(snapshot.appDataBytes)} · 缓存 ${formatStorageBytes(snapshot.appCacheBytes)}", style = MaterialTheme.typography.bodySmall)
    Text("持久数据：模型 ${formatStorageBytes(snapshot.modelsBytes)} · 知识卡片 ${formatStorageBytes(snapshot.knowledgeCardsBytes)} · 转录结果 ${formatStorageBytes(snapshot.resultsBytes)} · 日志 ${formatStorageBytes(snapshot.logsBytes)}", style = MaterialTheme.typography.bodySmall)
    Text("可重建缓存：音频 ${formatStorageBytes(snapshot.audioCacheBytes)} · 解码 ${formatStorageBytes(snapshot.decodedCacheBytes)} · 导出 ${formatStorageBytes(snapshot.exportCacheBytes)} · 视频 ${formatStorageBytes(snapshot.videoCacheBytes)}", style = MaterialTheme.typography.bodySmall)
}
