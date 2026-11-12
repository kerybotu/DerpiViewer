package com.kerybotu.derpibooru.mirror.ui

import org.junit.Assert.*
import org.junit.Test

class RichTextParserTest {
    @Test fun allRequestedStyles() {
        val result = RichTextParser.parse("**bold** *italic* ||hidden|| `code` __underline__ ~~strike~~ ^sup^ ~sub~")
        assertEquals("bold italic hidden code underline strike sup sub", result.text)
        assertEquals(setOf("bold", "italic", "spoiler", "code", "underline", "strike", "sup", "sub"), result.marks.map { it.kind }.toSet())
    }
    @Test fun nestingAndCodeAreIndependent() {
        val result = RichTextParser.parse("**bold and __underlined__** `**literal**`")
        assertEquals("bold and underlined **literal**", result.text)
        assertEquals(3, result.marks.size)
        assertEquals("bold and underlined", result.text.substring(result.marks.first { it.kind == "bold" }.start, result.marks.first { it.kind == "bold" }.end))
    }
    @Test fun malformedInputKeepsTextAndSpaces() {
        assertEquals("unclosed **test\n  spaces", RichTextParser.parse("unclosed **test\n  spaces").text)
        assertEquals("*  ** [bad](javascript:alert)", RichTextParser.parse("*  ** [bad](javascript:alert)").text)
    }
    @Test fun linksAndTagReferences() {
        val result = RichTextParser.parse("https://example.org/x [image](/images/42) >>123 [[artist:test]]")
        assertEquals(listOf("https://example.org/x", "/images/42", "/images/123", "artist:test"), result.marks.map { it.target })
    }
    @Test fun htmlIsDataAndDangerousSchemesAreRejected() {
        val result = RichTextParser.parse("<p><strong>A &amp; B</strong><script>bad()</script><a href='javascript:bad()'>bad link</a><br>next</p>")
        assertEquals("A & Bbad link\nnext\n", result.text)
        assertEquals(listOf("bold"), result.marks.map { it.kind })
        assertNull(RichTextParser.safeUrl("//evil.example"))
        assertNull(RichTextParser.safeUrl("javascript:alert(1)"))
    }
}
