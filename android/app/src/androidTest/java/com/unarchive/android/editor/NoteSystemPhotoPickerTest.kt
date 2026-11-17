package com.unarchive.android.editor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.assets.ImageImportState
import com.unarchive.android.editor.assets.NoteAssetStore
import com.unarchive.android.editor.native.NativeDocumentEditText
import com.unarchive.android.editor.ui.MarkdownNoteRoute
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.Test

/** Uses the actual system Picker. It never reads another MediaStore row or selects a private album. */
class NoteSystemPhotoPickerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val runId = (InstrumentationRegistry.getArguments().getString("notePickerRun")
        ?: InstrumentationRegistry.getArguments().getString("noteEditorReportRun"))
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) } ?: "direct-${System.currentTimeMillis()}-${System.nanoTime()}"
    private val root = File(context.cacheDir, "note-system-picker-$runId")
    private val mediaToken = "$runId-${UUID.randomUUID().toString().take(8)}"
    private val album = "Unarchive-editor-$mediaToken"
    private val fileName = "synthetic-$mediaToken.png"
    private var insertedUri: Uri? = null
    private var pickerOpen = false
    private var originalAutomationFlags: Int? = null
    private lateinit var activeFixture: Fixture

    @After fun cleanOnlyReturnedMediaUriAndSyntheticRepository() {
        if (pickerOpen) runCatching {
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            SystemClock.sleep(150)
        }
        originalAutomationFlags?.let { flags ->
            val service = instrumentation.uiAutomation.serviceInfo
            service.flags = flags
            instrumentation.uiAutomation.serviceInfo = service
        }
        rule.runOnUiThread { rule.activity.viewModelStore.clear() }
        insertedUri?.let { uri ->
            assertEquals("Only the test's returned media URI may be deleted", 1, context.contentResolver.delete(uri, null, null))
        }
        val canonicalRoot = root.canonicalFile
        check(canonicalRoot.parentFile == context.cacheDir.canonicalFile && canonicalRoot.name == "note-system-picker-$runId")
        if (canonicalRoot.exists()) {
            canonicalRoot.walkTopDown().forEach { file ->
                check(file.canonicalPath == canonicalRoot.path || file.canonicalPath.startsWith(canonicalRoot.path + File.separator))
            }
            canonicalRoot.deleteRecursively()
        }
    }

    @Test fun realSystemPickerImportsOnlyTheUniqueSyntheticAlbumThenFormalPortableExport() = report("import") { evidence ->
        requirePicker(evidence)
        val media = createOwnedImage()
        evidence.put("ownedMediaSha256", media.sha256).put("ownedMediaRows", 1)
        val fixture = fixture()
        mount(fixture)
        awaitReading(); editAtEnd()
        val native = native()
        val editable = onMain { native.text }
        val original = onMain { native.bridge!!.session.source }
        chooseRealPhoto(evidence)
        evidence.put("systemPickerOpened", true)
        val selection = navigateToOwnedTarget(evidence)
        evidence.put("syntheticAlbumMatched", selection?.isPhoto == false)
            .put("syntheticFilenameMatchedDirectly", selection?.isPhoto == true)
        safeRequired(selection != null, evidence, "NO_SAFE_UNIQUE_OWNED_ALBUM_OR_FILENAME_PATH")
        val target = if (checkNotNull(selection).isPhoto) selection.node else {
            activate(selection.node)
            val albumHeader = waitForUniqueNode(8_000) { node -> node.isVisibleToUser && ownAlbumLabel(node) }
            safeRequired(albumHeader != null, evidence, "SYNTHETIC_ALBUM_CONTEXT_NOT_ATTESTED")
            waitForUniqueOwnedPhoto(10_000)
        }
        // No device-wide query: attest only the exact row returned to this test.
        verifyOwnedRow(media.uri)
        evidence.put("uniqueOwnedPhotoMatched", target != null)
        safeRequired(target != null, evidence, "SYNTHETIC_PHOTO_NOT_UNIQUE_OR_NOT_ACCESSIBLE")
        evidence.put("safeLocatorReached", true)
        activate(checkNotNull(target))
        // Single selection usually returns immediately. Some OEM pickers require Done/Select.
        if (!waitForAppReturn(3_000)) {
            val done = waitForUniqueNode(5_000) { node -> node.isVisibleToUser && node.isEnabled &&
                (exactLabel(node, setOf("Add", "Done", "Select", "添加", "完成", "选择")) ||
                    Regex("^(Add|Done|Select|添加|完成|选择)[ （(]1[)）]?$", RegexOption.IGNORE_CASE)
                        .matches(label(node))) }
            safeRequired(done != null, evidence, "SINGLE_SELECTION_CONFIRMATION_NOT_UNIQUE")
            activate(checkNotNull(done))
        }
        safeRequired(waitForAppReturn(10_000), evidence, "PICKER_DID_NOT_RETURN_TO_PRODUCTION_ROUTE")
        pickerOpen = false
        rule.waitUntil(15_000) { onMain { vm().images?.state?.value is ImageImportState.Inserted } }
        val asset = onMain { vm().coordinator.currentAssets.single() }
        val source = onMain { native.bridge!!.session.source }
        assertTrue(source.contains(asset.relativePath))
        assertTrue(source.startsWith(original))
        assertTrue(onMain { native.bridge!!.session.projection.nodes.any { it.node.atomic?.kind == "image" } })
        assertSame(native, native())
        assertSame(editable, onMain { native.text })
        val file = checkNotNull(fixture.cards.assetFile(fixture.card, asset))
        assertTrue(file.isFile)
        assertEquals(asset.sha256, KnowledgeCard.sha256(file.readBytes()))
        assertEquals(fixture.formal.markdown, fixture.notes.find(fixture.card.cardId, fixture.card.cardVersion)?.markdown)
        rule.onNodeWithTag("markdown-note-done").performClick()
        awaitReading()
        val formal = checkNotNull(fixture.notes.find(fixture.card.cardId, fixture.card.cardVersion))
        assertEquals(source, formal.markdown)
        assertEquals(fixture.formal.markdownRevision + 1, formal.markdownRevision)
        assertEquals(listOf(asset), formal.structuredMetadata.assets)
        val snapshot = FormalMarkdownSnapshotResolver.resolve(fixture.card, formal)
        val exported = KnowledgeCardExport.portable(snapshot.card) {
            KnowledgeCardExport.readManagedAsset(it, fixture.cards.assetFile(snapshot.card, it))
        }
        assertTrue(exported.isFullyPortable)
        assertEquals(1, exported.assets.embeddedCount)
        assertTrue(exported.markdown.contains("data:image/png;base64,"))
        assertFalse(exported.markdown.contains(asset.relativePath))
        evidence.put("realSystemPickerSelected", true).put("productionAtomicImage", true)
            .put("sameNativeView", true).put("sameEditable", true).put("formalRevision", formal.markdownRevision)
            .put("managedAssetSha256", asset.sha256).put("portableEmbeddedCount", exported.assets.embeddedCount)
            .put("portableFullyResolved", true)
    }

    @Test fun realSystemPickerCancelKeepsSourceSelectionBufferAndFormalRevision() = report("cancel") { evidence ->
        requirePicker(evidence)
        val fixture = fixture()
        mount(fixture)
        awaitReading(); editAtEnd()
        val native = native()
        val editable = onMain { native.text }
        val source = onMain { native.bridge!!.session.source }
        val selection = onMain { native.bridge!!.sourceSelection() }
        chooseRealPhoto(evidence)
        evidence.put("systemPickerOpened", true)
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        safeRequired(waitForAppReturn(10_000), evidence, "PICKER_CANCEL_DID_NOT_RETURN")
        pickerOpen = false
        rule.waitUntil(8_000) { onMain { vm().images?.state?.value is ImageImportState.Cancelled } }
        assertEquals(source, onMain { native.bridge!!.session.source })
        assertEquals(selection, onMain { native.bridge!!.sourceSelection() })
        assertTrue(onMain { vm().coordinator.currentAssets.isEmpty() })
        assertSame(native, native())
        assertSame(editable, onMain { native.text })
        rule.onNodeWithTag("markdown-note-done").performClick(); awaitReading()
        assertEquals(fixture.formal.markdownRevision, fixture.notes.find(fixture.card.cardId, fixture.card.cardVersion)?.markdownRevision)
        evidence.put("realSystemPickerCancelled", true).put("sourceExact", true).put("selectionExact", true)
            .put("sameNativeView", true).put("sameEditable", true).put("revisionUnchanged", true)
    }

    private fun requirePicker(evidence: JSONObject) {
        safeRequired(Build.VERSION.SDK_INT >= 33, evidence, "SYSTEM_PHOTO_PICKER_REQUIRES_API_33")
        safeRequired(ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context), evidence, "PHOTO_PICKER_NOT_AVAILABLE")
    }
    private fun chooseRealPhoto(evidence: JSONObject) {
        val service = instrumentation.uiAutomation.serviceInfo
        originalAutomationFlags = service.flags
        service.flags = service.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = service
        rule.onNodeWithTag("markdown-note-insert").performClick()
        rule.onNodeWithTag("markdown-note-pick-photo").performClick()
        pickerOpen = true
        val found = waitFor(10_000) {
            pickerRoot() != null
        }
        captureSafeStructure(evidence, "opened")
        safeRequired(found, evidence, "SYSTEM_PICKER_SURFACE_NOT_RECOGNIZED")
    }
    private data class PickerSelection(val node: AccessibilityNodeInfo, val isPhoto: Boolean)
    private fun navigateToOwnedTarget(evidence: JSONObject): PickerSelection? {
        val seen = mutableSetOf<String>()
        repeat(5) { step ->
            captureSafeStructure(evidence, "navigation-$step")
            val root = pickerRoot() ?: return null
            uniqueFilenamePhoto(root, evidence)?.let { return PickerSelection(it, true) }
            val direct = nodes(root).filter { it.isVisibleToUser && ownAlbumLabel(it) }
                .map(::clickTarget).distinctBy(::boundsKey).toList()
            evidence.put("syntheticAlbumVisibleMatches", direct.size)
            if (direct.size == 1) return PickerSelection(direct.single(), false)
            if (direct.size > 1) return null
            if (step == 4) return null
            val album = waitForAlbumTab(1_000, evidence)
            if (album != null) {
                val navigationId = "${root.packageName}:albums:${boundsKey(album)}"
                if (seen.add(navigationId)) {
                    activate(album)
                    evidence.put("fixedNavigationSteps", seen.size)
                    SystemClock.sleep(400)
                    val found = findOwnedAlbumWithBoundedScroll(evidence)
                    if (found != null) return PickerSelection(found, false)
                }
            }
            val refreshed = pickerRoot() ?: return null
            val navigation = FIXED_NAVIGATION.entries.firstNotNullOfOrNull { entry ->
                val targets = nodes(refreshed).filter { node -> node.isVisibleToUser && node.isEnabled &&
                    fixedTabLabel(node, entry.value) && navigationStructure(node) }
                    .map(::clickTarget).filter { it.isVisibleToUser && it.isEnabled }.distinctBy(::boundsKey).toList()
                if (targets.size != 1) null else {
                    val key = "${refreshed.packageName}:${entry.key}:${boundsKey(targets.single())}"
                    if (key in seen) null else Triple(key, entry.key, targets.single())
                }
            }
            if (navigation == null) {
                if (seen.add("expand-system-sheet") && expandKnownPickerSheet(evidence)) {
                    SystemClock.sleep(500)
                    return@repeat
                }
                return null
            }
            seen += navigation.first
            evidence.put("lastFixedNavigation", navigation.second).put("fixedNavigationSteps", seen.size)
            activate(navigation.third)
            SystemClock.sleep(450)
        }
        return null
    }
    /** Global Photos is selectable only with an exact per-run filename/token, never by position. */
    private fun uniqueFilenamePhoto(root: AccessibilityNodeInfo, evidence: JSONObject): AccessibilityNodeInfo? {
        val candidates = nodes(root).filter { node ->
            val ownText = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .any { it == fileName || it.contains(fileName) || (it.contains(mediaToken) && !ownAlbumLabel(node)) }
            if (!node.isVisibleToUser || !ownText || ownAlbumLabel(node)) false else {
                val target = clickTarget(node)
                val thumbnail = node.className?.toString()?.endsWith("ImageView") == true || node.isCheckable ||
                    nodes(target).any { it.viewIdResourceName.orEmpty().let { id -> id.endsWith(":id/icon_thumbnail") || id.endsWith(":id/thumbnail") } }
                thumbnail && target.isEnabled
            }
        }.map(::clickTarget).distinctBy(::boundsKey).toList()
        evidence.put("exactOwnedFilenamePhotoMatches", candidates.size)
        return candidates.singleOrNull()
    }
    private fun expandKnownPickerSheet(evidence: JSONObject): Boolean {
        val root = pickerRoot() ?: return false
        if (root.packageName?.toString() !in setOf("com.android.providers.media.module", "com.google.android.providers.media.module")) return false
        val sheets = nodes(root).filter { it.isVisibleToUser && it.viewIdResourceName.orEmpty().endsWith(":id/bottom_sheet") }.toList()
        val handles = nodes(root).filter { it.isVisibleToUser && it.viewIdResourceName.orEmpty().endsWith(":id/drag_bar") }.toList()
        if (sheets.size != 1 || handles.size != 1) return false
        val expandable = listOf(handles.single(), sheets.single()).firstOrNull { node ->
            node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id } }
        if (expandable != null && expandable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id)) {
            evidence.put("knownBottomSheetExpanded", true).put("sheetExpandMethod", "platform-action")
            return true
        }
        val handle = Rect().also(handles.single()::getBoundsInScreen)
        val sheet = Rect().also(sheets.single()::getBoundsInScreen)
        val density = context.resources.displayMetrics.density
        if (handle.isEmpty || !sheet.contains(handle.centerX(), handle.centerY()) || handle.height() > 64 * density ||
            sheet.top < 80 * density) return false
        val endY = (32 * density).coerceAtLeast(1f)
        val x = handle.exactCenterX()
        val startY = handle.exactCenterY()
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, startY)
        repeat(12) { step -> SystemClock.sleep(25); send(MotionEvent.ACTION_MOVE, startY + (endY - startY) * (step + 1) / 12) }
        send(MotionEvent.ACTION_UP, endY)
        evidence.put("knownBottomSheetExpanded", true).put("sheetExpandMethod", "observed-drag-bar-gesture")
        return true
    }
    /** Window/package/class/IDs only. Never serialize text, descriptions, titles, or the tree. */
    private fun captureSafeStructure(evidence: JSONObject, phase: String) {
        val root = pickerRoot() ?: instrumentation.uiAutomation.rootInActiveWindow
        val windows = instrumentation.uiAutomation.windows.orEmpty()
        val types = JSONObject()
        windows.groupingBy { it.type }.eachCount().forEach { (type, count) -> types.put(type.toString(), count) }
        val diagnostic = JSONObject().put("phase", phase).put("windowCount", windows.size).put("windowTypes", types)
            .put("rootPackageName", root?.packageName?.toString() ?: "none")
            .put("rootClassName", root?.className?.toString() ?: "none")
        val visible = root?.let { nodes(it).filter { node -> node.isVisibleToUser }.toList() }.orEmpty()
        val owner = root?.packageName?.toString()
        val systemOwner = owner?.let { packageName -> runCatching { context.packageManager.getApplicationInfo(packageName, 0).flags and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }.getOrDefault(false) } == true
        val ids = visible.mapNotNull { it.viewIdResourceName }.filter { id ->
            id.matches(Regex("[A-Za-z0-9_.]+:id/[A-Za-z0-9_]+")) &&
                ((systemOwner && id.substringBefore(":id/") == owner) || id.startsWith("android:id/"))
        }.distinct().take(80)
        diagnostic.put("visibleResourceIds", JSONArray(ids)).put("visibleResourceIdCount", ids.size)
        val labels = JSONObject()
        (FIXED_NAVIGATION + mapOf("albums" to ALBUM_TAB_LABELS)).forEach { (key, values) ->
            labels.put(key, visible.count { fixedTabLabel(it, values) })
        }
        diagnostic.put("fixedNavigationLabelCounts", labels)
        val tabs = visible.filter { it.viewIdResourceName.orEmpty().endsWith(":id/tab_layout") }.toList()
        val tabNodes = JSONArray()
        tabs.flatMap { nodes(it).toList() }.filter { node -> node.isVisibleToUser &&
            (tabRole(node) || node.isClickable || node.isSelected) }.distinctBy(::boundsKey).take(12).forEach { node ->
                val fixed = (FIXED_NAVIGATION + mapOf("albums" to ALBUM_TAB_LABELS)).entries
                    .firstOrNull { fixedTabLabel(node, it.value) }?.key ?: "unrecognized"
                tabNodes.put(JSONObject().put("class", node.className?.toString()).put("clickable", node.isClickable)
                    .put("selected", node.isSelected).put("childCount", node.childCount).put("fixedLabel", fixed))
            }
        diagnostic.put("tabLayoutCount", tabs.size).put("fixedTabStructure", tabNodes)
            .put("visibleButtonRoleCount", visible.count { it.className?.toString()?.endsWith("Button") == true })
        val history = evidence.optJSONArray("safeStructure") ?: JSONArray().also { evidence.put("safeStructure", it) }
        if (history.length() < 7) history.put(diagnostic)
    }
    private fun navigationStructure(node: AccessibilityNodeInfo): Boolean {
        val target = clickTarget(node)
        val type = target.className?.toString().orEmpty()
        val id = (node.viewIdResourceName ?: target.viewIdResourceName).orEmpty().substringAfter(":id/", "")
        val bounds = Rect().also(target::getBoundsInScreen)
        val density = context.resources.displayMetrics.density
        val screenHeight = context.resources.displayMetrics.heightPixels
        val toolbarButton = type.endsWith("Button") && bounds.height() <= 80 * density &&
            (bounds.centerY() < screenHeight * .4f || bounds.centerY() > screenHeight * .8f)
        var ancestor: AccessibilityNodeInfo? = node
        var withinTabLayout = false
        repeat(5) { if (ancestor?.viewIdResourceName.orEmpty().endsWith(":id/tab_layout")) withinTabLayout = true
            ancestor = ancestor?.parent }
        return tabRole(node) || tabRole(target) || withinTabLayout || toolbarButton ||
            id in setOf("button_browse", "browse", "navigation_header", "navigation_menu", "drawer_toggle", "picker_tab_photos", "tab_photos")
    }
    private fun pickerRoot(): AccessibilityNodeInfo? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let { roots += it }
        instrumentation.uiAutomation.windows.orEmpty().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
            .sortedByDescending { it.isActive || it.isFocused }.mapNotNullTo(roots) { it.root }
        return roots.firstOrNull { root ->
            val name = root.packageName?.toString() ?: return@firstOrNull false
            if (name in setOf(context.packageName, "com.android.systemui")) return@firstOrNull false
            val imePackage = android.provider.Settings.Secure.getString(context.contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
            if (name == imePackage) return@firstOrNull false
            val system = runCatching { context.packageManager.getApplicationInfo(name, 0).flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }.getOrDefault(false)
            system && (name.contains("media") || name.contains("document") || name.contains("gallery") ||
                name.contains("photo") || name.contains("filemanager") || name.contains("fileexplorer") || name.contains("resolver"))
        }
    }
    /** Accept fixed navigation text and standard accessibility tab suffixes, never album names. */
    private fun waitForAlbumTab(timeoutMs: Long, evidence: JSONObject): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        waitFor(timeoutMs) {
            val current = pickerRoot() ?: return@waitFor false
            val visible = nodes(current).filter { it.isVisibleToUser }.toList()
            val fixedLabels = visible.filter { fixedTabLabel(it, ALBUM_TAB_LABELS) && navigationStructure(it) }
            evidence.put("albumTabFixedLabelCount", fixedLabels.size)
            evidence.put("albumTabExactLabelCount", visible.count { exactLabel(it, ALBUM_TAB_LABELS) })
                .put("albumTabKnownIdCount", visible.count { knownAlbumTabId(it) })
                .put("albumTabSelectedLabelCount", fixedLabels.count { it.isSelected })
            val exactTargets = fixedLabels.map(::clickTarget).filter { it.isVisibleToUser && it.isEnabled }
                .distinctBy(::boundsKey)
            evidence.put("albumTabTargetCount", exactTargets.size)
            val selected = if (exactTargets.size == 1) exactTargets.single() else {
                // Tabs may expose both a visible text child and virtual role parent.
                // Prefer only the tab-role/resource node with that same fixed label.
                val roles = fixedLabels.filter { tabRole(it) }.map(::clickTarget)
                    .filter { it.isVisibleToUser && it.isEnabled }.distinctBy(::boundsKey)
                evidence.put("albumTabRoleTargetCount", roles.size)
                roles.singleOrNull() ?: visible.filter(::knownAlbumTabId).map(::clickTarget)
                    .filter { it.isVisibleToUser && it.isEnabled }.distinctBy(::boundsKey).singleOrNull()
            }
            val navigation = selected ?: otherSystemTabFromAttestedPair(current, evidence)
            if (navigation != null) { result = navigation; true } else false
        }
        return result
    }
    /**
     * An OEM may omit its category label. Selecting the sole other tab is safe
     * only when the system TabLayout itself proves an exact two-tab structure
     * anchored by the selected Photos tab. This never promotes a media cell.
     */
    private fun otherSystemTabFromAttestedPair(root: AccessibilityNodeInfo, evidence: JSONObject): AccessibilityNodeInfo? {
        val owner = root.packageName?.toString()
        if (owner !in setOf("com.android.providers.media.module", "com.google.android.providers.media.module")) return null
        val layouts = nodes(root).filter { it.isVisibleToUser && it.viewIdResourceName == "$owner:id/tab_layout" }.toList()
        evidence.put("twoTabSystemLayoutCount", layouts.size)
        if (layouts.size != 1) return null
        val tabLayout = layouts.single()
        val layoutBounds = Rect().also(tabLayout::getBoundsInScreen)
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        var groups = 0
        nodes(tabLayout).forEach { group ->
            val children = (0 until group.childCount).mapNotNull(group::getChild).filter { it.isVisibleToUser }
            if (children.size != 2 || children.any { node ->
                    node.childCount == 0 || node.packageName?.toString() != owner ||
                        node.className?.toString()?.let { type -> !type.endsWith("Layout") && !type.contains("Tab") } != false
                }) return@forEach
            val rectangles = children.map { Rect().also(it::getBoundsInScreen) }
            if (rectangles.any { it.isEmpty || !layoutBounds.contains(it.centerX(), it.centerY()) } ||
                Rect.intersects(rectangles[0], rectangles[1])) return@forEach
            val selected = children.filter { node -> node.isSelected || nodes(node).any { it.isSelected } }
            if (selected.size != 1 || nodes(selected.single()).none { fixedTabLabel(it, FIXED_NAVIGATION.getValue("photos")) }) return@forEach
            val other = children.single { it !== selected.single() }
            if (other.isSelected || nodes(other).any { it.isSelected } || !other.isEnabled || !other.isClickable) return@forEach
            if (children.any { node -> nodes(node).any { descendant ->
                    descendant.viewIdResourceName.orEmpty().let { id -> id.endsWith(":id/icon_thumbnail") || id.endsWith(":id/thumbnail") }
                } }) return@forEach
            groups++
            candidates += other
        }
        val unique = candidates.distinctBy(::boundsKey)
        evidence.put("twoTabPairGroupCount", groups).put("twoTabOtherTargetCount", unique.size)
        if (groups != 1 || unique.size != 1) return null
        evidence.put("twoTabNavigationAttested", true).put("twoTabDirectItemCount", 2)
            .put("twoTabSelectedPhotosCount", 1).put("twoTabUnselectedClickableCount", 1)
        return unique.single()
    }
    private fun tabRole(node: AccessibilityNodeInfo): Boolean {
        val type = node.className?.toString().orEmpty()
        return type.contains("Tab", ignoreCase = true) || knownAlbumTabId(node) || node.roleDescription()?.let {
                it.equals("tab", ignoreCase = true) || it in setOf("标签页", "选项卡")
            } == true
    }
    private fun knownAlbumTabId(node: AccessibilityNodeInfo): Boolean = node.viewIdResourceName.orEmpty().let {
        it.endsWith(":id/picker_tab_albums") || it.endsWith(":id/tab_albums")
    }
    private fun AccessibilityNodeInfo.roleDescription(): String? = extras
        .getCharSequence("AccessibilityNodeInfo.roleDescription")?.toString()
    private fun fixedTabLabel(node: AccessibilityNodeInfo, values: Set<String>): Boolean {
        if (exactLabel(node, values)) return true
        return listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).any { value ->
            values.any { base ->
                if (!value.startsWith(base, ignoreCase = true)) false else {
                    val suffix = value.substring(base.length)
                    val hasRole = tabRole(node) || Regex("tab|标签页|选项卡", RegexOption.IGNORE_CASE).containsMatchIn(suffix)
                    val remainder = suffix.replace(Regex("not selected|unselected|selected|tab|of|已选择|未选择|已选中|未选中|标签页|选项卡|第|个|共|项",
                        RegexOption.IGNORE_CASE), "").replace(Regex("[\\s,，、:：()（）/\\d]+"), "")
                    hasRole && remainder.isEmpty()
                }
            }
        }
    }
    private fun findOwnedAlbumWithBoundedScroll(evidence: JSONObject): AccessibilityNodeInfo? {
        var pages = 0
        repeat(9) {
            val found = waitForUniqueNode(if (pages == 0) 8_000 else 1_500) { node ->
                node.isVisibleToUser && ownAlbumLabel(node)
            }
            val current = pickerRoot()
            evidence.put("syntheticAlbumVisibleMatches", current?.let { root ->
                nodes(root).count { it.isVisibleToUser && ownAlbumLabel(it) }
            } ?: 0)
            if (found != null) { evidence.put("albumListScrollCount", pages); return found }
            if (current == null) return null
            // Move only the system's album list. Never click another album or a thumbnail.
            val scrollable = nodes(current).filter { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                node.isVisibleToUser && node.isScrollable && bounds.height() > 160 * context.resources.displayMetrics.density &&
                    (node.className?.toString()?.let { type -> type.contains("RecyclerView") || type.contains("GridView") ||
                        type.contains("ScrollView") } == true || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
            }.distinctBy(::boundsKey).sortedByDescending { node -> Rect().also(node::getBoundsInScreen).let { it.width().toLong() * it.height() } }.toList()
            val list = scrollable.firstOrNull()
            evidence.put("albumScrollableContainerCount", scrollable.size)
            if (list == null || (scrollable.size > 1 && boundsKey(list) == boundsKey(scrollable[1]))) return null
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return null
            pages++
            evidence.put("albumListScrollCount", pages)
            SystemClock.sleep(350)
        }
        return null
    }
    private fun waitForUniqueOwnedPhoto(timeoutMs: Long): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        waitFor(timeoutMs) {
            val current = pickerRoot() ?: return@waitFor false
            if (nodes(current).none(::ownAlbumLabel)) return@waitFor false
            val exact = nodes(current).filter { it.isVisibleToUser && (it.text?.toString() == fileName ||
                it.contentDescription?.toString()?.contains(fileName) == true) }.map(::clickTarget).distinctBy(::boundsKey).toList()
            val candidates = if (exact.isNotEmpty()) exact else nodes(current).filter { node ->
                val description = node.contentDescription?.toString().orEmpty()
                val id = node.viewIdResourceName.orEmpty()
                val thumbnailId = id.endsWith(":id/icon_thumbnail") || id.endsWith(":id/thumbnail")
                val photoLabel = description in setOf("Photo", "Image", "照片", "图片", "相片") ||
                    description.startsWith("Photo taken", true) || description.startsWith("Image taken", true) ||
                    description.startsWith("照片拍摄") || description.startsWith("图片拍摄") ||
                    description.startsWith("照片，") || description.startsWith("图片，")
                node.isVisibleToUser && (thumbnailId || (photoLabel &&
                    (node.className?.toString()?.endsWith("ImageView") == true || node.isCheckable))) &&
                    !description.contains("button", true) && !description.contains("按钮")
            }.map(::clickTarget).filter { it.isClickable && it.isEnabled }.distinctBy(::boundsKey).toList()
            if (candidates.size == 1) { result = candidates.single(); true } else false
        }
        return result
    }
    private fun waitForUniqueNode(timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        waitFor(timeoutMs) {
            val current = pickerRoot() ?: return@waitFor false
            val matches = nodes(current).filter(predicate).map(::clickTarget).distinctBy(::boundsKey).toList()
            if (matches.size == 1) { result = matches.single(); true } else false
        }
        return result
    }
    private fun ownAlbumLabel(node: AccessibilityNodeInfo): Boolean = node.text?.toString() == album ||
        node.contentDescription?.toString()?.let { it == album || it.startsWith("$album,") || it.startsWith("$album，") } == true
    private fun exactLabel(node: AccessibilityNodeInfo, values: Set<String>) = values.any {
        it.equals(node.text?.toString(), ignoreCase = true) || it.equals(node.contentDescription?.toString(), ignoreCase = true)
    }
    private fun label(node: AccessibilityNodeInfo) = node.text?.toString() ?: node.contentDescription?.toString().orEmpty()
    private fun clickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        repeat(6) {
            if (current.isClickable && current.isEnabled) return current
            val parent = current.parent ?: return current
            val bounds = Rect().also(parent::getBoundsInScreen)
            // Never promote a label to an entire modal/list. A direct touch on
            // its exact synthetic label remains safer than an unrelated parent.
            if (bounds.height() > 240 * context.resources.displayMetrics.density) return node
            current = parent
        }
        return node
    }
    private fun boundsKey(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen).toShortString()
    private fun activate(node: AccessibilityNodeInfo) {
        assertTrue("Synthetic target must be visible", node.isVisibleToUser)
        val matched = clickTarget(node)
        if (matched.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val bounds = Rect().also(matched::getBoundsInScreen)
        assertFalse(bounds.isEmpty)
        val time = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN); SystemClock.sleep(50); send(MotionEvent.ACTION_UP)
    }
    private fun waitForAppReturn(timeoutMs: Long) = waitFor(timeoutMs) {
        instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName
    }
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return true; SystemClock.sleep(100) }
        return false
    }
    private fun nodes(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(root)
        for (index in 0 until root.childCount) root.getChild(index)?.let { yieldAll(nodes(it)) }
    }

    private fun createOwnedImage(): OwnedMedia {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$album/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        insertedUri = uri
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(57, 150, 116)) }
        val bytes = ByteArrayOutputStream().use { output ->
            try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
            finally { bitmap.recycle() }
        }
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
        assertEquals(1, context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null))
        // Publishing the closed row is the MediaStore boundary. Notify only this owned URI.
        context.contentResolver.notifyChange(uri, null)
        verifyOwnedRow(uri)
        return OwnedMedia(uri, KnowledgeCard.sha256(bytes))
    }
    private fun verifyOwnedRow(uri: Uri) {
        require(uri == insertedUri)
        val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.IS_PENDING)
        checkNotNull(context.contentResolver.query(uri, projection, null, null, null)).use { cursor ->
            assertEquals(1, cursor.count); assertTrue(cursor.moveToFirst())
            assertEquals(fileName, cursor.getString(0))
            assertEquals("${Environment.DIRECTORY_PICTURES}/$album/", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
        }
    }
    private data class OwnedMedia(val uri: Uri, val sha256: String)

    private fun report(name: String, action: (JSONObject) -> Unit) {
        val evidence = JSONObject().put("schemaVersion", 1).put("runId", runId).put("case", name)
            .put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT).put("productionRoute", "MarkdownNoteRoute")
            .put("registryInjected", false).put("passed", false).put("safeLocatorReached", false)
        try { action(evidence); evidence.put("passed", true) }
        catch (error: AssumptionViolatedException) { evidence.put("skipped", true)
            .put("reasonCode", evidence.optString("reasonCode", "SYSTEM_PICKER_SURFACE_NOT_RECOGNIZED")); throw error }
        catch (error: Throwable) { evidence.put("failureType", error.javaClass.simpleName); throw error }
        finally {
            val output = File(context.getExternalFilesDir(null), "note-editor-system-picker/$runId")
            check(output.mkdirs() || output.isDirectory)
            File(output, "$name.json").writeText(evidence.toString(2))
        }
    }
    private fun safeRequired(value: Boolean, evidence: JSONObject, code: String) {
        if (!value) evidence.put("reasonCode", code)
        assumeTrue(code, value)
    }
    private fun fixture(): Fixture {
        check(root.mkdirs() || root.isDirectory)
        val cards = FileKnowledgeCardRepository(File(root, "cards"))
        val card = cards.save(KnowledgeCard(KnowledgeCardId("synthetic", "system-picker"), "v1", "https://example.com/synthetic",
            "合成系统图片选择", "synthetic", 1L, TranscriptTimingAccuracy.EXACT, emptyList(),
            analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = "# 合成系统图片选择\n\n原正文完整保留\n\n"))
        val notes = FileNoteContentRepository(File(root, "notes"))
        val formal = notes.save(NoteContent(card.cardId, card.cardVersion, card.markdown, 0L, card.toNoteDocument(),
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L)).content
        return Fixture(cards, card, notes, formal, NoteAssetStore(cards, card, root))
    }
    private fun mount(fixture: Fixture) {
        activeFixture = fixture
        rule.setContent { UnarchiveTheme { MarkdownNoteRoute(fixture.card.cardId, fixture.card.cardVersion, fixture.notes,
            onBack = {}, imageImporter = fixture.importer,
            readAsset = { KnowledgeCardExport.readManagedAsset(it, fixture.cards.assetFile(fixture.card, it)) }) } }
    }
    private fun awaitReading() = rule.waitUntil(12_000) { rule.onAllNodesWithTag("markdown-note-edit").fetchSemanticsNodes().isNotEmpty() }
    private fun editAtEnd() {
        rule.onNodeWithTag("markdown-note-edit").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("markdown-note-done").fetchSemanticsNodes().isNotEmpty() }
        onMain { val bridge = nativeView().bridge!!; bridge.revealSourceOffset(bridge.session.source.length) }
    }
    private fun vm() = ViewModelProvider(rule.activity)[
        "markdown-note-${activeFixture.card.cardId.value}-${activeFixture.card.cardVersion}", MarkdownNoteViewModel::class.java]
    private fun native() = onMain { nativeView() }
    private fun nativeView() = descendants(rule.activity.window.decorView).filterIsInstance<NativeDocumentEditText>().single()
    private fun descendants(view: View): Sequence<View> = sequence { yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index))) }
    private fun <T> onMain(action: () -> T) = rule.runOnIdle(action)
    private data class Fixture(val cards: FileKnowledgeCardRepository, val card: KnowledgeCard,
        val notes: FileNoteContentRepository, val formal: NoteContent, val importer: NoteAssetStore)
    private companion object {
        val ALBUM_TAB_LABELS = setOf("Albums", "Collections", "Folders", "相册", "相簿", "图集", "文件夹")
        val FIXED_NAVIGATION = linkedMapOf("browse" to setOf("Browse", "浏览"),
            "photos" to setOf("Photos", "照片", "相片"), "images" to setOf("Images", "图片", "图片和视频"))
    }
}
