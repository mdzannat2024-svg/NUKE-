package com.nuke.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class Video(
    val uri: Uri,
    val name: String,
    val size: Long,
    val durationMs: Long,
    val folder: String
)

// Shared playlist so we don't pass big lists through Intents
object Queue {
    var items: List<Video> = emptyList()
    var index: Int = 0
}

object Videos {
    fun scan(ctx: Context): Map<String, List<Video>> {
        val out = LinkedHashMap<String, MutableList<Video>>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
            "${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE ASC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(proj[0])
            val iName = c.getColumnIndexOrThrow(proj[1])
            val iSize = c.getColumnIndexOrThrow(proj[2])
            val iDur = c.getColumnIndexOrThrow(proj[3])
            val iDir = c.getColumnIndexOrThrow(proj[4])
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(iId)
                )
                val folder = c.getString(iDir) ?: "Other"
                out.getOrPut(folder) { mutableListOf() }.add(
                    Video(uri, c.getString(iName) ?: "Video", c.getLong(iSize), c.getLong(iDur), folder)
                )
            }
        }
        return out.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

    fun fmtSize(b: Long) = if (b > 1_000_000_000) "%.2f GB".format(b / 1e9) else "%.1f MB".format(b / 1e6)
    fun fmtTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = s % 3600 / 60; val x = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
    }
}
