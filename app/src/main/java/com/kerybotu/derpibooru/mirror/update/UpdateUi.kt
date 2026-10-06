package com.kerybotu.derpibooru.mirror.update

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.kerybotu.derpibooru.mirror.PaletteManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object UpdateUi {
    fun show(activity: Activity, info: AppUpdateInfo, scope: CoroutineScope) {
        val colors = PaletteManager.colors(activity)
        val message = TextView(activity).apply {
            text = info.message.joinToString("\n")
            setTextColor(colors.onSurface)
            setPadding(24, 8, 24, 8)
        }
        val status = TextView(activity).apply {
            text = "您可以在设置里随时手动更新"
            setTextColor(colors.muted)
            setPadding(24, 8, 24, 8)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.surface)
            addView(message)
            addView(status)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("发现新版本 ${info.version}").setView(box)
            .setNegativeButton("跳过此版本") { _, _ -> AppUpdateManager.skip(activity, info.version) }
            .setNeutralButton("稍后提醒") { _, _ -> AppUpdateManager.remindNextLaunch(activity) }
            .setPositiveButton("更新", null).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(colors.surface))
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(colors.onSurface)
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(colors.onSurface)
            listOf(
                AlertDialog.BUTTON_POSITIVE,
                AlertDialog.BUTTON_NEUTRAL,
                AlertDialog.BUTTON_NEGATIVE
            ).forEach { button -> dialog.getButton(button)?.setTextColor(colors.primary) }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                status.text = "准备下载…"
                scope.launch {
                    runCatching {
                        val file = AppUpdateManager.download(activity, info) { text -> activity.runOnUiThread { status.text = text } }
                        withContext(Dispatchers.Main) {
                            if (!AppUpdateManager.canInstallPackages(activity)) {
                                status.text = "安装需要允许此应用安装未知来源 APK。此权限仅用于安装本次更新，不会读取其他文件。"
                                AppUpdateManager.openInstallPermissionSettings(activity)
                                // The settings screen is external to the app. Poll briefly
                                // so returning after granting permission continues directly
                                // to the package installer.
                                scope.launch {
                                    repeat(300) {
                                        kotlinx.coroutines.delay(1_000)
                                        if (AppUpdateManager.canInstallPackages(activity)) {
                                            status.text = "权限已允许，正在打开系统安装器…"
                                            AppUpdateManager.install(activity, file)
                                            dialog.dismiss()
                                            return@launch
                                        }
                                    }
                                }
                            } else {
                                status.text = "下载完成，正在打开系统安装器…"
                                AppUpdateManager.install(activity, file)
                                dialog.dismiss()
                            }
                        }
                    }.onFailure { e -> activity.runOnUiThread { status.text = "更新失败：${e.message ?: "网络错误"}"; dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true } }
                }
            }
        }
        dialog.show()
    }
}
