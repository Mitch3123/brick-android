package com.brick.app.ui.design

import com.brick.app.feature.nfc.ScanMedium
import com.brick.app.ui.scan.showsCamera
import org.junit.Assert.*
import org.junit.Test

class ReferenceSelectionTest {
    @Test fun selectionTogglesWithoutMutatingOriginal() {
        val original = setOf("one")
        assertEquals(setOf("one", "two"), toggleAppSelection(original, "two"))
        assertEquals(emptySet<String>(), toggleAppSelection(original, "one"))
        assertEquals(setOf("one"), original)
    }
    @Test fun capRejectsNewAppsButAllowsDeselection() {
        val full = (1..MAX_MODE_APPS).map { "app.$it" }.toSet()
        assertEquals(full, toggleAppSelection(full, "overflow"))
        assertEquals(49, toggleAppSelection(full, "app.1").size)
    }
    @Test fun anyMediumRequiresExplicitCameraChoice() {
        assertFalse(showsCamera(ScanMedium.ANY, false))
        assertTrue(showsCamera(ScanMedium.ANY, true))
        assertTrue(showsCamera(ScanMedium.QR, false))
        assertFalse(showsCamera(ScanMedium.NFC, true))
    }
}
