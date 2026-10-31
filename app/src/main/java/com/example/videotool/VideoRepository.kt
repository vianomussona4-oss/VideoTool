package com.example.videotool

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val size: Long
)

data class Folder(val id: Long, val name: String, val videos: List<VideoItem>)

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "%d KB".format(bytes / 1000)
}

object VideoRepository {

    /** Lê todos os vídeos do celular e agrupa por pasta. */
    fun load(context: Context): List<Folder> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        val folderNames = LinkedHashMap<Long, String>()
        val folderVideos = LinkedHashMap<Long, MutableList<VideoItem>>()

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val iBid = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val iBname = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val bucket = c.getLong(iBid)
                folderNames[bucket] = c.getString(iBname) ?: "Sem pasta"
                folderVideos.getOrPut(bucket) { mutableListOf() }.add(
                    VideoItem(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        name = c.getString(iName) ?: "Vídeo",
                        durationMs = c.getLong(iDur),
                        size = c.getLong(iSize)
                    )
                )
            }
        }
        return folderVideos.map { (id, list) -> Folder(id, folderNames[id] ?: "Sem pasta", list) }
            .sortedBy { it.name.lowercase() }
    }

    /** Para vídeos escolhidos pelo seletor de arquivos ou abertos por outro app. */
    fun fromUri(context: Context, uri: Uri): VideoItem {
        var name = uri.lastPathSegment ?: "Vídeo"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0) c.getString(n)?.let { name = it }
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (s >= 0) size = c.getLong(s)
                }
            }
        } catch (_: Exception) {}
        return VideoItem(-1L, uri, name, 0L, size)
    }
}
