package com.kerybotu.derpibooru.mirror

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilterSearchUrlInstrumentedTest {
    @Test
    fun fqIsEncodedOnceBySiteUrlBuilder() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(
            "https://derpibooru.org/filters?fq=creator%3A495pygly",
            NetworkManager.sitePageUrl(context, "/filters", mapOf("fq" to "creator:495pygly"))
        )
    }
}
