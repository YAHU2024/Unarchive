package com.unarchive.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.unarchive.android.security.ClearResult
import com.unarchive.android.security.ConnectionStatus
import com.unarchive.android.security.CredentialMutationResult
import com.unarchive.android.security.SecurityNotice
import com.unarchive.android.security.SecurityProfile
import com.unarchive.android.security.SecureCredential
import com.unarchive.android.security.SecuritySettingsState

/**
 * Presentational security settings surface. State contains presence-only facts;
 * secret values stay in transient Compose memory inside this composable.
 */
@Composable
internal fun SecuritySettingsContent(
    state: SecuritySettingsState,
    onBack: () -> Unit,
    onInputPresence: (SecureCredential, Boolean) -> Unit,
    onSave: (SecureCredential, String) -> CredentialMutationResult,
    onRequestClear: (SecurityProfile) -> Unit,
    onCancelClear: () -> Unit,
    onConfirmClear: () -> ClearResult,
    onCheckConnection: (SecurityProfile) -> Unit,
) {
    var deepSeekInput by remember { mutableStateOf("") }
    var siliconFlowInput by remember { mutableStateOf("") }
    var imaClientIdInput by remember { mutableStateOf("") }
    var imaApiKeyInput by remember { mutableStateOf("") }
    var localNotice by remember { mutableStateOf<String?>(null) }

    if (state.pendingClear != null) {
        val profile = state.pendingClear!!
        AlertDialog(
            onDismissRequest = {
                localNotice = null
                onCancelClear()
            },
            title = { Text("确认清除${profile.displayLabel()}凭据？") },
            text = { Text("这会移除本机保存的该服务凭据；本地笔记和转写结果不会删除。") },
            confirmButton = {
                Button(
                    onClick = {
                        localNotice = null
                        if (onConfirmClear() is ClearResult.Cleared) {
                            when (profile) {
                                SecurityProfile.DEEPSEEK -> deepSeekInput = ""
                                SecurityProfile.SILICONFLOW -> siliconFlowInput = ""
                                SecurityProfile.IMA -> {
                                    imaClientIdInput = ""
                                    imaApiKeyInput = ""
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("security-clear-confirm"),
                ) { Text("确认清除") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        localNotice = null
                        onCancelClear()
                    },
                    modifier = Modifier.testTag("security-clear-cancel"),
                ) { Text("取消") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .testTag("security-scroll")
            .semantics { contentDescription = "安全设置列表，可上下滚动" },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "安全设置",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .testTag("security-title")
                    .semantics { heading() },
            )
            TextButton(onClick = onBack) { Text("返回") }
        }
        Text(
            "凭据使用 Android Keystore 加密保存。页面只显示是否已配置，不回显密钥或内部服务编号。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("security-privacy-note"),
        )

        SecureCredentialField(
            label = "DeepSeek API Key",
            credential = SecureCredential.DEEPSEEK_API_KEY,
            configured = state.presence(SecureCredential.DEEPSEEK_API_KEY).configured,
            value = deepSeekInput,
            onValueChange = {
                localNotice = null
                deepSeekInput = it
                onInputPresence(SecureCredential.DEEPSEEK_API_KEY, it.isNotBlank())
            },
            onSave = {
                localNotice = null
                val result = onSave(SecureCredential.DEEPSEEK_API_KEY, deepSeekInput)
                localNotice = saveMessage(result)
                if (result is CredentialMutationResult.Saved) deepSeekInput = ""
            },
            onClear = { onRequestClear(SecurityProfile.DEEPSEEK) },
        )
        ConnectionRow(
            profile = SecurityProfile.DEEPSEEK,
            status = state.connection[SecurityProfile.DEEPSEEK],
            canCheck = !state.presence(SecureCredential.DEEPSEEK_API_KEY).hasPendingInput,
            onCheck = { profile ->
                localNotice = null
                onCheckConnection(profile)
            },
        )

        HorizontalDivider()

        SecureCredentialField(
            label = "SiliconFlow API Key",
            credential = SecureCredential.SILICONFLOW_API_KEY,
            configured = state.presence(SecureCredential.SILICONFLOW_API_KEY).configured,
            value = siliconFlowInput,
            onValueChange = {
                localNotice = null
                siliconFlowInput = it
                onInputPresence(SecureCredential.SILICONFLOW_API_KEY, it.isNotBlank())
            },
            onSave = {
                localNotice = null
                val result = onSave(SecureCredential.SILICONFLOW_API_KEY, siliconFlowInput)
                localNotice = saveMessage(result)
                if (result is CredentialMutationResult.Saved) siliconFlowInput = ""
            },
            onClear = { onRequestClear(SecurityProfile.SILICONFLOW) },
        )
        ConnectionRow(
            profile = SecurityProfile.SILICONFLOW,
            status = state.connection[SecurityProfile.SILICONFLOW],
            canCheck = !state.presence(SecureCredential.SILICONFLOW_API_KEY).hasPendingInput,
            onCheck = { profile ->
                localNotice = null
                onCheckConnection(profile)
            },
        )

        HorizontalDivider()
        Text("ima", style = MaterialTheme.typography.titleMedium)
        SecureCredentialField(
            label = "Client ID",
            credential = SecureCredential.IMA_CLIENT_ID,
            configured = state.presence(SecureCredential.IMA_CLIENT_ID).configured,
            value = imaClientIdInput,
            onValueChange = {
                localNotice = null
                imaClientIdInput = it
                onInputPresence(SecureCredential.IMA_CLIENT_ID, it.isNotBlank())
            },
            onSave = {
                localNotice = null
                val result = onSave(SecureCredential.IMA_CLIENT_ID, imaClientIdInput)
                localNotice = saveMessage(result)
                if (result is CredentialMutationResult.Saved) imaClientIdInput = ""
            },
            onClear = { onRequestClear(SecurityProfile.IMA) },
        )
        SecureCredentialField(
            label = "API Key",
            credential = SecureCredential.IMA_API_KEY,
            configured = state.presence(SecureCredential.IMA_API_KEY).configured,
            value = imaApiKeyInput,
            onValueChange = {
                localNotice = null
                imaApiKeyInput = it
                onInputPresence(SecureCredential.IMA_API_KEY, it.isNotBlank())
            },
            onSave = {
                localNotice = null
                val result = onSave(SecureCredential.IMA_API_KEY, imaApiKeyInput)
                localNotice = saveMessage(result)
                if (result is CredentialMutationResult.Saved) imaApiKeyInput = ""
            },
            onClear = { onRequestClear(SecurityProfile.IMA) },
        )
        ConnectionRow(
            profile = SecurityProfile.IMA,
            status = state.connection[SecurityProfile.IMA],
            canCheck = !state.presence(SecureCredential.IMA_CLIENT_ID).hasPendingInput &&
                !state.presence(SecureCredential.IMA_API_KEY).hasPendingInput,
            onCheck = { profile ->
                localNotice = null
                onCheckConnection(profile)
            },
        )

        (localNotice ?: state.notice?.safeMessage())?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag("security-notice")
                    .semantics {
                        contentDescription = "安全设置通知：$it"
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
    }
}

@Composable
private fun SecureCredentialField(
    label: String,
    credential: SecureCredential,
    configured: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val presenceLabel = if (configured) "已配置" else "未配置"
    Column(
        modifier = Modifier.semantics {
            contentDescription = "$label，$presenceLabel，新值输入已掩码"
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(presenceLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("security-input-${credential.name.lowercase()}"),
            label = { Text("输入新值") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = value.isNotBlank()) { Text("保存") }
            OutlinedButton(onClick = onClear) { Text("清除") }
        }
    }
}

@Composable
private fun ConnectionRow(
    profile: SecurityProfile,
    status: ConnectionStatus?,
    canCheck: Boolean,
    onCheck: (SecurityProfile) -> Unit,
) {
    val statusText = status?.safeMessage() ?: "未检查"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("security-connection-${profile.name.lowercase()}")
            .semantics { contentDescription = "${profile.displayLabel()}连接状态：$statusText" },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("连接状态：$statusText", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onCheck(profile) }, enabled = canCheck && status != ConnectionStatus.CHECKING) {
            Text(if (status == ConnectionStatus.CHECKING) "检查中" else "检查连接")
        }
    }
}

private fun saveMessage(result: CredentialMutationResult): String = when (result) {
    is CredentialMutationResult.Saved -> "已保存。"
    is CredentialMutationResult.Rejected -> "保存失败，请检查输入格式。"
    is CredentialMutationResult.Failed -> "保存失败，请稍后重试。"
}

private fun SecurityProfile.displayLabel(): String = when (this) {
    SecurityProfile.DEEPSEEK -> "DeepSeek"
    SecurityProfile.SILICONFLOW -> "SiliconFlow"
    SecurityProfile.IMA -> "ima"
}

private fun SecurityNotice.safeMessage(): String = when (this) {
    SecurityNotice.CREDENTIAL_SAVED -> "已保存。"
    SecurityNotice.CLEAR_CONFIRMATION_REQUIRED -> "请确认是否清除凭据。"
    SecurityNotice.CREDENTIALS_CLEARED -> "凭据已清除。"
    SecurityNotice.SAVE_FAILED -> "保存失败，请检查输入格式。"
    SecurityNotice.CLEAR_FAILED -> "清除失败，请重试。"
    SecurityNotice.CONNECTION_SUCCEEDED -> "连接成功。"
    SecurityNotice.CREDENTIAL_REQUIRED -> "请先配置凭据。"
    SecurityNotice.CONNECTION_CHECKER_UNAVAILABLE -> "当前服务暂不支持连接检查。"
    SecurityNotice.CONNECTION_FAILED -> "连接失败，请检查网络或配置。"
}

private fun ConnectionStatus.safeMessage(): String = when (this) {
    ConnectionStatus.IDLE -> "未检查"
    ConnectionStatus.CHECKING -> "检查中"
    ConnectionStatus.CONNECTED -> "已连接"
    ConnectionStatus.MISSING_CREDENTIAL -> "缺少凭据"
    ConnectionStatus.CHECKER_UNAVAILABLE -> "暂不支持检查"
    ConnectionStatus.UNAUTHORIZED -> "凭据无效"
    ConnectionStatus.RATE_LIMITED -> "请求受限"
    ConnectionStatus.NETWORK_ERROR -> "网络不可用"
    ConnectionStatus.SERVER_ERROR -> "服务暂不可用"
    ConnectionStatus.INVALID_RESPONSE -> "服务响应异常"
    ConnectionStatus.UNKNOWN_ERROR -> "检查失败"
}
