package com.kerybotu.derpibooru.mirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackdropSourceSelectionTest {
    @Test
    fun autoParentSelectsParentSource() {
        val parent = Any()

        assertEquals(parent, selectBackdropSource(BackdropSourceMode.AUTO_PARENT, Any(), parent))
        assertEquals(parent, selectBackdropSource(BackdropSourceMode.AUTO_PARENT, null, parent))
    }

    @Test
    fun explicitSourceNeverFallsBackToParent() {
        val parent = Any()

        assertNull(selectBackdropSource(BackdropSourceMode.EXPLICIT_SOURCE, null, parent))
    }

    @Test
    fun disabledNeverSelectsAnySource() {
        assertNull(selectBackdropSource(BackdropSourceMode.DISABLED, Any(), Any()))
    }
}
