package com.kerybotu.derpibooru.mirror.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.MenuItem
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.kerybotu.derpibooru.mirror.R

class ProfileActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        com.kerybotu.derpibooru.mirror.PaletteManager.apply(this)
        val toolbar = findViewById<Toolbar>(R.id.profile_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val cards = findViewById<GridLayout>(R.id.profile_cards)
        listOf("关注 Watched", "收藏 Faves", "点赞 Upvotes", "我的图库", "我的上传", "我的评论", "我的帖子", "链接").forEach { label ->
            val card = TextView(this).apply {
                text = "$label\n0"
                textSize = 15f
                setPadding(20, 28, 20, 28)
                setTextColor(getColor(android.R.color.darker_gray))
                isClickable = true
                setOnClickListener { Toast.makeText(this@ProfileActivity, "$label 暂未登录", Toast.LENGTH_SHORT).show() }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(6, 6, 6, 6)
            }
            cards.addView(card, params)
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.profile_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_profile_settings -> {
                AlertDialog.Builder(this)
                    .setTitle("设置")
                    .setItems(arrayOf("站点偏好", "账户信息", "登出")) { _, which ->
                        if (which == 2) confirmLogout() else Toast.makeText(this, "设置项即将开放", Toast.LENGTH_SHORT).show()
                    }.show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("确认登出")
            .setMessage("确定要登出吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("登出") { _: DialogInterface, _: Int ->
                window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                Toast.makeText(this, "已登出", Toast.LENGTH_SHORT).show()
            }.show()
    }
}
