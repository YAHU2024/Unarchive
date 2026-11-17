package com.unarchive.lgspike

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Samples() } }
    }
}

@Composable private fun Samples() {
    val renderer = rememberBackdropRenderer()
    var enabled by rememberSaveable { mutableStateOf(true) }
    var reduced by rememberSaveable { mutableStateOf(false) }
    var highContrast by rememberSaveable { mutableStateOf(false) }
    var constrained by rememberSaveable { mutableStateOf(false) }
    var lens by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf("创作") }
    var message by rememberSaveable { mutableStateOf("已保留 3 段转写，可明确继续或取消。") }
    val mode = sampleMode(SampleInputs(Build.VERSION.SDK_INT, enabled, reduced, highContrast, constrained, renderer.candidateAvailable, lens))
    Box(Modifier.fillMaxSize()) {
        renderer.Background(Modifier.matchParentSize()) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer)) {
                repeat(8) { index ->
                    Box(Modifier.fillMaxWidth().height(96.dp).background(
                        if (index % 2 == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                    ))
                }
            }
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OpaqueRenderer.Surface(SurfaceSpec(Modifier.fillMaxWidth()), SampleMode.Opaque) {
                Column {
                    Text("LG-A 独立兼容样本", style = MaterialTheme.typography.headlineMedium)
                    Text("API ${Build.VERSION.SDK_INT} · $mode · candidate=${renderer.candidateAvailable}")
                }
            }
            OpaqueRenderer.Surface(SurfaceSpec(), SampleMode.Opaque) {
                Column {
                    Toggle("允许玻璃", enabled) { enabled = it }
                    Toggle("减少透明度", reduced) { reduced = it }
                    Toggle("高对比度", highContrast) { highContrast = it }
                    Toggle("低性能策略", constrained) { constrained = it }
                    Toggle("Lens 实验（API 33+）", lens) { lens = it }
                }
            }
            renderer.Surface(SurfaceSpec(Modifier.fillMaxWidth(), emphasized = true), mode) { Text("顶栏 · 创作", style = MaterialTheme.typography.titleLarge) }
            renderer.Surface(SurfaceSpec(Modifier.fillMaxWidth()), mode) {
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("创作", "笔记", "去向", "我的").forEach { label ->
                        Text(label, Modifier.weight(1f).selectable(label == selected, role = Role.Tab, onClick = { selected = label }).heightIn(min = 48.dp).padding(vertical = 12.dp))
                    }
                }
            }
            renderer.Surface(SurfaceSpec(Modifier.fillMaxWidth()), mode) {
                Column {
                    Text("短任务卡 · 已中断", style = MaterialTheme.typography.titleMedium)
                    Text(message)
                    TextButton({ message = "样本任务已取消，无网络或数据写入。" }) { Text("取消") }
                }
            }
            renderer.Surface(SurfaceSpec(Modifier.fillMaxWidth(), emphasized = true, backgroundScope = "fixture"), mode) {
                Column {
                    Text("恢复 / 目标选择浮层样本", style = MaterialTheme.typography.titleMedium)
                    Text("只使用固定数据，不读取凭据或用户笔记。")
                    Button({ message = "已明确继续样本任务。" }) { Text("继续生成") }
                    TextButton({ message = "已关闭样本确认。" }) { Text("暂不继续") }
                }
            }
        }
    }
}

@Composable private fun Toggle(label: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = changed).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}
