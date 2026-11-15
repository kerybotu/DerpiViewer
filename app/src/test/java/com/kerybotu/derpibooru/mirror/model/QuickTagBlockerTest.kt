package com.kerybotu.derpibooru.mirror.model

import org.junit.Assert.*
import org.junit.Test

class QuickTagBlockerTest {
    private fun filter(query: String?) = Filter(-1, "local", "", null, false, false, 0, 0, localQuery = query, isLocal = true)

    @Test fun addsQuotedTagAndKeepsExistingConditions() {
        val result = QuickTagBlocker.withBlockedTag(filter("safe -explicit"), "oc:frostwing blade", true)!!
        assertEquals("safe -explicit -\"oc:frostwing blade\"", result.localQuery)
    }

    @Test fun duplicateAddIsCollapsed() {
        val result = QuickTagBlocker.withBlockedTag(filter("-Cat -cat"), " cat ", true)!!
        assertEquals("-cat", result.localQuery)
    }

    @Test fun removesOnlyMatchingExclude() {
        val result = QuickTagBlocker.withBlockedTag(filter("safe -cat -dog"), "CAT", false)!!
        assertEquals("safe -dog", result.localQuery)
    }

    @Test fun includeDoesNotCountAsBlocked() {
        assertFalse(QuickTagBlocker.isBlocked(filter("cat"), "cat"))
    }
}
