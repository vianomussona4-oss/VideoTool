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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial: Uri? = intent?.data
        setContent { MaterialTheme { AppRoot(initial) } }
    }
}

private fun requiredPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    Build.VERSION.SDK_INT >= 23 -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    else -> emptyArray()
}

private fun hasAccess(context: Context, perms: Array<String>): Boolean {
    fun ok(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return when {
        perms.isEmpty() -> true
        Build.VERSION.SDK_INT >= 34 ->
            ok(Manifest.permission.READ_MEDIA_VIDEO) || ok(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else -> perms.all { ok(it) }
    }
}

@Composable
fun AppRoot(initialUri: Uri?) {
    val context = LocalContext.current
    val perms = remember { requiredPermissions() }

    var granted by remember { mutableStateOf(hasAccess(context, perms)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasAccess(context, perms) }

    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var openFolderId by rememberSaveable { mutableStateOf<Long?>(null) }
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
    if (pd != null) {
        PlayerScreen(pd.first, pd.second, onExit = { playerData = null })
    } else if (!granted) {
        PermissionScreen(
            onRequest = { permLauncher.launch(perms) },
            onPickFile = { filePicker.launch(arrayOf("*/*")) }
        )
    } else {
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
                onPickFile = { filePicker.launch(arrayOf("*/*")) }
            )
        }
    }
}
