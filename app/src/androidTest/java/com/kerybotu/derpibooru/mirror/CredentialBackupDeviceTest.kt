package com.kerybotu.derpibooru.mirror

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kerybotu.derpibooru.mirror.auth.*
import com.kerybotu.derpibooru.mirror.network.SharedCookieJar
import com.kerybotu.derpibooru.mirror.ui.CredentialBackupActivity
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic credentials only. Never logs or snapshots a real account. */
@RunWith(AndroidJUnit4::class)
class CredentialBackupDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val origin = "https://backup-test.example/"
    private val password = "River9!Mountains#Cloud2026".toCharArray()
    private fun main(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block() else inst.runOnMainSync(block)
    }
    private fun cookie(value: String, path: String = "/") = BackupCookie("backup_test_session", value,
        "backup-test.example", path, true, true, true, System.currentTimeMillis() + 3600_000)
    private fun payload(cookies: List<BackupCookie>? = null, key: String? = null) = EncryptedExportPayload(
        exportedAt = System.currentTimeMillis(), origin = origin, includesCookies = cookies != null,
        includesApiKey = key != null, cookies = cookies, apiKey = key)
    private fun waitFor(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; Thread.sleep(100) }
        fail(label)
    }
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun text(activity: Activity, value: String): View {
        var result: View? = null
        main { result = all(activity.window.decorView).firstOrNull { it is TextView && it.text.toString() == value } }
        return requireNotNull(result) { "Missing expected UI control" }
    }
    private fun tap(view: View) {
        val location = IntArray(2); var x = 0f; var y = 0f
        main {
            view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width, view.height), true)
            view.getLocationOnScreen(location); x = location[0] + view.width / 2f; y = location[1] + view.height / 2f
        }
        Thread.sleep(200)
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0).also {
                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                inst.uiAutomation.injectInputEvent(it, true); it.recycle()
            }
        }
        inst.waitForIdleSync()
    }
    private fun shot(activity: Activity, name: String) {
        // Only the synthetic fixture UI is captured; production FLAG_SECURE remains enabled.
        main { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        Thread.sleep(300)
        val file = File(context.getExternalFilesDir(null), "credential-backup-test/$name.png")
        file.parentFile!!.mkdirs()
        inst.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        main { activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList()
        else listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun systemClick(vararg choices: String) {
        val filePrefix = choices.firstOrNull()?.takeIf { it.startsWith("file:") }?.removePrefix("file:")
        var node: AccessibilityNodeInfo? = null
        waitFor("System document control") {
            node = nodes(inst.uiAutomation.rootInActiveWindow).firstOrNull { entry ->
                entry.isVisibleToUser && entry.isEnabled && (filePrefix?.let { entry.text?.toString()?.contains(it, true) == true } == true || choices.any { (if (it.startsWith("~")) entry.text?.toString()?.startsWith(it.drop(1)) == true else entry.text?.toString().equals(it, true)) || entry.contentDescription?.toString() == it || entry.viewIdResourceName?.endsWith(it) == true })
            }; node != null
        }
        Thread.sleep(800)
        node = nodes(inst.uiAutomation.rootInActiveWindow).first { entry ->
            entry.isVisibleToUser && entry.isEnabled && (filePrefix?.let { entry.text?.toString()?.contains(it, true) == true } == true || choices.any { (if (it.startsWith("~")) entry.text?.toString()?.startsWith(it.drop(1)) == true else entry.text?.toString().equals(it, true)) || entry.contentDescription?.toString() == it || entry.viewIdResourceName?.endsWith(it) == true })
        }
        val rect = android.graphics.Rect(); node!!.getBoundsInScreen(rect)
        android.util.Log.i("BackupTest", "system_target=${node!!.viewIdResourceName} rect=$rect")
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0).also {
                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; inst.uiAutomation.injectInputEvent(it, true); it.recycle()
            }
        }
        Thread.sleep(500)
    }

    @Test fun selectiveRestoreScopeAndNativeCookieAttributes() = runBlocking {
        val site = AppSettings.getSelectedSite(context)
        val custom = AppSettings.getCustomDomain(context)
        val keyState = ApiKeyStore.backupCheckpoint(context)
        val cookieState = SiteCookieStore.backupCheckpoint(context, origin)
        try {
            AppSettings.setCustomSite(context, "backup-test.example")
            val manager = CredentialBackupManager(context)
            assertTrue(manager.restore(payload(listOf(cookie("synthetic-one")), "synthetic-key-one")))
            assertTrue(manager.restore(payload(listOf(cookie("synthetic-two", "/api/")))))
            assertEquals("synthetic-key-one", ApiKeyStore.get(context))
            main { assertFalse(CookieManager.getInstance().getCookie(origin).orEmpty().contains("backup_test_session=")) }
            val jar = SharedCookieJar(context)
            assertFalse(jar.loadForRequest(origin.toHttpUrl()).any { it.name == "backup_test_session" })
            val scoped = jar.loadForRequest((origin + "api/test").toHttpUrl()).single { it.name == "backup_test_session" }
            assertTrue(scoped.secure && scoped.httpOnly && scoped.hostOnly && scoped.persistent)
            assertEquals("/api/", scoped.path)
            assertTrue(manager.restore(payload(key = "synthetic-key-two")))
            assertEquals("synthetic-two", jar.loadForRequest((origin + "api/test").toHttpUrl()).single { it.name == "backup_test_session" }.value)
            val wrongSite = EncryptedExportPayload(exportedAt = System.currentTimeMillis(), origin = "https://other.example/",
                includesCookies = false, includesApiKey = true, cookies = null, apiKey = "must-not-be-imported")
            try { manager.restore(wrongSite); fail("Cross-site must fail") } catch (_: IllegalStateException) { }
            assertEquals("synthetic-key-two", ApiKeyStore.get(context))
            // Fail the second store after Cookie writes to exercise real compensation.
            val failing = CredentialBackupManager(context) { _, _ -> false }
            assertFalse(failing.restore(payload(listOf(cookie("must-roll-back")), "must-not-be-saved")))
            assertEquals("synthetic-key-two", ApiKeyStore.get(context))
            assertEquals("synthetic-two", jar.loadForRequest((origin + "api/test").toHttpUrl()).single { it.name == "backup_test_session" }.value)
            val bytes = CredentialBackupCodec.encrypt(payload(listOf(cookie("synthetic-portable")), "synthetic-portable-key"), password)
            File(context.getExternalFilesDir(null), "credential-portable.json").writeBytes(bytes)
            try { CredentialBackupCodec.decrypt(bytes, "WrongPassword9".toCharArray()); fail() } catch (_: Exception) { }
            assertEquals("synthetic-key-two", ApiKeyStore.get(context))
        } finally {
            main { SiteCookieStore.clearWebViewOrigin(CookieManager.getInstance(), origin) }
            // Delete the path-specific fixture as well.
            val latch = CountDownLatch(1)
            main { CookieManager.getInstance().setCookie(origin + "api/", "backup_test_session=; Path=/api/; Max-Age=0; Secure") { latch.countDown() } }
            latch.await(5, TimeUnit.SECONDS)
            SiteCookieStore.restoreBackupCheckpoint(context, cookieState)
            ApiKeyStore.restoreBackupCheckpoint(context, keyState)
            if (custom != null) AppSettings.setCustomSite(context, custom)
            AppSettings.setSelectedSite(context, site)
        }
    }

    @Test fun independentlyEncryptedFixtureIsPortable() {
        val bytes = File(context.getExternalFilesDir(null), "credential-python.json").readBytes()
        val payload = CredentialBackupCodec.decrypt(bytes, password)
        assertEquals(origin, payload.origin)
        assertEquals("synthetic-portable-key", payload.apiKey)
        assertEquals("synthetic-portable", payload.cookies!!.single().value)
    }

    @Test fun exportAndImportThroughSystemDocumentsWithResponsivePasswordUi() {
        val site = AppSettings.getSelectedSite(context); val custom = AppSettings.getCustomDomain(context)
        val keyState = ApiKeyStore.backupCheckpoint(context)
        var page: Activity? = null
        try {
            AppSettings.setCustomSite(context, "backup-test.example")
            ApiKeyStore.replaceForBackup(context, "synthetic-ui-key")
            page = inst.startActivitySync(Intent(context, CredentialBackupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val activity = page!!
            shot(activity, "backup-home")
            tap(text(activity, "导出登录凭据"))
            tap(text(activity, "Cookie")) // API Key only, no real WebView data.
            var edits = emptyList<EditText>()
            main { edits = all(activity.window.decorView).filterIsInstance<EditText>() }
            main { assertFalse(text(activity, "加密并选择保存位置").isEnabled) }
            main { edits[0].setText("password123"); edits[1].setText("mismatch") }
            main { assertFalse(text(activity, "加密并选择保存位置").isEnabled) }
            main { edits[0].setText(String(password)); edits[1].setText(String(password)) }
            main { assertTrue(text(activity, "加密并选择保存位置").isEnabled) }
            shot(activity, "backup-export-strong")
            tap(text(activity, "加密并选择保存位置"))
            val fileName = "~DerpiViewer_Backup_"
            systemClick("保存", "Save", "SAVE", "action_menu_save", "android:id/button1")
            waitFor("Export completion") { var ready = false; main { ready = all(activity.window.decorView).filterIsInstance<TextView>().any { it.text.startsWith("加密备份已保存") } }; ready }
            tap(text(activity, "导入登录凭据"))
            // List mode prevents truncated grid labels and makes the exact filename visible.
            val listToggle = nodes(inst.uiAutomation.rootInActiveWindow).firstOrNull { it.contentDescription?.toString()?.contains("list", true) == true }
            if (listToggle != null) systemClick(listToggle.contentDescription.toString())
            systemClick("file:DerpiViewer_Backup_")
            waitFor("Selected SAF file") {
                nodes(inst.uiAutomation.rootInActiveWindow).any { it.text?.toString()?.contains("DerpiViewer_Backup_", true) == true }
            }
            waitFor("Password form") { var ready = false; main { ready = all(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "解密登录凭据" } }; ready }
            main { all(activity.window.decorView).filterIsInstance<EditText>().single().setText(String(password)) }
            tap(text(activity, "解密并验证"))
            waitFor("Import preview") { var ready = false; main { ready = all(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "确认恢复登录凭据" } }; ready }
            shot(activity, "backup-import-confirm")
            tap(text(activity, "确认写入所选凭据"))
            waitFor("Import completion") { var ready = false; main { ready = all(activity.window.decorView).filterIsInstance<TextView>().any { it.text.startsWith("凭据恢复成功") } }; ready }
            assertEquals("synthetic-ui-key", ApiKeyStore.get(context))
        } finally {
            main { page?.finish() }
            ApiKeyStore.restoreBackupCheckpoint(context, keyState)
            if (custom != null) AppSettings.setCustomSite(context, custom)
            AppSettings.setSelectedSite(context, site)
        }
    }
}
