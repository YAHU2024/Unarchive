package com.unarchive.android.editor.ai

import com.unarchive.android.card.CardAsset
import com.unarchive.android.card.CardAssetKind
import org.junit.Assert.*
import org.junit.Test

class AiMarkdownSafetyTest {
    private val asset = CardAsset("img", CardAssetKind.OTHER, "image/png", "assets/image.png", 3L, "a".repeat(64))
    private fun safe(source: String, exists: Boolean = true) = AiMarkdownSafety.validate(source, listOf(asset)) { exists }

    @Test fun generalWebAndAnchorLinksAreSupportedByNewEditor() {
        assertTrue(safe("[web](http://example.com/a_(b) \"T\")\n[secure](https://example.org)\n[anchor](#part)").isSafe)
        assertTrue(safe("<https://example.com/a>\nhttps://example.com/b").isSafe)
    }

    @Test fun inlineAndReferenceDangerousSchemesAreBlocked() {
        listOf("javascript:alert(1)", "data:text/plain,x", "file:///tmp/x", "intent://x", "mailto:a@b.com").forEach { uri ->
            assertFalse(uri, safe("[x]($uri)").isSafe)
            assertFalse(uri, safe("[x][ref]\n\n[ref]: $uri").isSafe)
            assertFalse(uri, safe("[ref][]\n\n[ref]: $uri").isSafe)
        }
    }

    @Test fun htmlOutsideCodeIsBlockedButCodeFenceAndCodeSpanAreLiteral() {
        assertFalse(safe("text <img src=x>").isSafe)
        assertFalse(safe("<!-- raw -->").isSafe)
        assertTrue(safe("````md\n```\n[bad](javascript:alert(1))\n<img src=x>\n````\n`[bad](file:///tmp/x)`").isSafe)
    }

    @Test fun nonemptyAltReferenceRepeatedAndTitleImagesResolveAgainstManagedManifest() {
        assertTrue(safe("![description](assets/image.png \"T\")\n![again][img]\n![img]\n\n[img]: <assets/image.png> \"T\"").isSafe)
    }

    @Test fun safeLookingImagePathStillRequiresActualOwnedFile() {
        assertFalse(safe("![x](assets/image.png)", exists = false).isSafe)
        assertFalse(safe("![x](assets/missing.png)").isSafe)
    }

    @Test fun remoteTraversalEncodedTraversalAndAbsoluteImagesAreBlocked() {
        listOf("https://example.com/x.png", "assets/../secret.png", "assets/%2e%2e/secret.png",
            "/secret.png", "C:/secret.png", "//host/x.png", "assets/image.png?x=1").forEach {
            assertFalse(it, safe("![x]($it)").isSafe)
        }
    }

    @Test fun unusedReferenceInsideFenceDoesNotOverrideActualDefinition() {
        assertTrue(safe("```\n[img]: https://evil.example/x.png\n```\n![x][img]\n\n[img]: assets/image.png").isSafe)
    }

    @Test fun escapedBracketsAndUnknownTextRemainLiteral() {
        assertTrue(safe("\\[bad](javascript:alert(1))").isSafe)
        assertTrue(safe("`![x](https://evil/x.png)`\n普通文字").isSafe)
    }
}
