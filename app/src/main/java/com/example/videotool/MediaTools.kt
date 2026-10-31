package com.example.videotool

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

object MediaTools {

    private enum class Kind { IMAGE, VIDEO, AUDIO }

    // ------------------------------------------------------------------
    // Salvar no armazenamento do celular (funciona do Android 5 ao 15)
    //  - Android 10+  : MediaStore (sem permissão)
    //  - Android 5-9  : pasta pública + scanner (precisa WRITE_EXTERNAL_STORAGE)
    // ------------------------------------------------------------------
    private fun save(
        context: Context,
        kind: Kind,
        subfolder: String,          // ex.: "VideoFrames/video_123"
        name: String,
        mime: String,
        write: (OutputStream) -> Unit
    ): Uri? {
        val publicDir = when (kind) {
            Kind.IMAGE -> Environment.DIRECTORY_PICTURES
            Kind.VIDEO -> Environment.DIRECTORY_MOVIES
            Kind.AUDIO -> Environment.DIRECTORY_MUSIC
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = when (kind) {
                Kind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                Kind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$publicDir/$subfolder")
            }
            val uri = context.contentResolver.insert(collection, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use(write)
            return uri
        }

        // Android 5 a 9
        return try {
            val dir = File(Environment.getExternalStoragePublicDirectory(publicDir), subfolder)
            if (!dir.exists() && !dir.mkdirs()) return null
            val file = File(dir, name)
            file.outputStream().use(write)
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
            Uri.fromFile(file)
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // 1) Dividir o vídeo em 1 foto por segundo -> Imagens/VideoFrames/<pasta>
    // ------------------------------------------------------------------
    suspend fun extractFramesEverySecond(
        context: Context,
        videoUri: Uri,
        onProgress: (done: Int, total: Int) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val total = (durationMs / 1000).toInt() + 1
        val folder = "VideoFrames/video_${System.currentTimeMillis()}"

        var saved = 0
        for (i in 0 until total) {
            val frame: Bitmap? = try {
                retriever.getFrameAtTime(i * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (e: Exception) { null }

            if (frame != null) {
                val uri = save(context, Kind.IMAGE, folder, "frame_%05d.jpg".format(i), "image/jpeg") {
                    frame.compress(Bitmap.CompressFormat.JPEG, 95, it)
                }
                if (uri != null) saved++
                frame.recycle()
            }
            onProgress(i + 1, total)
        }
        retriever.release()
        saved
    }

    // ------------------------------------------------------------------
    // 2) Cortar / redimensionar / converter para áudio (Media3 Transformer)
    //    IMPORTANTE: chamar a partir da thread principal.
    // ------------------------------------------------------------------
    @OptIn(UnstableApi::class)
    fun export(
        context: Context,
        videoUri: Uri,
        startMs: Long,
        endMs: Long,
        targetHeight: Int?,        // ex.: 480, 720, 1080 (null = manter)
        audioOnly: Boolean,
        onDone: (Uri?) -> Unit
    ) {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()
        val item = MediaItem.Builder().setUri(videoUri).setClippingConfiguration(clipping).build()

        val videoEffects = mutableListOf<Effect>()
        if (targetHeight != null && !audioOnly) {
            videoEffects += Presentation.createForHeight(targetHeight)
        }

        val edited = EditedMediaItem.Builder(item)
            .setRemoveVideo(audioOnly)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val ext = if (audioOnly) "m4a" else "mp4"
        val stamp = System.currentTimeMillis()
        val tmp = File(context.cacheDir, "export_$stamp.$ext")

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val uri = save(
                        context,
                        if (audioOnly) Kind.AUDIO else Kind.VIDEO,
                        "VideoTool",
                        "video_$stamp.$ext",
                        if (audioOnly) "audio/mp4" else "video/mp4"
                    ) { out -> tmp.inputStream().use { it.copyTo(out) } }
                    tmp.delete()
                    onDone(uri)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    tmp.delete()
                    onDone(null)
                }
            })
            .build()

        transformer.start(edited, tmp.absolutePath)
    }
}
