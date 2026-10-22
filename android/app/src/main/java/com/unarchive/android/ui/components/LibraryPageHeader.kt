package com.unarchive.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity

/** Large, wrapping entry title shared by the three LG-B structure samples. */
@Composable
internal fun LibraryPageHeader(title: String, description: String? = null, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val titleScale = density.fontScale.coerceAtMost(1.4f)
    val reportTitleHeight = LocalRootTitleHeight.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = with(density) { (34f * titleScale).dp.toSp() }, lineHeight = with(density) { (41f * titleScale).dp.toSp() }),
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .testTag("root-large-title")
                .onSizeChanged { reportTitleHeight(it.height) }
                .semantics { heading() },
        )
        if (!description.isNullOrBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
