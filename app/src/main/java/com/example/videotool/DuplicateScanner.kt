package com.example.videotool

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.MessageDigest

data class MediaFile(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val durationMs: Long,
    val isAudio: Boolean
)

data class ScanResult(
    val identical: List<List<MediaFile>>,   // mesmo tamanho e mesmo conteúdo
    val similar: List<List<MediaFile>>      // capas visualmente parecidas
)

object DuplicateScanner {

    private fun list(context: Context, audio: Boolean): List<MediaFile> {
        val base = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val durCol = if (audio) MediaStore.Audio.Media.DURATION else MediaStore.Video.Media.DURATION
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            durCol
        )
        val out = mutableListOf<MediaFile>()
        try {
            context.contentResolver.query(
                base, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += MediaFile(
                        id = id,
                        uri = ContentUris.withAppendedId(base, id),
                        name = c.getString(1) ?: "arquivo",
                        size = c.getLong(2),
                        durationMs = c.getLong(3),
                        isAudio = audio
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Hash rápido: tamanho + início + meio + fim do arquivo (1 MB de cada). */
    private fun quickHash(context: Context, f: MediaFile): String? = try {
        val pfd = context.contentResolver.openFileDescriptor(f.uri, "r")
        if (pfd == null) null
        else ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            val ch = input.channel
            val md = MessageDigest.getInstance("SHA-256")
            val chunk = 1 shl 20
            val buf = ByteBuffer.allocate(chunk)
            val offsets = listOf(
                0L,
                (f.size / 2 - chunk / 2).coerceAtLeast(0L),
                (f.size - chunk).coerceAtLeast(0L)
            ).distinct()
            for (off in offsets) {
                buf.clear()
                val n = ch.read(buf, off)
                if (n > 0) md.update(buf.array(), 0, n)
            }
            md.update(f.size.toString().toByteArray())
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (_: Exception) {
        null
    }

    private fun cover(context: Context, f: MediaFile): Bitmap? {
        return try {
            if (f.isAudio) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, f.uri)
                    val bytes = r.embeddedPicture
                    if (bytes == null) null
                    else {
                        val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    }
                } finally {
                    r.release()
                }
            } else {
                loadVideoThumb(context, f.uri, f.id)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun gray(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /** "Impressão digital" visual de 64 bits (dHash). */
    private fun dHash(bmp: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        var h = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (gray(s.getPixel(x, y)) > gray(s.getPixel(x + 1, y))) h = h or (1L shl bit)
                bit++
            }
        }
        if (s !== bmp) s.recycle()
        return h
    }

    suspend fun scan(
        context: Context,
        includeAudio: Boolean,
        onProgress: (String) -> Unit
    ): ScanResult = withContext(Dispatchers.IO) {
        val files = list(context, false) + if (includeAudio) list(context, true) else emptyList()

        // 1) Idênticos
        val bySize = files.filter { it.size > 0 }
            .groupBy { it.isAudio to it.size }
            .filter { it.value.size > 1 }
        val total = bySize.values.sumOf { it.size }
        var done = 0
        val identical = mutableListOf<List<MediaFile>>()
        for ((_, group) in bySize) {
            val byHash = HashMap<String, MutableList<MediaFile>>()
            for (f in group) {
                done++
                onProgress("Comparando arquivos… $done/$total")
                val h = quickHash(context, f) ?: continue
                byHash.getOrPut(h) { mutableListOf() }.add(f)
            }
            byHash.values.filter { it.size > 1 }.forEach { identical += it }
        }

        // 2) Capas parecidas
        val hashes = ArrayList<Pair<MediaFile, Long>>()
        for ((i, f) in files.withIndex()) {
            onProgress("Analisando capas… ${i + 1}/${files.size}")
            val bmp = cover(context, f) ?: continue
            val h = dHash(bmp)
            bmp.recycle()
            val ones = java.lang.Long.bitCount(h)
            if (ones in 8..56) hashes += f to h   // ignora imagens lisas (ex.: tela preta)
        }

        val parent = IntArray(hashes.size) { it }
        fun find(x: Int): Int {
            var a = x
            while (parent[a] != a) {
                parent[a] = parent[parent[a]]
                a = parent[a]
            }
            return a
        }
        for (i in hashes.indices) {
            for (j in i + 1 until hashes.size) {
                if (hashes[i].first.isAudio == hashes[j].first.isAudio &&
                    java.lang.Long.bitCount(hashes[i].second xor hashes[j].second) <= 6
                ) {
                    parent[find(i)] = find(j)
                }
            }
        }
        val groups = hashes.indices.groupBy { find(it) }.values
            .filter { it.size > 1 }
            .map { idxs -> idxs.map { hashes[it].first } }

        // tira dos "parecidos" os grupos que já aparecem como idênticos
        val identId = HashMap<Uri, Int>()
        identical.forEachIndexed { gi, g -> g.forEach { identId[it.uri] = gi } }
        val similar = groups.filter { g ->
            val ids = g.map { identId[it.uri] }
            !(ids.all { it != null } && ids.toSet().size == 1)
        }

        ScanResult(identical, similar)
    }
}
