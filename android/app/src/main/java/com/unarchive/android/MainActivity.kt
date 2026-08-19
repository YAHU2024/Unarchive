package com.unarchive.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class AppTab(val label: String, val icon: ImageVector) {
    TEST("测试", Icons.Filled.PlayArrow),
    RESULTS("结果", Icons.AutoMirrored.Filled.List),
    KNOWLEDGE("笔记", Icons.AutoMirrored.Filled.List),
    LOG("日志", Icons.Filled.Info),
    SETTINGS("设置", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    private val sharedAudio = mutableStateOf<Uri?>(null)
    private val sharedVideoReference = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptIntent(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: UnarchiveViewModel = viewModel()
                    LaunchedEffect(sharedAudio.value, sharedVideoReference.value) {
                        vm.acceptSharedInput(sharedAudio.value, sharedVideoReference.value)
                    }
                    UnarchiveApp(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
    }

    private fun acceptIntent(intent: Intent) {
        sharedAudio.value = intent.audioUri()
        sharedVideoReference.value = intent.videoReferenceText()
    }
}

@Composable
private fun UnarchiveApp(vm: UnarchiveViewModel) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = AppTab.entries

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tabs[selectedTabIndex]) {
                AppTab.TEST -> TestTab(vm, onOpenLogs = { selectedTabIndex = AppTab.LOG.ordinal })
                AppTab.RESULTS -> ResultsTab(vm)
                AppTab.KNOWLEDGE -> KnowledgeTab(vm)
                AppTab.LOG -> LogTab()
                AppTab.SETTINGS -> SettingsTab(vm)
            }
        }
    }
}

internal fun Intent.audioUri(): Uri? = when (action) {
    Intent.ACTION_VIEW -> data
    Intent.ACTION_SEND -> if (type?.startsWith("audio/") == true) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM)
        }
    } else {
        null
    }
    else -> null
}

internal fun Intent.videoReferenceText(): String? = when (action) {
    Intent.ACTION_SEND -> if (type == "text/plain") getStringExtra(Intent.EXTRA_TEXT) else null
    else -> null
}
