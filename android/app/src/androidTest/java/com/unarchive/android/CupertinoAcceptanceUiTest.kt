package com.unarchive.android

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.unarchive.android.ui.components.CupertinoButton
import com.unarchive.android.ui.components.GlassScaffold
import com.unarchive.android.ui.components.GlassSurface
import com.unarchive.android.ui.components.ToggleSettingsRow
import com.unarchive.android.ui.settings.SettingsScreen
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.GraphUiState
import com.unarchive.android.ui.state.MeUiState
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.SettingsEvent
import com.unarchive.android.ui.state.SettingsUiState
import com.unarchive.android.ui.state.UnarchiveUiState
import com.unarchive.android.ui.theme.LiquidGlassInputs
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Synthetic fixtures only: no user notes, credentials, model installs or service calls. */
@RunWith(Parameterized::class)
class CupertinoAcceptanceUiTest(private val dark: Boolean, private val scale: Float) {
    @get:Rule val rule = createComposeRule()

    @Composable private fun Theme(glass: Boolean = true, content: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
            UnarchiveTheme(darkTheme = dark, liquidGlassEnabled = glass, motionPolicy = MotionPolicy.Reduced,
                glassInputs = LiquidGlassInputs(true, android.os.Build.VERSION.SDK_INT), content = content)
        }
    }

    @Test fun mountedShellKeepsDockLargeTextAndThreeDestinations() {
        rule.setContent {
            Theme {
                UnarchiveNavigationHost(
                    state = UnarchiveUiState(create = CreateUiState("BV_fixture", false, "", false, 0),
                        notes = NotesUiState(0, 0, emptyList()), graph = GraphUiState(0), me = MeUiState()),
                    onCreateEvent = {}, onNotesEvent = {}, onMeEvent = {},
                    legacyTestContent = { _, _ -> }, legacyLogContent = {},
                    legacySettingsContent = { back ->
                        SettingsScreen(SettingsUiState(), File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "cupertino-no-models"), {}, {}, back)
                    },
                )
            }
        }
        rule.onNodeWithTag("bottom-nav-create").assertHeightIsAtLeast(if (scale == 2f) 88.dp else 48.dp)
        capture("shell-create")
        rule.onNodeWithTag("bottom-nav-notes").performClick().assertIsSelected()
        capture("shell-notes")
        rule.onNodeWithTag("bottom-nav-me").performClick().assertIsSelected()
        capture("shell-me")
        rule.onNodeWithTag("me-appearance").performScrollTo().performClick()
        capture("settings")
    }

    @Test fun settingsInputAndSwitchHitAreaKeepSingleEventAndButtonContrast() {
        val events = mutableListOf<SettingsEvent>()
        var checked by mutableStateOf(false)
        var changes = 0
        var expected = Color.Unspecified
        var actual = Color.Unspecified
        rule.setContent {
            Theme(false) {
                Column {
                    ToggleSettingsRow("开关", checked, true, Modifier.testTag("row")) {
                        checked = it; changes++
                    }
                    CupertinoButton(onClick = {}) {
                        expected = MaterialTheme.colorScheme.onPrimary
                        actual = LocalContentColor.current
                        Text("主操作")
                    }
                    SettingsScreen(SettingsUiState(), File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "cupertino-no-models"), {}, events::add, {})
                }
            }
        }
        rule.onNodeWithTag("row").performTouchInput { click(Offset(width - 60f, height / 2f)) }
        rule.runOnIdle { assertEquals(1, changes); assertTrue(checked); assertEquals(expected, actual) }
        rule.onNodeWithTag("siliconflow-model-input").performScrollTo().performTextReplacement("fixture/model")
        rule.onNodeWithTag("siliconflow-model-add").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(SettingsEvent.AddSiliconFlowModel("fixture/model")), events) }
    }

    @Test fun chromeSamplesBackgroundAndOpaqueFallbackStopsSampling() {
        var glass by mutableStateOf(true)
        rule.setContent {
            Theme(glass) {
                GlassScaffold(bottomBar = {
                    GlassSurface(Modifier.size(120.dp).testTag("sample")) {}
                }) { Box(Modifier.fillMaxSize().background(Color.Red)) }
            }
        }
        rule.waitForIdle()
        val image = rule.onNodeWithTag("sample").captureToImage().toPixelMap()
        val sampled = image[image.width / 2, image.height / 2]
        rule.runOnIdle { glass = false }
        val opaque = rule.onNodeWithTag("sample").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
        assertTrue("glass must sample the red source", sampled.red - sampled.green > opaque.red - opaque.green + 0.04f)
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "cupertino-fixtures").apply { mkdirs() }
        val roots = rule.onAllNodes(isRoot())
        val bitmap = roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asAndroidBitmap()
        File(directory, "$name-${if (dark) "dark" else "light"}-font-$scale.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}, font={1}")
        fun configurations(): List<Array<Any>> = listOf(arrayOf(false, 1f), arrayOf(true, 1f), arrayOf(false, 2f), arrayOf(true, 2f))
    }
}
