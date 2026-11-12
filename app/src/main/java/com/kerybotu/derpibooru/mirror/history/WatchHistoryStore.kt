package com.kerybotu.derpibooru.mirror.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.kerybotu.derpibooru.mirror.model.Image

class WatchHistoryStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
    companion object {
        private const val DB_NAME = "derpiviewer_history.db"
        private const val DB_VERSION = 1
        const val MAX_ENTRIES = 500
        private const val TABLE = "watch_history"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE $TABLE (
            image_id INTEGER PRIMARY KEY, detail_url TEXT NOT NULL, thumbnail_url TEXT,
            title TEXT NOT NULL, tags TEXT NOT NULL, media_type TEXT NOT NULL,
            first_watched INTEGER NOT NULL, last_watched INTEGER NOT NULL,
            watch_count INTEGER NOT NULL, full_url TEXT, width INTEGER NOT NULL,
            height INTEGER NOT NULL, mime_type TEXT)""")
        db.execSQL("CREATE INDEX history_last_watched ON $TABLE(last_watched DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
    }

    fun record(image: Image, now: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.query(TABLE, arrayOf("first_watched", "watch_count"), "image_id=?", arrayOf(image.id.toString()), null, null, null)
            val values = ContentValues().apply {
                put("image_id", image.id)
                put("detail_url", "images/${image.id}")
                put("thumbnail_url", image.thumbnailUrl)
                put("title", image.title)
                put("tags", image.tags.joinToString(" "))
                put("media_type", mediaType(image.mimeType, image.fullUrl))
                put("first_watched", if (existing.moveToFirst()) existing.getLong(0) else now)
                put("last_watched", now)
                put("watch_count", if (existing.moveToFirst()) existing.getInt(1) + 1 else 1)
                put("full_url", image.fullUrl)
                put("width", image.width)
                put("height", image.height)
                put("mime_type", image.mimeType)
            }
            existing.close()
            db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL("DELETE FROM $TABLE WHERE image_id NOT IN (SELECT image_id FROM $TABLE ORDER BY last_watched DESC LIMIT $MAX_ENTRIES)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun list(): List<WatchHistoryEntry> {
        val result = mutableListOf<WatchHistoryEntry>()
        readableDatabase.query(TABLE, null, null, null, null, null, "last_watched DESC").use { c ->
            while (c.moveToNext()) result += WatchHistoryEntry(
                c.getInt(c.getColumnIndexOrThrow("image_id")), c.getString(c.getColumnIndexOrThrow("detail_url")),
                c.getString(c.getColumnIndexOrThrow("thumbnail_url")), c.getString(c.getColumnIndexOrThrow("title")),
                c.getString(c.getColumnIndexOrThrow("tags")), c.getString(c.getColumnIndexOrThrow("media_type")),
                c.getLong(c.getColumnIndexOrThrow("first_watched")), c.getLong(c.getColumnIndexOrThrow("last_watched")),
                c.getInt(c.getColumnIndexOrThrow("watch_count")), c.getString(c.getColumnIndexOrThrow("full_url")),
                c.getInt(c.getColumnIndexOrThrow("width")), c.getInt(c.getColumnIndexOrThrow("height")),
                c.getString(c.getColumnIndexOrThrow("mime_type")))
        }
        return result
    }

    fun delete(imageId: Int) { writableDatabase.delete(TABLE, "image_id=?", arrayOf(imageId.toString())) }
    fun clear() { writableDatabase.delete(TABLE, null, null) }

    private fun mediaType(mime: String?, url: String?): String = when {
        mime?.contains("video", true) == true || url?.substringBefore('?')?.substringAfterLast('.')?.lowercase() in setOf("mp4", "webm", "mov") -> "video"
        mime?.contains("gif", true) == true || url?.substringBefore('?')?.endsWith(".gif", true) == true -> "gif"
        else -> "image"
    }
}
