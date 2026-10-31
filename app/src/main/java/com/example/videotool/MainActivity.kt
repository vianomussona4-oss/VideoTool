package com.example.videotool

import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// Faixa de velocidade pedida: -0.2x (reverso) até 4.0x
const val MIN_SPEED = -0.2f
const val MAX_SPEED = 4.0f

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial: Uri? = intent?.data
        setContent { MaterialTheme { VideoToolScreen(initial) } }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoToolScreen(initialUri: Uri?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var videoUri by remember { mutableStateOf(initialUri) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var loop by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var trimRange by remember { mutableStateOf(0f..1f) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Abrir QUALQUER arquivo (o ExoPlayer decide se consegue decodificar)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) videoUri = uri
    }

    // Android 6 a 9: pedir permissão para gravar na galeria
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT in 23..28 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    LaunchedEffect(videoUri) {
        videoUri?.let {
            player.setMediaItem(MediaItem.fromUri(it))
            player.prepare()
            player.playWhenReady = true
        }
    }

    // Listener: duração + erros de formato
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0)
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Toast.makeText(context, "Formato não suportado: ${error.errorCodeName}",
                    Toast.LENGTH_LONG).show()
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    player.volume = if (muted) 0f else 1f

    // Velocidade: >0 usa o ExoPlayer; <0 faz "rewind" por seeks repetidos; 0 = pausa
    LaunchedEffect(speed) {
        when {
            speed > 0f -> {
                player.playbackParameters = PlaybackParameters(speed)
            }
            speed == 0f -> player.pause()
            else -> {
                player.pause()
                val tickMs = 100L
                while (true) {
                    delay(tickMs)
                    val target = player.currentPosition + (speed * tickMs).toLong()
                    if (target <= 0L) { player.seekTo(0); break }
                    player.seekTo(target)
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Abrir vídeo")
        }

        // Player com controles padrão (play/pause, barra de progresso, tempo)
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )

        // ---- Navegação ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) { Text("⏪ 10s") }
            OutlinedButton(onClick = { player.seekTo(player.currentPosition + 10_000) }) { Text("10s ⏩") }
            OutlinedButton(onClick = { player.pause(); player.seekTo((player.currentPosition + 40).coerceAtMost(durationMs)) }) { Text("Frame ▶|") }
        }

        // ---- Velocidade ----
        Text("Velocidade: ${"%.1f".format(speed)}x" + if (speed < 0) " (reverso)" else "")
        Slider(
            value = speed,
            onValueChange = { speed = (Math.round(it * 10) / 10f) },
            valueRange = MIN_SPEED..MAX_SPEED,
            steps = ((MAX_SPEED - MIN_SPEED) * 10).toInt() - 1
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.5f, 1f, 1.5f, 2f, 4f).forEach { v ->
                AssistChip(onClick = { speed = v }, label = { Text("${v}x") })
            }
        }

        // ---- Opções do player ----
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row { Checkbox(loop, { loop = it }); Text("Repetir", Modifier.padding(top = 12.dp)) }
            Row { Checkbox(muted, { muted = it }); Text("Mudo", Modifier.padding(top = 12.dp)) }
        }

        HorizontalDivider()

        // ---- Frames: 1 foto por segundo ----
        Button(
            enabled = videoUri != null && !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                scope.launch {
                    val n = MediaTools.extractFramesEverySecond(context, videoUri!!) { d, t ->
                        status = "Extraindo frames: $d/$t"
                    }
                    status = "$n fotos salvas em Imagens/VideoFrames"
                    busy = false
                }
            }
        ) { Text("Dividir em fotos (1 por segundo)") }

        HorizontalDivider()

        // ---- Recortar (trim) ----
        Text("Recortar: ${fmt((trimRange.start * durationMs).toLong())} → ${fmt((trimRange.endInclusive * durationMs).toLong())}")
        RangeSlider(value = trimRange, onValueChange = { trimRange = it })

        val startMs = (trimRange.start * durationMs).toLong()
        val endMs = (trimRange.endInclusive * durationMs).toLong()

        Button(
            enabled = videoUri != null && !busy && abs(endMs - startMs) > 500,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true; status = "Cortando…"
                MediaTools.export(context, videoUri!!, startMs, endMs, null, false) {
                    status = if (it != null) "Vídeo salvo em Filmes/VideoTool" else "Erro ao cortar"
                    busy = false
                }
            }
        ) { Text("Salvar trecho recortado") }

        // ---- Redimensionar ----
        Text("Redimensionar (altura):")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(360, 480, 720, 1080).forEach { h ->
                OutlinedButton(
                    enabled = videoUri != null && !busy,
                    onClick = {
                        busy = true; status = "Convertendo para ${h}p…"
                        MediaTools.export(context, videoUri!!, 0, durationMs, h, false) {
                            status = if (it != null) "Salvo em Filmes/VideoTool" else "Erro ao redimensionar"
                            busy = false
                        }
                    }
                ) { Text("${h}p") }
            }
        }

        // ---- Vídeo -> Áudio ----
        Button(
            enabled = videoUri != null && !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true; status = "Extraindo áudio…"
                MediaTools.export(context, videoUri!!, startMs, endMs, null, true) {
                    status = if (it != null) "Áudio (M4A) salvo em Música/VideoTool" else "Erro ao extrair áudio"
                    busy = false
                }
            }
        ) { Text("Converter para áudio (M4A/AAC)") }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status)
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}
