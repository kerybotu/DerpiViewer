package com.kerybotu.derpibooru.mirror.ui

enum class BackdropSourceMode {
    AUTO_PARENT,
    EXPLICIT_SOURCE,
    DISABLED
}

internal fun <T : Any> selectBackdropSource(
    mode: BackdropSourceMode,
    explicitSource: T?,
    parentSource: T?
): T? = when (mode) {
    BackdropSourceMode.AUTO_PARENT -> parentSource
    BackdropSourceMode.EXPLICIT_SOURCE -> explicitSource
    BackdropSourceMode.DISABLED -> null
}
