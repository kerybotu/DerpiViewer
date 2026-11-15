package com.kerybotu.derpibooru.mirror.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.auth.*
import com.example.liquidglass.LiquidGlassView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Passwords and decrypted payloads deliberately do not participate in saved instance state. */
class CredentialBackupActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var manager: CredentialBackupManager
    private var encrypted: ByteArray? = null
    private var document: ByteArray? = null
    private var busy = false
    private var fields = mutableListOf<EditText>()
    private val createDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val bytes = encrypted.also { encrypted = null }
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null && bytes != null) work {
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes); it.flush() }
                }
                home("加密备份已保存。请妥善保管文件和密码。")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // A provider may have left a truncated encrypted file; it contains no plaintext.
                home("保存失败；文件提供程序可能留下不完整的加密文件，请删除后重试。")
            } finally { bytes.fill(0) }
        } else { bytes?.fill(0); home("已取消保存；没有修改登录凭据。") }
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) work {
            val bytes = withContext(Dispatchers.IO) {
                checkNotNull(contentResolver.openInputStream(uri)).use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= CredentialBackupCodec.MAX_FILE_BYTES)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray().also { require(it.isNotEmpty()) }
                }
            }
            document?.fill(0); document = bytes
            importPassword()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        manager = CredentialBackupManager(applicationContext)
        home()
    }

    private fun layout(title: String) {
        fields.forEach { it.text?.clear() }; fields.clear()
        val colors = PaletteManager.colors(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
            setBackgroundColor(colors.surface)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        button("‹  返回") { if (title == "登录凭据备份与恢复") finish() else home() }
        label(title, 26f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(8), 0, dp(4)) }
        status = label("")
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun label(value: String, size: Float = 15f): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(PaletteManager.colors(this@CredentialBackupActivity).glassText)
        setPadding(0, dp(10), 0, dp(10)); content.addView(this)
    }
    private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value; isAllCaps = false; minHeight = dp(48)
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
        PaletteManager.styleButton(this)
        setOnClickListener { if (!busy) action() }
    }
    private fun option(value: String, selected: Boolean = true): CheckBox = CheckBox(this).apply {
        text = value; isChecked = selected; minHeight = dp(48)
        setTextColor(PaletteManager.colors(this@CredentialBackupActivity).glassText)
        content.addView(this)
    }
    private fun glassSection(title: String): LinearLayout {
        val glass = LiquidGlassView(this).apply {
            GlassWidgetStyle.applyMaterial(this, PaletteManager.colors(this@CredentialBackupActivity))
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        glass.addView(inner, FrameLayout.LayoutParams(-1, -2))
        content.addView(glass, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) })
        inner.addView(TextView(this).apply {
            text = title; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(PaletteManager.colors(this@CredentialBackupActivity).glassText)
            setPadding(0, dp(4), 0, dp(6))
        })
        return inner
    }
    private fun password(hintText: String): EditText = EditText(this).apply {
        hint = hintText; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        isSingleLine = true; isSaveEnabled = false; setFreezesText(false)
        transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        filters = arrayOf(android.text.InputFilter.LengthFilter(1024))
        imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setTextColor(PaletteManager.colors(this@CredentialBackupActivity).glassText)
        content.addView(this, LinearLayout.LayoutParams(-1, -2)); fields.add(this)
    }
    private fun showPasswords(vararg inputs: EditText) {
        option("显示密码", false).setOnCheckedChangeListener { _, checked ->
            inputs.forEach {
                it.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                it.setSelection(it.length())
            }
        }
    }
    private fun home(message: String = "") {
        document?.fill(0); document = null
        layout("登录凭据备份与恢复")
        label("当前站点：${manager.currentOrigin()}\n凭据只在你明确确认后读写。备份文件使用密码加密，可在其他设备恢复；密码无法找回。")
        val actions = glassSection("安全备份")
        val previous = content
        content = actions
        button("导出登录凭据") { exportForm() }
        button("导入登录凭据") {
            openDocument.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            })
        }
        content = previous
        status.text = message
    }

    private fun exportForm() {
        layout("导出登录凭据")
        label("选择要备份的当前站点凭据。加密文件不包含明文密码或凭据。")
        val selection = glassSection("备份内容")
        val previous = content; content = selection
        val cookies = option("Cookie")
        label("网站会话和安全属性")
        val key = option("API Key")
        label("当前站点的接口密钥")
        content = previous
        val security = glassSection("设置备份密码")
        content = security
        label("至少 8 个字符，含英文字母和数字。建议使用 14 位以上、包含大小写和符号且不重复的密码。")
        val first = password("备份密码"); val second = password("确认备份密码")
        showPasswords(first, second)
        val strength = label("输入备份密码").apply { setPadding(0, dp(8), 0, dp(4)) }
        content = previous
        val submit = button("加密并选择保存位置") {
            val selectedCookies = cookies.isChecked; val selectedKey = key.isChecked
            val pass = CharArray(first.length()) { first.text[it] }
            first.text.clear(); second.text.clear()
            work {
                try {
                    val payload = manager.capture(selectedCookies, selectedKey)
                    encrypted?.fill(0)
                    encrypted = withContext(Dispatchers.Default) { CredentialBackupCodec.encrypt(payload, pass) }
                    createDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "DerpiViewer_Backup_${SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())}.json")
                    })
                } finally { pass.fill('\u0000') }
            }
        }
        fun update() {
            val assessment = BackupPasswordPolicy.assess(first.text)
            strength.text = assessment.message
            val palette = PaletteManager.colors(this@CredentialBackupActivity)
            strength.setTextColor(when (assessment.score) {
                0 -> palette.muted
                1 -> palette.glassSecondaryText
                2 -> palette.primary
                else -> palette.glassActionText
            })
            submit.isEnabled = !busy && (cookies.isChecked || key.isChecked) &&
                BackupPasswordPolicy.valid(first.text) && first.text.toString() == second.text.toString()
        }
        first.doAfterTextChanged { update() }; second.doAfterTextChanged { update() }
        cookies.setOnCheckedChangeListener { _, _ -> update() }; key.setOnCheckedChangeListener { _, _ -> update() }
        update()
    }

    private fun importPassword() {
        layout("解密登录凭据")
        label("已读取加密文件。输入备份密码后查看内容类型，确认前不会写入任何凭据。")
        val input = password("解密密码")
        showPasswords(input)
        val submit = button("解密并验证") {
            val bytes = document ?: return@button
            val pass = CharArray(input.length()) { input.text[it] }; input.text.clear()
            work {
                try {
                    val payload = withContext(Dispatchers.Default) { CredentialBackupCodec.decrypt(bytes, pass) }
                    check(payload.origin == manager.currentOrigin())
                    confirm(payload)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    status.text = "无法恢复：密码错误、文件损坏、格式不受支持、凭据已过期或源站不匹配。现有凭据未修改。"
                } finally { pass.fill('\u0000') }
            }
        }
        input.doAfterTextChanged { submit.isEnabled = input.length() in 1..1024 && !busy }
        submit.isEnabled = false
    }

    private fun confirm(payload: EncryptedExportPayload) {
        layout("确认恢复登录凭据")
        label("目标站点：${payload.origin}\n文件时间：${Date(payload.exportedAt)}\n仅覆盖勾选的凭据；其他凭据保持不变。Cookie 恢复会替换当前站点会话并授权在本机加密保存。恢复的凭据仍可能被服务器判定失效。")
        val cookies = if (payload.includesCookies) option("恢复 Cookie（${payload.cookies!!.size} 项）") else null
        val key = if (payload.includesApiKey) option("恢复 API Key（内容已隐藏）") else null
        val submit = button("确认写入所选凭据") {
            val selected = EncryptedExportPayload(exportedAt = payload.exportedAt, origin = payload.origin,
                includesCookies = cookies?.isChecked == true, includesApiKey = key?.isChecked == true,
                cookies = payload.cookies.takeIf { cookies?.isChecked == true }, apiKey = payload.apiKey.takeIf { key?.isChecked == true })
            work {
                val success = manager.restore(selected)
                home(if (success) "凭据恢复成功。请在站点确认登录状态。" else "恢复失败，已恢复原有凭据。")
            }
        }
        fun update() { submit.isEnabled = !busy && (cookies?.isChecked == true || key?.isChecked == true) }
        cookies?.setOnCheckedChangeListener { _, _ -> update() }; key?.setOnCheckedChangeListener { _, _ -> update() }
    }

    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; status.text = "正在安全处理，请稍候…"
        lifecycleScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                status.text = if (error.message == "backup_rollback_failed") "存储故障且未能完整恢复，请重新登录。" else
                    "操作失败：请检查所选凭据是否存在、WebView 是否支持完整 Cookie 属性，以及文件和站点是否有效。"
            } finally { busy = false }
        }
    }
    override fun onDestroy() {
        fields.forEach { it.text?.clear() }; fields.clear()
        encrypted?.fill(0); encrypted = null; document?.fill(0); document = null
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
