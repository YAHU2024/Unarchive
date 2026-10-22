package com.unarchive.android

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
internal fun SecuritySettingsScreen(
    vm: UnarchiveViewModel,
    onBack: () -> Unit,
    profile: com.unarchive.android.security.SecurityProfile? = null,
) {
    val state by vm.securitySettingsController.state.collectAsState()

    LaunchedEffect(Unit) {
        vm.refreshSecurityPresence()
    }

    SecuritySettingsContent(
        state = state,
        profile = profile,
        onBack = onBack,
        onInputPresence = vm.securitySettingsController::setInputPresence,
        onSave = vm::saveSecureCredential,
        onRequestClear = vm::requestClearSecureCredentials,
        onCancelClear = vm::cancelClearSecureCredentials,
        onConfirmClear = vm::confirmClearSecureCredentials,
        onCheckConnection = vm::checkSecureConnection,
    )
}

@Composable
internal fun SecuritySettingsOverviewScreen(vm: UnarchiveViewModel, onBack: () -> Unit, onOpen: (com.unarchive.android.security.SecurityProfile) -> Unit) {
    val state by vm.securitySettingsController.state.collectAsState()
    LaunchedEffect(Unit) { vm.refreshSecurityPresence() }
    com.unarchive.android.ui.components.SecondaryPage("安全设置", onBack, "security-back", "security-title") {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            com.unarchive.android.ui.components.InsetGroupedList(title = "服务凭据") {
                com.unarchive.android.security.SecurityProfile.entries.forEachIndexed { index, profile ->
                    if (index > 0) androidx.compose.material3.HorizontalDivider(androidx.compose.ui.Modifier.padding(start = 16.dp))
                    val credentials = when (profile) {
                        com.unarchive.android.security.SecurityProfile.DEEPSEEK -> listOf(com.unarchive.android.security.SecureCredential.DEEPSEEK_API_KEY)
                        com.unarchive.android.security.SecurityProfile.SILICONFLOW -> listOf(com.unarchive.android.security.SecureCredential.SILICONFLOW_API_KEY)
                        com.unarchive.android.security.SecurityProfile.IMA -> listOf(com.unarchive.android.security.SecureCredential.IMA_CLIENT_ID, com.unarchive.android.security.SecureCredential.IMA_API_KEY)
                    }
                    com.unarchive.android.ui.components.NavigationSettingsRow(
                        title = profile.displayLabel(),
                        onClick = { onOpen(profile) },
                        modifier = androidx.compose.ui.Modifier.testTag("security-open-${profile.name.lowercase()}"),
                        trailingValue = if (credentials.all { state.presence(it).configured }) "已配置" else "未配置",
                    )
                }
            }
            androidx.compose.material3.Text("凭据加密保存在本机，密钥不回显。", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
    }
}
