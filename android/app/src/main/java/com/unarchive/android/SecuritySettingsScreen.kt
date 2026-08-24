package com.unarchive.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
internal fun SecuritySettingsScreen(
    vm: UnarchiveViewModel,
    onBack: () -> Unit,
) {
    val state by vm.securitySettingsController.state.collectAsState()

    LaunchedEffect(Unit) {
        vm.refreshSecurityPresence()
    }

    SecuritySettingsContent(
        state = state,
        onBack = onBack,
        onInputPresence = vm.securitySettingsController::setInputPresence,
        onSave = vm::saveSecureCredential,
        onRequestClear = vm::requestClearSecureCredentials,
        onCancelClear = vm::cancelClearSecureCredentials,
        onConfirmClear = vm::confirmClearSecureCredentials,
        onCheckConnection = vm::checkSecureConnection,
    )
}
