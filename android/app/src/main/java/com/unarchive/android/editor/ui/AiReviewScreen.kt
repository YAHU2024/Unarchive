package com.unarchive.android.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.unarchive.android.editor.MarkdownAiProposalStatus
import com.unarchive.android.editor.ai.*
import com.unarchive.android.ui.components.UnarchiveTextButton

/** Presentation only: the parent keeps document mode, native selection and viewport alive. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun AiReviewScreen(
    state: AiProposalUiState,
    onBack: () -> Unit,
    onApply: () -> Unit,
    onReject: () -> Unit,
    onRegenerate: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var selected by remember(state.proposal?.proposalId) { mutableIntStateOf(0) }
    val review = state.review
    val tabs = if (review?.mode == AiReviewMode.DIFF) listOf("差异", "完整原文", "完整候选")
        else listOf("完整原文", "完整候选")
    Surface(Modifier.fillMaxSize().testTag("ai-review-screen")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                UnarchiveTextButton(onClick = onBack, modifier = Modifier.testTag("ai-review-back")) { Text("返回") }
                Text("AI 候选审阅", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            }
            if (state.proposal?.status == MarkdownAiProposalStatus.EXPIRED) Text("候选已过期，仅可查看",
                Modifier.padding(horizontal = 16.dp).testTag("ai-review-expired"), color = MaterialTheme.colorScheme.error)
            review?.explanation?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(it, Modifier.padding(16.dp).testTag("ai-review-error"), color = MaterialTheme.colorScheme.error) }
            state.safetyErrors.forEach { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            if (review == null) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (state.proposal == null && state.activity == AiProposalActivity.IDLE) {
                        Text("候选已清理", Modifier.align(Alignment.Center).testTag("ai-review-empty"))
                    } else CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("ai-review-loading"))
                }
            } else {
                Row(Modifier.fillMaxWidth()) {
                    tabs.forEachIndexed { index, title ->
                        UnarchiveTextButton(onClick = { selected = index }, modifier = Modifier.weight(1f)
                            .testTag("ai-review-tab-$index")) {
                            Text(title, color = if (selected == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                val tab = selected.coerceAtMost(tabs.lastIndex)
                val diff = review.mode == AiReviewMode.DIFF && tab == 0
                val original = if (review.mode == AiReviewMode.DIFF) tab == 1 else tab == 0
                key(state.proposal?.proposalId, tab) {
                    SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(Modifier.fillMaxSize().testTag("ai-review-content"), contentPadding = PaddingValues(16.dp)) {
                            if (diff) itemsIndexed(review.lines) { index, line ->
                                val marker = when (line.kind) { AiReviewLineKind.ADDED -> "+"; AiReviewLineKind.REMOVED -> "−"; else -> " " }
                                val number = line.candidateLine ?: line.originalLine
                                Text("$marker $number  ${line.text}", Modifier.fillMaxWidth().testTag("ai-review-line-$index"),
                                    fontFamily = FontFamily.Monospace, color = when (line.kind) {
                                        AiReviewLineKind.ADDED -> MaterialTheme.colorScheme.primary
                                        AiReviewLineKind.REMOVED -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurface
                                    })
                            } else {
                                val chunks = if (original) review.originalChunks else review.candidateChunks
                                if (original && review.original == null) item { Text("原正式基版不可用") }
                                itemsIndexed(chunks) { index, chunk ->
                                    val number = if (chunk.continuation) "↳" else chunk.line.toString()
                                    Text("$number  ${chunk.text}", Modifier.fillMaxWidth().testTag("ai-review-text-$index"),
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                            item { Text("已到${if (diff) "差异" else if (original) "原文" else "候选"}末尾",
                                Modifier.padding(top = 16.dp).testTag("ai-review-end"), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                UnarchiveTextButton(onClick = onReject, enabled = state.proposal != null && state.activity == AiProposalActivity.IDLE,
                    modifier = Modifier.testTag("ai-review-reject")) { Text("拒绝候选") }
                if (state.proposal?.status == MarkdownAiProposalStatus.EXPIRED) {
                    UnarchiveTextButton(onClick = onRegenerate, enabled = state.activity == AiProposalActivity.IDLE,
                        modifier = Modifier.testTag("ai-review-regenerate")) { Text("重新生成") }
                } else UnarchiveTextButton(onClick = onApply, enabled = state.canApply && review != null,
                    modifier = Modifier.testTag("ai-review-apply")) { Text("应用到草稿") }
            }
        }
    }
}
