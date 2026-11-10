package com.kerybotu.derpibooru.mirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.liquidglass.LiquidGlassButton
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import com.kerybotu.derpibooru.mirror.download.*
import com.kerybotu.derpibooru.mirror.favorites.*
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** Real UI rendering/navigation with labelled deterministic API fixtures, never real credentials. */
@RunWith(AndroidJUnit4::class)
class Ui2LegacyRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val pages = mutableListOf<Activity>()
    private val savedPrefs = mutableMapOf<SharedPreferences, Map<String, *>>()
    private val clientField = NetworkManager::class.java.getDeclaredField("okHttpClient").apply { isAccessible = true }
    private var savedClient: Any? = null
    private var savedTasks: List<DownloadTask> = emptyList()
    private lateinit var queue: DownloadQueueManager
    private val requests = CopyOnWriteArrayList<String>()
    private val evidencePrefix get() = InstrumentationRegistry.getArguments().getString("phase", "after")

    @Before
    fun fixtures() {
        for (name in listOf("app_settings_prefs", "api_credentials", "local_favorites", "download_queue")) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            savedPrefs[prefs] = HashMap(prefs.all)
        }
        AppSettings.setPalette(context, AppSettings.Palette.LIGHT)
        ApiKeyStore.save(context, "instrumentation-fixture-only")
        ApiKeyStore.saveUserId(context, 101L)
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(100, 180, 220))
            drawCircle(300f, 300f, 180f, Paint().apply { color = Color.rgb(230, 170, 80) })
        }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        savedClient = clientField.get(NetworkManager)
        clientField.set(NetworkManager, OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            requests += chain.request().url.toString()
            val payload = when {
                path.endsWith("/art.png") -> null
                path.contains("/profiles/") -> {
                    val id = path.substringAfterLast('/').toLong()
                    """{"user":{"id":$id,"name":"${if (id == 101L) "测试账户 101" else "测试上传者 202"}","created_at":"2020-01-01","description":"UI 2.0 用户主页回归测试资料","uploads_count":1,"comments_count":0,"posts_count":0,"topics_count":0,"awards":[]}}"""
                }
                path.contains("/search/images") -> """{"total":1,"images":[$imageJson]}"""
                path.endsWith("/images/1") -> """{"image":$imageJson}"""
                else -> """{"images":[],"comments":[],"filters":[],"total":0}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(if (payload == null) png.toResponseBody("image/png".toMediaType())
                    else payload.toResponseBody("application/json".toMediaType())).build()
        }.build())
        queue = DownloadQueueManager.get(context)
        savedTasks = queue.state.value.toList()
        setTasks(emptyList())
    }

    @After
    fun restore() {
        instrumentation.runOnMainSync { pages.asReversed().filterNot { it.isDestroyed }.forEach { it.finish() } }
        Thread.sleep(400)
        setTasks(savedTasks)
        savedPrefs.forEach { (prefs, values) ->
            val edit = prefs.edit().clear()
            values.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>)
            } }
            edit.commit()
        }
        clientField.set(NetworkManager, savedClient)
    }

    @Test
    fun favoritesAndDownloadTabsAlignWithTheirSelectionAndViewport() {
        val favorite = launch(FavoritesActivity::class.java)
        shot("favorites", favorite)
        val download = launch(DownloadManagerActivity::class.java)
        shot("downloads", download)
        // Capture both baseline screens even when the first alignment check fails.
        instrumentation.runOnMainSync {
            assertTabs(favorite.window.decorView)
            assertTabs(download.window.decorView)
            val feed = descendants(download.window.decorView).filterIsInstance<GlassFeedLayout>().single()
            val status = descendants(feed).filterIsInstance<TextView>().first { it.text == "暂无进行中的下载" }
            val bounds = Rect(); status.parent.let { it as View }.getGlobalVisibleRect(bounds)
            val top = feed.results.paddingTop
            val bottom = feed.height - feed.results.paddingBottom
            val origin = IntArray(2); feed.getLocationOnScreen(origin)
            assertTrue("Empty state must be centered below the fixed islands",
                abs(bounds.exactCenterY() - origin[1] - (top + bottom) / 2f) < dp(35))
        }
        download.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val landscape = resumed(DownloadManagerActivity::class.java)
        shot("downloads-landscape", landscape)
        instrumentation.runOnMainSync { assertTabs(landscape.window.decorView) }
        landscape.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        resumed(DownloadManagerActivity::class.java)
    }

    @Test
    fun textSegmentsStayCenteredAtNarrowWidthsAndLargeFonts() {
        instrumentation.runOnMainSync {
            for (fontScale in listOf(1f, 1.3f)) for (widthDp in listOf(280, 360, 640)) {
                val config = android.content.res.Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
                val themedContext = androidx.appcompat.view.ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_DerpiViewer)
                val bar = HomeGlassTabBar(themedContext)
                bar.configureTabs(listOf("本地收藏夹", "Derpibooru收藏").map { com.example.liquidglass.LiquidGlassTabBar.TabItem(it) })
                bar.measure(View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(64), View.MeasureSpec.EXACTLY))
                bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
                assertTabs(bar)
            }
        }
    }

    @Test
    fun folderActionsServerFavoritesAndDownloadRowsKeepTheirLayout() {
        val store = LocalFavoritesStore(context)
        repeat(8) { store.createFolder("布局测试收藏夹 $it") }
        val favorite = launch(FavoritesActivity::class.java)
        val feed = descendants(favorite.window.decorView).filterIsInstance<GlassFeedLayout>().single()
        instrumentation.runOnMainSync {
            descendants(feed).filterIsInstance<LiquidGlassButton>().first { it.text.toString().contains("新建文件夹") }.performClick()
        }
        onView(isAssignableFrom(EditText::class.java)).perform(replaceText("回归测试新建文件夹"))
        onView(withText("完成")).perform(click())
        assertTrue(LocalFavoritesStore(context).allFolders().any { it.name == "回归测试新建文件夹" })
        shot("favorites-many-folders", favorite)
        instrumentation.runOnMainSync {
            descendants(feed).filterIsInstance<LiquidGlassButton>().first { it.text.toString().contains("导入 Derpibooru") }.performClick()
        }
        await { LocalFavoritesStore(context).isFavoriteInDefaultFolder(1) }
        instrumentation.runOnMainSync { descendants(feed).filterIsInstance<HomeGlassTabBar>().single().selectedIndex = 1 }
        await { descendants(feed).filterIsInstance<TextView>().any { it.text.toString().contains("图片 #1") } }
        shot("favorites-server", favorite)
        instrumentation.runOnMainSync {
            descendants(feed).filterIsInstance<HomeGlassTabBar>().single().selectedIndex = 0
            feed.results.scrollToPosition(7)
        }
        settle()
        instrumentation.runOnMainSync { feed.results.scrollToPosition(0) }
        val download = launch(DownloadManagerActivity::class.java)
        setTasks(listOf(task(7001, DownloadStatus.QUEUED), task(7002, DownloadStatus.COMPLETED), task(7003, DownloadStatus.FAILED)))
        settle()
        val downloadFeed = descendants(download.window.decorView).filterIsInstance<GlassFeedLayout>().single()
        shot("downloads-active", download)
        instrumentation.runOnMainSync {
            descendants(downloadFeed).filterIsInstance<LiquidGlassButton>().first { it.text.toString() == "取消" }.performClick()
        }
        assertFalse(queue.state.value.any { it.taskId == 7001L })
        for ((index, label) in listOf(1 to "completed", 2 to "failed")) {
            instrumentation.runOnMainSync { descendants(downloadFeed).filterIsInstance<HomeGlassTabBar>().single().selectedIndex = index }
            settle()
            shot("downloads-$label", download)
            instrumentation.runOnMainSync { assertTabs(downloadFeed) }
        }
        instrumentation.runOnMainSync { download.finish(); favorite.finish() }
        val reopened = launch(FavoritesActivity::class.java)
        assertTrue(LocalFavoritesStore(context).allFolders().any { it.name == "回归测试新建文件夹" })
        shot("favorites-reopened", reopened)
    }

    @Test
    fun detailUploaderAndMyTabUseOneProfileAndReturnToTheSameDetail() {
        val main = launch(MainActivity::class.java)
        await { descendants(main.window.decorView).filterIsInstance<HomeGlassTabBar>().any { it.id == R.id.glass_bottom_navigation } }
        instrumentation.runOnMainSync { main.findViewById<HomeGlassTabBar>(R.id.glass_bottom_navigation).selectedIndex = 4 }
        await { descendants(main.window.decorView).filterIsInstance<TextView>().any { it.text == "测试账户 101" } }
        shot("profile-my-ui2-fixture", main)
        val my = descendants(main.window.decorView).filterIsInstance<EmbeddedProfileView>().single()
        instrumentation.runOnMainSync { assertProfileHeaderBelowIsland(my) }
        assertTrue(descendants(my).filterIsInstance<Button>().any { it.text == "收藏" })
        val detail = launch(ImageDetailActivity::class.java, Intent().putExtra("image_id", 1))
        await { detail.findViewById<TextView>(R.id.detail_uploader).text.toString().contains("测试上传者") }
        shot("detail-before-profile-fixture", detail)
        instrumentation.runOnMainSync { detail.findViewById<View>(R.id.detail_uploader).performClick() }
        val profile = resumed(ProfileActivity::class.java)
        await { descendants(profile.window.decorView).filterIsInstance<TextView>().any { it.text == "测试上传者 202" } }
        shot("profile-uploader-ui2-fixture", profile)
        val shared = descendants(profile.window.decorView).filterIsInstance<EmbeddedProfileView>()
        assertEquals("Detail must host the exact same UI 2.0 implementation as My", 1, shared.size)
        instrumentation.runOnMainSync { assertProfileHeaderBelowIsland(shared.single()) }
        assertEquals(202L, profile.intent.getLongExtra(ProfileActivity.EXTRA_USER_ID, -1))
        assertFalse("Another user's profile must not expose my private queries",
            descendants(shared.single()).filterIsInstance<Button>().any { it.text == "收藏" || it.text == "关注" })
        instrumentation.runOnMainSync {
            descendants(shared.single()).filterIsInstance<Button>().first { it.text == "评论" }.performClick()
        }
        await { requests.any { it.contains("search/comments") && it.contains("202") } }
        instrumentation.runOnMainSync {
            descendants(shared.single()).filterIsInstance<ImageButton>().first { it.contentDescription == "返回" }.performClick()
        }
        assertSame("Back must reveal the retained detail Activity", detail, resumed(ImageDetailActivity::class.java))
        assertEquals("Uploader identity remains tied to the image", "测试上传者 202", detail.findViewById<TextView>(R.id.detail_uploader).text.toString())
        shot("detail-returned-fixture", detail)
        assertTrue(requests.any { it.contains("profiles/101") })
        assertTrue(requests.any { it.contains("profiles/202") })
    }

    private fun assertProfileHeaderBelowIsland(root: EmbeddedProfileView) {
        val island = descendants(root).filterIsInstance<com.kerybotu.derpibooru.mirror.ui.IslandGlassView>().single()
        val avatar = descendants(root).filterIsInstance<android.widget.ImageView>().single { it.contentDescription == "头像" }
        val islandBounds = Rect(); val avatarBounds = Rect()
        assertTrue(island.getGlobalVisibleRect(islandBounds))
        assertTrue(avatar.getGlobalVisibleRect(avatarBounds))
        assertTrue("Profile identity must start below the floating island", avatarBounds.top >= islandBounds.bottom)
    }

    private fun assertTabs(root: View) {
        for (bar in descendants(root).filterIsInstance<HomeGlassTabBar>()) {
            val row = bar.getChildAt(0) as ViewGroup
            for (i in 0 until row.childCount) {
                val cell = row.getChildAt(i) as ViewGroup
                if (descendants(cell).any { it is android.widget.ImageView }) continue
                val text = descendants(cell).filterIsInstance<TextView>().single()
                assertEquals("Segment label must center actual glyphs", Gravity.CENTER, text.gravity)
                assertTrue("Segment text and touch cell must share a horizontal center",
                    abs(text.left + text.width / 2f - cell.width / 2f) <= 2)
                assertTrue("Segment text and touch cell must share a vertical center",
                    abs(text.top + text.height / 2f - cell.height / 2f) <= 2)
                assertTrue("Segment cells must be vertically centered in the island",
                    abs(row.top + cell.top + cell.height / 2f - bar.height / 2f) <= 2)
            }
        }
    }

    private fun launch(type: Class<out Activity>, extras: Intent = Intent()): Activity {
        instrumentation.runOnMainSync { context.startActivity(Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtras(extras)) }
        return resumed(type).also { if (it !in pages) pages += it }
    }
    private fun resumed(type: Class<out Activity>): Activity {
        var page: Activity? = null
        await { page = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { type.isInstance(it) }; page != null }
        settle()
        return requireNotNull(page).also { if (it !in pages) pages += it }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(100)
        }
        error("UI state was not ready within 15 seconds")
    }
    private fun settle() { Thread.sleep(800); instrumentation.runOnMainSync {} }
    private fun shot(name: String, page: Activity) {
        settle()
        instrumentation.runOnMainSync { assertTrue(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).contains(page)) }
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir(null), "ui2-legacy").apply { mkdirs() }.resolve("$evidencePrefix-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (name.startsWith("profile-")) {
                val bounds = Rect()
                instrumentation.runOnMainSync {
                    descendants(page.window.decorView).filterIsInstance<android.widget.ImageView>()
                        .single { it.contentDescription == "头像" }.getGlobalVisibleRect(bounds)
                }
                val pixel = image.getPixel(bounds.centerX(), bounds.centerY())
                val surface = PaletteManager.colors(context).surface
                assertTrue("Profile avatar must actually be painted, not covered by a source layer",
                    abs(Color.red(pixel) - Color.red(surface)) + abs(Color.green(pixel) - Color.green(surface)) +
                        abs(Color.blue(pixel) - Color.blue(surface)) > 30)
            }
        }
        finally { image.recycle() }
    }
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    @Suppress("UNCHECKED_CAST") private fun setTasks(tasks: List<DownloadTask>) { (queue.state as MutableStateFlow<List<DownloadTask>>).value = tasks }
    private fun task(id: Long, status: DownloadStatus) = DownloadTask(id, 1, "https://fixture.invalid/art.png", "示例下载文件-$id.png", null, status, errorMessage = "测试下载失败状态")
    private val imageJson = """{"id":1,"uploader_id":202,"uploader":"测试上传者 202","created_at":"2020-01-01","description":"布局回归图片","mime_type":"image/png","width":600,"height":600,"tags":["safe"],"representations":{"small":"https://fixture.invalid/art.png","full":"https://fixture.invalid/art.png"}}"""
}
