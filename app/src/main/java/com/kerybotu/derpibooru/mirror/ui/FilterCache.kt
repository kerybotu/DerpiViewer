package com.kerybotu.derpibooru.mirror.ui

import android.content.Context

object FilterCache {
    private const val PREFS = "filter_cache"
    private const val KEY_SYSTEM_FILTERS = "system_filters"
    fun getSystemFilters(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SYSTEM_FILTERS, null)
    fun saveSystemFilters(context: Context, json: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SYSTEM_FILTERS, json).apply()
}
