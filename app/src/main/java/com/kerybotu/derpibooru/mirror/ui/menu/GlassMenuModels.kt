package com.kerybotu.derpibooru.mirror.ui.menu

data class GlassMenuItem(
    val id: String,
    val iconRes: Int,
    val title: String,
    val subtitle: String? = null,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val onClick: () -> Unit
)
