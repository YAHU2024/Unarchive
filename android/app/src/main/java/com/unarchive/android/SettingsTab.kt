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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.model.ModelManagementSection
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

        ModelManagementSection(
            modelsDirectory = File(context.filesDir, "models"),
            enabled = vm.runningJob == null,
        )
    }
}

@Composable
private fun ImaKnowledgeBaseSelector(vm: UnarchiveViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = vm.imaKnowledgeBases.firstOrNull { it.id == vm.imaKnowledgeBaseId }?.name
        ?.takeIf(String::isNotBlank) ?: "请选择测试知识库"
    Box {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().clickable(enabled = vm.imaKnowledgeBases.isNotEmpty()) { expanded = true },
            enabled = vm.runningJob == null && vm.generateJob == null && vm.imaKnowledgeBases.isNotEmpty(),
            label = { Text("测试知识库") },
        )
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
    Box {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { expanded = true },
            enabled = enabled,
            label = { Text("目标文件夹") },
        )
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
