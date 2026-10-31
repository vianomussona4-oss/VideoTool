package com.example.videotool

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
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

object MediaTools {

    // ------------------------------------------------------------------
    // 1) Dividir o vídeo em 1 foto por segundo -> Pictures/VideoFrames/<pasta>
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
        val folder = "Pictures/VideoFrames/video_${System.currentTimeMillis()}"

        var saved = 0
        for (i in 0 until total) {
            val frame: Bitmap? = retriever.getFrameAtTime(
                i * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST
            )
            if (frame != null) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "frame_%05d.jpg".format(i))
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        frame.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    }
                    saved++
                }
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
        val tmp = File(context.cacheDir, "export_${System.currentTimeMillis()}.$ext")

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onDone(copyToMediaStore(context, tmp, audioOnly))
                    tmp.delete()
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

    private fun copyToMediaStore(context: Context, file: File, audio: Boolean): Uri? {
        val collection = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (audio) "audio/mp4" else "video/mp4")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (audio) "Music/VideoTool" else "Movies/VideoTool"
            )
        }
        val uri = context.contentResolver.insert(collection, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        return uri
    }
}
