package com.unarchive.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity

/** Large root title; reserves space for the single action row owned by RootPage. */
@Composable
internal fun LibraryPageHeader(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    titleScale: () -> Float = { 1f },
) {
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceAtMost(1.4f)
    val actionWidth = LocalRootTitleActionWidth.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                title,
                style = MaterialTheme.typography.displaySmall.copy(fontSize = with(density) { (34f * fontScale).dp.toSp() }, lineHeight = with(density) { (41f * fontScale).dp.toSp() }),
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .graphicsLayer {
                        val scale = titleScale()
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .testTag("root-large-title")
                    .semantics { heading() },
            )
            if (actionWidth > 0.dp) Spacer(Modifier.width(actionWidth + 12.dp))
        }
        if (!description.isNullOrBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
