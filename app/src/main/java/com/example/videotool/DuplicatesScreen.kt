package com.example.videotool

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
private fun FileRow(f: MediaFile, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        if (f.isAudio) {
            Box(Modifier.size(56.dp).background(Color(0xFF222222)), contentAlignment = Alignment.Center) {
                Text("🎵")
            }
        } else {
            Thumb(VideoItem(f.id, f.uri, f.name, f.durationMs, f.size), Modifier.size(56.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                (if (f.isAudio) "Áudio" else "Vídeo") + " · ${formatTime(f.durationMs)} · ${formatSize(f.size)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun DuplicatesScreen(audioAllowed: Boolean, onRequestAudio: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var includeAudio by remember(audioAllowed) { mutableStateOf(audioAllowed) }
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ScanResult?>(null) }
    val selected = remember { mutableStateListOf<Uri>() }
    var message by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }

    fun removeSelectedFromResult() {
        val sel = selected.toSet()
        result = result?.let { r ->
            ScanResult(
                r.identical.map { g -> g.filter { it.uri !in sel } }.filter { it.size > 1 },
                r.similar.map { g -> g.filter { it.uri !in sel } }.filter { it.size > 1 }
            )
        }
        selected.clear()
    }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            message = "Arquivos apagados."
            removeSelectedFromResult()
        } else {
            message = "Nada foi apagado."
        }
    }

    fun deleteSelected() {
        val uris = selected.toList()
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else {
            val deleted = mutableListOf<Uri>()
            for (u in uris) {
                try {
                    if (context.contentResolver.delete(u, null, null) > 0) deleted += u
                } catch (_: Exception) {
                }
            }
            message = "${deleted.size} de ${uris.size} apagados." +
                if (deleted.size < uris.size) " Apague os restantes pelo app Arquivos." else ""
            selected.clear()
            selected.addAll(deleted)
            removeSelectedFromResult()
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Voltar") }
            Text("Duplicados e capas parecidas", style = MaterialTheme.typography.titleMedium)
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (audioAllowed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeAudio, { includeAudio = it })
                    Text("Incluir áudios")
                }
            } else {
                OutlinedButton(onClick = onRequestAudio) { Text("Permitir acesso aos áudios") }
            }
            Button(
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scanning = true
                    message = ""
                    selected.clear()
                    scope.launch {
                        result = DuplicateScanner.scan(context, includeAudio && audioAllowed) { progress = it }
                        scanning = false
                    }
                }
            ) { Text(if (result == null) "Procurar" else "Procurar de novo") }
            if (scanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress, style = MaterialTheme.typography.bodySmall)
            }
            if (message.isNotEmpty()) Text(message)
        }

        val r = result
        if (r != null && !scanning) {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Idênticos (${r.identical.size})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Capas parecidas (${r.similar.size})") })
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (tab == 0) {
                    OutlinedButton(onClick = {
                        selected.clear()
                        r.identical.forEach { g -> g.drop(1).forEach { selected += it.uri } }
                    }) { Text("Marcar cópias") }
                }
                Button(enabled = selected.isNotEmpty(), onClick = { deleteSelected() }) {
                    Text("Apagar (${selected.size})")
                }
            }
            Text(
                if (tab == 0) "Mesmo tamanho e mesmo conteúdo. \"Marcar cópias\" mantém o arquivo mais antigo de cada grupo."
                else "Capas visualmente parecidas. Podem não ser o mesmo arquivo, confira antes de apagar.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            val groups = if (tab == 0) r.identical else r.similar
            if (groups.isEmpty()) {
                Text("Nada encontrado aqui.", Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                itemsIndexed(groups) { gi, g ->
                    Text(
                        "Grupo ${gi + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, top = 10.dp)
                    )
                    g.forEach { f ->
                        FileRow(f, f.uri in selected) {
                            if (f.uri in selected) selected.remove(f.uri) else selected.add(f.uri)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
