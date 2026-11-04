package com.kerybotu.derpibooru.mirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kerybotu.derpibooru.mirror.model.FilterSearchInput

class FilterHtmlParserTest {
    @Test
    fun parsesLegacyCardAndLinks() {
        val result = FilterHtmlParser.parse(
            """
            <div class="filter">
              <h3>Public filter</h3>
              <p>Maintained by <strong><a href="/profiles/495pygly">495pygly</a></strong></p>
              <div class="filter-options">
                <ul><li>Spoilers 6, hides 21</li><li><a href="/filters/215340">View</a></li>
                <li><a href="/filters/new?based_on=215340">Copy</a></li><li><a href="/filters/215340/edit">Edit</a></li></ul>
                <p><em>A safe filter.</em></p>
              </div>
            </div>
            """.trimIndent(), "derpibooru.org"
        )
        val filter = result.filters.single()
        assertEquals("Public filter", filter.name)
        assertEquals("495pygly", filter.creator)
        assertEquals(6, filter.spoilerCount)
        assertEquals(21, filter.hiddenCount)
        assertEquals("/filters/215340/edit", filter.remoteEditPath)
        assertEquals("A safe filter.", filter.description)
    }

    @Test
    fun parsesNestedTranslationFontsAndChineseStats() {
        val result = FilterHtmlParser.parse(
            """
            <div class="filter"><h3><font><font>公共场合用过滤器</font></font></h3>
              <p><strong><a href="/profiles/495pygly"><font><font>495pygly维护</font></font></a></strong></p>
              <div class="filter-options"><ul><li><font><font>剧透6，隐藏21</font></font></li>
                <li><a href="/filters/215340">查看此筛选器</a></li></ul><p><em><font><font>公共描述</font></font></em></p></div>
            </div>
            """.trimIndent(), "derpibooru.org"
        )
        val filter = result.filters.single()
        assertEquals("公共场合用过滤器", filter.name)
        assertEquals("495pygly", filter.creator)
        assertEquals(6, filter.spoilerCount)
        assertEquals(21, filter.hiddenCount)
        assertEquals("公共描述", filter.description)
    }

    @Test
    fun handlesMissingOptionalFieldsAndExternalLinksSafely() {
        val result = FilterHtmlParser.parse(
            """
            <div class="filter"><h3>Incomplete</h3><div class="filter-options">
              <a href="https://evil.example/filters/1">bad</a>
              <a href="/filters/2">good</a>
            </div></div>
            """.trimIndent(), "derpibooru.org"
        )
        val filter = result.filters.single()
        assertEquals(2, filter.id)
        assertEquals(-1, filter.spoilerCount)
        assertEquals(-1, filter.hiddenCount)
        assertNull(filter.creator)
        assertNull(filter.remoteEditPath)
    }

    @Test
    fun recognizesLoginChallengeAndEmptyPages() {
        assertTrue(FilterHtmlParser.parse("<form action='/login'><input name='password'></form>", "derpibooru.org").loginRequired)
        assertTrue(FilterHtmlParser.parse("<div id='anubis_challenge'>challenge</div>", "derpibooru.org").challengePage)
        val empty = FilterHtmlParser.parse("<main><h1>No filters found</h1></main>", "derpibooru.org")
        assertTrue(empty.filters.isEmpty())
        assertFalse(empty.loginRequired)
    }

    @Test
    fun readsNextPaginationPage() {
        val result = FilterHtmlParser.parse(
            "<a rel='next' href='/filters?fq=name%3Ax&page=2'>Next</a>",
            "derpibooru.org", currentPage = 1
        )
        assertEquals(2, result.nextPage)
    }

    @Test
    fun parameterInsertionKeepsExistingTextAndPlacesDescriptionCursor() {
        val inserted = FilterSearchInput.insert("name:default", 12, 12, "description:\"\"", 13)
        assertEquals("name:default description:\"\"", inserted.text)
        assertEquals(inserted.text.length - 1, inserted.cursor)

        val atMiddle = FilterSearchInput.insert("creator:foo", 0, 0, "system:true", 11)
        assertEquals("system:true creator:foo", atMiddle.text)
        assertEquals(11, atMiddle.cursor)
    }

    @Test
    fun classifiesAccessDeniedPageInsteadOfReturningNoResults() {
        val result = FilterHtmlParser.parse(
            "<html><head><title>Derpibooru - Oh noes!</title></head><body><h1>Oh noes!</h1><p>You have been blocked from accessing this site.</p></body></html>",
            "derpibooru.org"
        )
        assertTrue(result.accessDenied)
        assertTrue(result.filters.isEmpty())
    }
}
