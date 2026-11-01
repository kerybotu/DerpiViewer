package com.kerybotu.derpibooru.mirror.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilterImportParserTest {
    private val domains = setOf("derpibooru.org", "trixiebooru.org")

    @Test fun acceptsIdAndDetailOrEditUrl() {
        assertEquals(215340, FilterImportParser.parseId("215340", domains))
        assertEquals(215340, FilterImportParser.parseId("https://derpibooru.org/filters/215340", domains))
        assertEquals(215340, FilterImportParser.parseId("https://derpibooru.org/filters/215340/edit", domains))
    }

    @Test fun rejectsWrongHostPathAndNonNumericId() {
        assertNull(FilterImportParser.parseId("https://example.com/filters/215340", domains))
        assertNull(FilterImportParser.parseId("https://derpibooru.org/images/215340", domains))
        assertNull(FilterImportParser.parseId("https://derpibooru.org/filters/nope", domains))
    }
}
