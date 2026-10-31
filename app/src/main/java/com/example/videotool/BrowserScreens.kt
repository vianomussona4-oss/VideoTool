package com.example.videotool

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun loadVideoThumb(context: Context, uri: Uri, id: Long): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= 29) {
        context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Video.Thumbnails.getThumbnail(
            context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null
        )
    }
} catch (_: Exception) {
    null
}

private fun loadThumb(context: Context, item: VideoItem): Bitmap? =
    loadVideoThumb(context, item.uri, item.id)

@Composable
fun Thumb(item: VideoItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { loadThumb(context, item) }
    }
    Box(modifier.background(Color(0xFF222222)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("▶", color = Color.White)
        }
    }
}

@Composable
fun PermissionScreen(onRequest: () -> Unit, onPickFile: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Para listar os vídeos do celular, o app precisa de acesso aos arquivos.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("Permitir acesso") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPickFile) { Text("Abrir um arquivo manualmente") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSettings) { Text("⚙ Configurações") }
    }
}

@Composable
fun FoldersScreen(
    folders: List<Folder>,
    loading: Boolean,
    onOpen: (Folder) -> Unit,
    onPickFile: () -> Unit,
    onSettings: () -> Unit
) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Pastas de vídeo", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onPickFile) { Text("Abrir arquivo") }
            TextButton(onClick = onSettings) { Text("⚙", style = MaterialTheme.typography.titleLarge) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (folders.isEmpty()) Text("Nenhum vídeo encontrado.", Modifier.padding(16.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(folders, key = { it.id }) { folder ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(folder) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Thumb(folder.videos.first(), Modifier.size(72.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("📁 ${folder.name}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                        Text("${folder.videos.size} vídeo(s)", style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun VideosScreen(folder: Folder, onBack: () -> Unit, onPlay: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Pastas") }
            Text(folder.name, style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(folder.videos) { i, v ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPlay(i) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Thumb(v, Modifier.width(112.dp).height(64.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${formatTime(v.durationMs)} · ${formatSize(v.size)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
