package com.unarchive.android

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.unarchive.android.auth.BilibiliAuthStore
import com.unarchive.android.auth.BilibiliLoginClient
import com.unarchive.android.auth.BilibiliSession
import com.unarchive.android.auth.QrCodeRenderer
import com.unarchive.android.auth.QrLoginState
import kotlinx.coroutines.delay

/**
 * Bilibili login controls: shows the current login state, and opens a login
 * dialog when logged out. The dialog prefers deep-linking into the Bilibili
 * App for authorization (single-device friendly), with the QR code as a
 * fallback for devices without the App.
 */
@Composable
fun BilibiliLoginSection(
    authStore: BilibiliAuthStore,
    loginClient: BilibiliLoginClient,
    loggedIn: Boolean,
    onLoggedIn: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    if (loggedIn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("B站已登录")
            TextButton(onClick = {
                authStore.clear()
                onLoggedOut()
            }) {
                Text("退出")
            }
        }
    } else {
        OutlinedButton(onClick = { showDialog = true }) {
            Text("登录 B 站（获取 AI 字幕）")
        }
    }

    if (showDialog) {
        QrLoginDialog(
            loginClient = loginClient,
            onSuccess = { session ->
                authStore.save(session)
                onLoggedIn()
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun QrLoginDialog(
    loginClient: BilibiliLoginClient,
    onSuccess: (BilibiliSession) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        status = "正在准备授权..."
        try {
            val qr = loginClient.generate()
            qrBitmap = QrCodeRenderer.render(qr.url)
            // Deep-link into the Bilibili App for authorization. If the App is
            // absent this falls back to the browser; the QR code remains as a
            // second fallback below.
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(qr.url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            status = "已唤起 B 站 App，请在其中确认登录"
            while (true) {
                delay(POLL_INTERVAL_MS)
                when (val state = loginClient.poll(qr.key)) {
                    is QrLoginState.NotScanned -> Unit
                    is QrLoginState.Scanned -> status = "已确认，正在完成登录..."
                    is QrLoginState.Expired -> {
                        status = "授权已失效，点击刷新重试"
                        break
                    }
                    is QrLoginState.Success -> {
                        onSuccess(state.session)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            status = "登录失败：${e.message ?: "网络错误"}"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { attempt++ }) { Text("刷新") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("登录 B 站") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(status)
                Spacer(Modifier.height(16.dp))
                qrBitmap?.let {
                    Text(
                        "没装 B 站 App？用另一台设备扫码：",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "登录二维码",
                        modifier = Modifier.size(160.dp),
                    )
                }
            }
        },
    )
}

private const val POLL_INTERVAL_MS = 2_000L
