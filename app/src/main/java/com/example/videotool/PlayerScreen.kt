package com.example.videotool

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// Faixa de velocidade: -0.2x (reverso) até 4.0x
const val MIN_SPEED = -0.2f
const val MAX_SPEED = 4.0f

/** Modos de tamanho da imagem na tela. */
@OptIn(UnstableApi::class)
enum class ScaleMode(val label: String, val resize: Int, val ratio: Float?) {
    FIT("Ajustar", AspectRatioFrameLayout.RESIZE_MODE_FIT, null),
    FILL("Esticar", AspectRatioFrameLayout.RESIZE_MODE_FILL, null),
    ZOOM("Preencher (cortar)", AspectRatioFrameLayout.RESIZE_MODE_ZOOM, null),
    WIDTH("Largura", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH, null),
    HEIGHT("Altura", AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT, null),
    SQUARE("Quadrado 1:1", AspectRatioFrameLayout.RESIZE_MODE_FILL, 1f),
    R169("16:9", AspectRatioFrameLayout.RESIZE_MODE_FILL, 16f / 9f),
    R43("4:3", AspectRatioFrameLayout.RESIZE_MODE_FILL, 4f / 3f)
}

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun OptionChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    if (selected) Button(onClick = onClick, contentPadding = pad) { Text(text) }
    else OutlinedButton(onClick = onClick, contentPadding = pad) { Text(text) }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(playlist: List<VideoItem>, startIndex: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("resume", Context.MODE_PRIVATE) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply { setSeekParameters(SeekParameters.CLOSEST_SYNC) }
    }

    // ---------------- estados ----------------
    var index by remember { mutableIntStateOf(startIndex) }
    val current = playlist[index]

    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var portraitVideo by remember { mutableStateOf(false) }

    var speed by remember { mutableFloatStateOf(1f) }
    var prevSpeed by remember { mutableFloatStateOf(1f) }
    var loop by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var scaleMode by remember { mutableStateOf(ScaleMode.FIT) }

    var fullscreen by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var controlsTick by remember { mutableIntStateOf(0) }
    var panelOpen by remember { mutableStateOf(true) }   // painel abre já aberto
    var sidePanel by remember { mutableStateOf(false) }  // painel lateral (tela cheia/horizontal)
    var tab by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf("") }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }

    var trimRange by remember { mutableStateOf(0f..1f) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val immersive = fullscreen || landscape

    // ---------------- volume e brilho ----------------
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember {
        mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol)
    }
    var brightness by remember {
        mutableFloatStateOf(
            (activity?.window?.attributes?.screenBrightness ?: -1f).let { if (it < 0f) 0.5f else it }
        )
    }

    fun applyVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * maxVol).roundToInt(), 0)
    }

    fun applyBrightness(b: Float) {
        brightness = b.coerceIn(0.02f, 1f)
        activity?.window?.let { w ->
            val lp = w.attributes
            lp.screenBrightness = brightness
            w.attributes = lp
        }
    }

    fun clampPos(p: Long): Long =
        if (durationMs > 0) p.coerceIn(0L, durationMs) else p.coerceAtLeast(0L)

    fun seekBy(delta: Long) {
        exoPlayer.seekTo(clampPos(exoPlayer.currentPosition + delta))
        hint = if (delta > 0) "⏩ +${delta / 1000}s" else "⏪ -${-delta / 1000}s"
    }

    fun togglePlay() {
        if (speed <= 0f) { speed = 1f; return }
        if (exoPlayer.isPlaying) exoPlayer.pause()
        else {
            if (exoPlayer.playbackState == Player.STATE_ENDED) exoPlayer.seekTo(0)
            exoPlayer.play()
        }
    }

    fun backAction() {
        when {
            immersive && sidePanel -> sidePanel = false
            fullscreen -> fullscreen = false
            else -> onExit()
        }
    }

    // ---------------- efeitos ----------------
    SideEffect {
        exoPlayer.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exoPlayer.volume = if (muted) 0f else 1f
    }

    BackHandler { backAction() }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) durationMs = exoPlayer.duration.coerceAtLeast(0)
                if (state == Player.STATE_ENDED) {
                    prefs.edit().remove(playlist[index].uri.toString()).apply()
                    if (index < playlist.lastIndex && !loop) index++
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                portraitVideo = videoSize.height > videoSize.width
            }
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(context, "Formato não suportado: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            val pos = exoPlayer.currentPosition
            if (pos > 0) prefs.edit().putLong(playlist[index].uri.toString(), pos).apply()
            exoPlayer.release()
            activity?.let { a ->
                a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(a.window, a.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                val lp = a.window.attributes
                lp.screenBrightness = -1f
                a.window.attributes = lp
            }
        }
    }

    // Carregar vídeo (e retomar de onde parou)
    LaunchedEffect(index) {
        val item = playlist[index]
        val saved = prefs.getLong(item.uri.toString(), 0L)
        val start = if (saved > 3000 && (item.durationMs == 0L || saved < item.durationMs - 3000)) saved else 0L
        if (start > 0) Toast.makeText(context, "Retomando de ${formatTime(start)}", Toast.LENGTH_SHORT).show()
        exoPlayer.setMediaItem(MediaItem.fromUri(item.uri), start)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        trimRange = 0f..1f
    }

    // Guardar posição a cada 3s
    LaunchedEffect(index) {
        while (true) {
            delay(3000)
            if (exoPlayer.isPlaying) {
                prefs.edit().putLong(playlist[index].uri.toString(), exoPlayer.currentPosition).apply()
            }
        }
    }

    // Posição para a barra de progresso
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = exoPlayer.currentPosition
            delay(250)
        }
    }

    // Velocidade (negativa = voltar)
    LaunchedEffect(speed) {
        val old = prevSpeed
        prevSpeed = speed
        when {
            speed > 0f -> {
                exoPlayer.playbackParameters = PlaybackParameters(speed)
                if (old <= 0f) exoPlayer.play()
            }
            speed == 0f -> exoPlayer.pause()
            else -> {
                exoPlayer.pause()
                val tickMs = 100L
                while (true) {
                    delay(tickMs)
                    val target = exoPlayer.currentPosition + (speed * tickMs).toLong()
                    if (target <= 0L) { exoPlayer.seekTo(0); break }
                    exoPlayer.seekTo(target)
                }
            }
        }
    }

    // Esconder controles sozinho
    LaunchedEffect(showControls, isPlaying, controlsTick) {
        if (showControls && isPlaying) {
            delay(4000)
            showControls = false
        }
    }

    LaunchedEffect(hint) {
        if (hint.isNotEmpty()) { delay(900); hint = "" }
    }

    // Tela cheia: orientação
    LaunchedEffect(fullscreen, portraitVideo) {
        activity?.requestedOrientation = when {
            !fullscreen -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            portraitVideo -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    // Esconder barras do sistema
    LaunchedEffect(immersive) {
        activity?.let { a ->
            val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
            if (immersive) {
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                c.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // ---------------- área do vídeo ----------------
    val videoArea: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        player = exoPlayer
                        keepScreenOn = true
                    }
                },
                update = { it.resizeMode = scaleMode.resize },
                modifier = scaleMode.ratio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize()
            )

            // Gestos: toque = controles, toque duplo = ±10s,
            // arrastar na horizontal = avançar/voltar, vertical esquerda = brilho, direita = volume
            Box(
                Modifier.matchParentSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showControls = !showControls },
                            onDoubleTap = { off ->
                                if (off.x < size.width / 2f) seekBy(-10_000) else seekBy(10_000)
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        var mode = 0
                        var startPos = 0L
                        var acc = 0f
                        fun target() = clampPos(startPos + (acc * 60).toLong())
                        detectDragGestures(
                            onDragStart = { mode = 0; startPos = exoPlayer.currentPosition; acc = 0f },
                            onDragEnd = { if (mode == 1) exoPlayer.seekTo(target()); mode = 0 },
                            onDragCancel = { mode = 0 },
                            onDrag = { change, drag ->
                                change.consume()
                                if (mode == 0) {
                                    mode = if (abs(drag.x) > abs(drag.y)) 1
                                    else if (change.position.x < size.width / 2f) 2 else 3
                                }
                                when (mode) {
                                    1 -> {
                                        acc += drag.x
                                        hint = (if (acc >= 0) "⏩ " else "⏪ ") + formatTime(target())
                                    }
                                    2 -> {
                                        applyBrightness(brightness - drag.y / size.height * 1.5f)
                                        hint = "☀ ${(brightness * 100).roundToInt()}%"
                                    }
                                    3 -> {
                                        applyVolume(volume - drag.y / size.height * 1.5f)
                                        hint = "🔊 ${(volume * 100).roundToInt()}%"
                                    }
                                }
                            }
                        )
                    }
            )

            if (hint.isNotEmpty()) {
                Text(
                    hint, color = Color.White, fontSize = 22.sp,
                    modifier = Modifier.align(Alignment.Center)
                        .background(Color(0x99000000), RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            if (showControls) {
                // Barra de cima
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Color(0x66000000)).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { backAction() }) { Text("←", color = Color.White, fontSize = 20.sp) }
                    Text(
                        current.name, color = Color.White, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        if (immersive) sidePanel = !sidePanel else panelOpen = !panelOpen
                        controlsTick++
                    }) { Text("⚙", color = Color.White, fontSize = 20.sp) }
                    TextButton(onClick = { fullscreen = !fullscreen; controlsTick++ }) {
                        Text(if (fullscreen) "⤢" else "⛶", color = Color.White, fontSize = 20.sp)
                    }
                }

                // Centro
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(enabled = index > 0, onClick = { index--; controlsTick++ }) {
                        Text("⏮", color = Color.White, fontSize = 24.sp)
                    }
                    TextButton(onClick = { seekBy(-10_000); controlsTick++ }) {
                        Text("⏪", color = Color.White, fontSize = 24.sp)
                    }
                    TextButton(onClick = { togglePlay(); controlsTick++ }) {
                        Text(if (isPlaying || speed < 0f) "⏸" else "▶", color = Color.White, fontSize = 36.sp)
                    }
                    TextButton(onClick = { seekBy(10_000); controlsTick++ }) {
                        Text("⏩", color = Color.White, fontSize = 24.sp)
                    }
                    TextButton(enabled = index < playlist.lastIndex, onClick = { index++; controlsTick++ }) {
                        Text("⏭", color = Color.White, fontSize = 24.sp)
                    }
                }

                // Barra de baixo
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color(0x66000000)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(formatTime(positionMs), color = Color.White, fontSize = 12.sp)
                    Slider(
                        value = if (seeking) seekValue
                        else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = { seeking = true; seekValue = it; controlsTick++ },
                        onValueChangeFinished = {
                            exoPlayer.seekTo((seekValue * durationMs).toLong())
                            seeking = false
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text(formatTime(durationMs), color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }

    // ---------------- painel de funções ----------------
    val panelContent: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Reprodução") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Ferramentas") })
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (tab == 0) {
                    Text("Velocidade: ${"%.1f".format(speed)}x" + if (speed < 0) " (reverso)" else "")
                    Slider(
                        value = speed,
                        onValueChange = { speed = Math.round(it * 10) / 10f },
                        valueRange = MIN_SPEED..MAX_SPEED,
                        steps = Math.round((MAX_SPEED - MIN_SPEED) * 10) - 1
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.5f, 1f, 1.5f, 2f, 4f).forEach { v ->
                            OptionChip("${v}x", speed == v) { speed = v }
                        }
                    }

                    Text("Tamanho do vídeo na tela")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScaleMode.values().forEach { m ->
                            OptionChip(m.label, scaleMode == m) { scaleMode = m }
                        }
                        OptionChip("Tela cheia", fullscreen) { fullscreen = !fullscreen }
                    }

                    Text("Brilho: ${(brightness * 100).roundToInt()}%")
                    Slider(value = brightness, onValueChange = { applyBrightness(it) }, valueRange = 0.02f..1f)

                    Text("Volume: ${(volume * 100).roundToInt()}%")
                    Slider(value = volume, onValueChange = { applyVolume(it) })

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(loop, { loop = it }); Text("Repetir")
                        Spacer(Modifier.width(16.dp))
                        Checkbox(muted, { muted = it }); Text("Mudo")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { seekBy(-10_000) }) { Text("⏪ 10s") }
                        OutlinedButton(onClick = { seekBy(10_000) }) { Text("10s ⏩") }
                        OutlinedButton(onClick = {
                            exoPlayer.pause()
                            exoPlayer.seekTo(clampPos(exoPlayer.currentPosition + 40))
                        }) { Text("Frame ▶|") }
                    }
                    Text("Dica: arraste no vídeo para avançar/voltar; lado esquerdo = brilho, lado direito = volume; toque duplo = ±10s.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            scope.launch {
                                val n = MediaTools.extractFramesEverySecond(context, current.uri) { d, t ->
                                    status = "Extraindo frames: $d/$t"
                                }
                                status = "$n fotos salvas em Imagens/VideoFrames"
                                busy = false
                            }
                        }
                    ) { Text("Dividir em fotos (1 por segundo)") }

                    val startMs = (trimRange.start * durationMs).toLong()
                    val endMs = (trimRange.endInclusive * durationMs).toLong()
                    Text("Recortar: ${formatTime(startMs)} → ${formatTime(endMs)}")
                    RangeSlider(value = trimRange, onValueChange = { trimRange = it })
                    Button(
                        enabled = !busy && abs(endMs - startMs) > 500,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true; status = "Cortando…"
                            MediaTools.export(context, current.uri, startMs, endMs, null, false) {
                                status = if (it != null) "Vídeo salvo em Filmes/VideoTool" else "Erro ao cortar"
                                busy = false
                            }
                        }
                    ) { Text("Salvar trecho recortado") }

                    Text("Redimensionar (altura):")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(360, 480, 720, 1080).forEach { h ->
                            OutlinedButton(enabled = !busy, onClick = {
                                busy = true; status = "Convertendo para ${h}p…"
                                MediaTools.export(context, current.uri, 0, durationMs, h, false) {
                                    status = if (it != null) "Salvo em Filmes/VideoTool" else "Erro ao redimensionar"
                                    busy = false
                                }
                            }) { Text("${h}p") }
                        }
                    }

                    Button(
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true; status = "Extraindo áudio…"
                            MediaTools.export(context, current.uri, startMs, endMs, null, true) {
                                status = if (it != null) "Áudio (M4A) salvo em Música/VideoTool" else "Erro ao extrair áudio"
                                busy = false
                            }
                        }
                    ) { Text("Converter para áudio (M4A/AAC)") }

                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (status.isNotEmpty()) Text(status)
                }
            }
        }
    }

    // ---------------- layout ----------------
    Box(
        Modifier.fillMaxSize()
            .background(if (immersive) Color.Black else MaterialTheme.colorScheme.background)
            .then(if (immersive) Modifier else Modifier.systemBarsPadding())
    ) {
        if (immersive) {
            videoArea(Modifier.fillMaxSize())
            if (sidePanel) {
                Box(
                    Modifier.align(Alignment.CenterEnd).width(340.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                ) { panelContent() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                videoArea(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                if (panelOpen) Box(Modifier.weight(1f).fillMaxWidth()) { panelContent() }
            }
        }
    }
}
