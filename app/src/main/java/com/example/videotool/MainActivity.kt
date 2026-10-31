package com.example.videotool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        val initial: Uri? = intent?.data
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(initial)
                }
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = when (AppSettings.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val accent = Color(AppSettings.accentColor)
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val scheme = if (dark) {
        darkColorScheme(primary = accent, onPrimary = onAccent, secondary = accent, tertiary = accent)
    } else {
        lightColorScheme(primary = accent, onPrimary = onAccent, secondary = accent, tertiary = accent)
    }
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    SideEffect {
        activity?.let { a ->
            WindowCompat.getInsetsController(a.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun requiredPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO
    )
    Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    Build.VERSION.SDK_INT >= 23 -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    else -> emptyArray()
}

private fun ok(context: Context, p: String) =
    ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

private fun hasVideoAccess(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= 34 ->
        ok(context, Manifest.permission.READ_MEDIA_VIDEO) ||
            ok(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> ok(context, Manifest.permission.READ_MEDIA_VIDEO)
    Build.VERSION.SDK_INT >= 23 -> ok(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> true
}

private fun hasAudioAccess(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= 33 -> ok(context, Manifest.permission.READ_MEDIA_AUDIO)
    Build.VERSION.SDK_INT >= 23 -> ok(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> true
}

@Composable
fun AppRoot(initialUri: Uri?) {
    val context = LocalContext.current
    val perms = remember { requiredPermissions() }

    var granted by remember { mutableStateOf(hasVideoAccess(context)) }
    var audioAllowed by remember { mutableStateOf(hasAudioAccess(context)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        granted = hasVideoAccess(context)
        audioAllowed = hasAudioAccess(context)
    }

    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var openFolderId by rememberSaveable { mutableStateOf<Long?>(null) }
    var screen by rememberSaveable { mutableStateOf("home") }   // home | settings | dups
    var playerData by remember {
        mutableStateOf<Pair<List<VideoItem>, Int>?>(
            initialUri?.let { listOf(VideoRepository.fromUri(context, it)) to 0 }
        )
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) playerData = listOf(VideoRepository.fromUri(context, uri)) to 0
    }

    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(perms) }

    LaunchedEffect(granted) {
        if (granted) {
            loading = true
            folders = withContext(Dispatchers.IO) { VideoRepository.load(context) }
            loading = false
        }
    }

    val pd = playerData
    when {
        pd != null -> PlayerScreen(pd.first, pd.second, onExit = { playerData = null })

        screen == "dups" -> {
            BackHandler { screen = "settings" }
            DuplicatesScreen(
                audioAllowed = audioAllowed,
                onRequestAudio = { permLauncher.launch(perms) },
                onBack = { screen = "settings" }
            )
        }

        screen == "settings" -> {
            BackHandler { screen = "home" }
            SettingsScreen(onBack = { screen = "home" }, onDuplicates = { screen = "dups" })
        }

        !granted -> PermissionScreen(
            onRequest = { permLauncher.launch(perms) },
            onPickFile = { filePicker.launch(arrayOf("*/*")) },
            onSettings = { screen = "settings" }
        )

        else -> {
            val folder = folders.firstOrNull { it.id == openFolderId }
            if (folder != null) {
                BackHandler { openFolderId = null }
                VideosScreen(
                    folder = folder,
                    onBack = { openFolderId = null },
                    onPlay = { i -> playerData = folder.videos to i }
                )
            } else {
                FoldersScreen(
                    folders = folders,
                    loading = loading,
                    onOpen = { openFolderId = it.id },
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onSettings = { screen = "settings" }
                )
            }
        }
    }
}
