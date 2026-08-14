package com.unarchive.android.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * In-app model management: shows installation state for SenseVoice and Silero
 * VAD, and lets the user download, cancel, or delete each model. Also carries the
 * attribution required by the SenseVoice (FunASR Model License v1.1) and Silero
 * (MIT) terms.
 */
@Composable
fun ModelManagementSection(
    modelsDirectory: File,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repository = remember { ModelRepository(modelsDirectory) }
    val downloader = remember { ModelDownloader(modelsDirectory, assets = context.assets) }

    var statuses by remember {
        mutableStateOf(ModelSource.ALL.associate { it.id to repository.status(it) })
    }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var progressBytes by remember { mutableLongStateOf(0L) }
    var progressTotal by remember { mutableStateOf<Long?>(null) }
    var phase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadToken by remember { mutableIntStateOf(0) }

    fun refresh() {
        statuses = ModelSource.ALL.associate { it.id to repository.status(it) }
    }

    fun startDownload(source: ModelSource) {
        downloadToken++
        val token = downloadToken
        downloadingId = source.id
        progressBytes = 0L
        progressTotal = null
        phase = "准备下载"
        error = null
        downloadJob = scope.launch {
            try {
                downloader.ensureInstalled(source) { progress ->
                    if (token == downloadToken) {
                        when (progress) {
                            is ModelDownloader.Progress.Downloading -> {
                                phase = "下载中"
                                progressBytes = progress.bytes
                                progressTotal = progress.total
                            }
                            is ModelDownloader.Progress.Extracting -> {
                                phase = "解压中"
                                progressBytes = progress.bytes
                                progressTotal = progress.total
                            }
                            is ModelDownloader.Progress.Copying -> {
                                phase = "安装中"
                                progressBytes = progress.bytes
                                progressTotal = progress.total
                            }
                            is ModelDownloader.Progress.Installing -> phase = "安装中"
                        }
                    }
                }
                if (token == downloadToken) {
                    phase = "完成"
                    refresh()
                }
            } catch (_: CancellationException) {
                if (token == downloadToken) phase = "已取消"
            } catch (e: Exception) {
                if (token == downloadToken) {
                    phase = "失败"
                    error = e.message
                }
            } finally {
                if (token == downloadToken) {
                    downloadingId = null
                    downloadJob = null
                }
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Models", style = MaterialTheme.typography.titleMedium)

        for (source in ModelSource.ALL) {
            val status = statuses[source.id] ?: ModelStatus.Missing
            val isDownloading = downloadingId == source.id
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.displayName)
                    Text(
                        when (status) {
                            ModelStatus.Installed -> "已安装"
                            ModelStatus.Missing -> "未安装"
                            ModelStatus.Corrupt -> "不完整，请重新下载"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                when {
                    isDownloading -> OutlinedButton(onClick = { downloadJob?.cancel() }) {
                        Text("取消")
                    }
                    status == ModelStatus.Installed -> OutlinedButton(
                        enabled = enabled,
                        onClick = {
                            repository.delete(source)
                            refresh()
                        },
                    ) {
                        Text("删除")
                    }
                    else -> Button(
                        enabled = enabled,
                        onClick = { startDownload(source) },
                    ) {
                        Text("安装")
                    }
                }
            }

            if (isDownloading) {
                val total = progressTotal
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (progressBytes.toFloat() / total).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("$phase ${formatBytes(progressBytes)} / ${formatBytes(total)}")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(phase)
                }
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text(
            "模型版权：SenseVoice（FunASR / 阿里巴巴，FunASR Model License v1.1）；" +
                "Silero VAD（Silero Team，MIT）；sherpa-onnx（Apache-2.0）。",
            style = MaterialTheme.typography.bodySmall,
        )

        var showLicenses by remember { mutableStateOf(false) }
        OutlinedButton(
            enabled = enabled,
            onClick = { showLicenses = true },
        ) {
            Text("查看开源许可")
        }
        if (showLicenses) {
            LicensesDialog(
                onDismiss = { showLicenses = false },
                licenseText = remember { loadLicenses(context) },
            )
        }
    }
}

/** Reads every text file under `assets/licenses/` and joins them for display. */
private fun loadLicenses(context: android.content.Context): String {
    val directory = "licenses"
    val names = context.assets.list(directory).orEmpty().sorted()
    val builder = StringBuilder()
    for (name in names) {
        val text = context.assets.open("$directory/$name").bufferedReader().use { it.readText() }
        builder.append("===== $name =====\n\n").append(text).append("\n\n")
    }
    return builder.toString()
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit, licenseText: String) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开源许可") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "本应用包含第三方开源软件与模型。完整许可文本如下：",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(licenseText, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun formatBytes(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
