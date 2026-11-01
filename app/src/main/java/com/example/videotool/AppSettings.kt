package com.example.videotool

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Configurações do app (guardadas no celular e observáveis pelo Compose). */
object AppSettings {
    private var prefs: SharedPreferences? = null

    var themeMode by mutableIntStateOf(0)            // 0 sistema, 1 claro, 2 escuro
    var accentColor by mutableIntStateOf(0xFF1E88E5.toInt())
    var imagesTreeUri by mutableStateOf<String?>(null)    // pasta escolhida para as fotos
    var exportsTreeUri by mutableStateOf<String?>(null)   // pasta para vídeos/áudios convertidos
    var frameIntervalMs by mutableIntStateOf(1000)
    var imageFormat by mutableStateOf("jpg")
    var jpgQuality by mutableIntStateOf(95)
    var resumeEnabled by mutableStateOf(true)
    var autoNext by mutableStateOf(true)
    var defaultScale by mutableStateOf(ScaleMode.FIT)

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        themeMode = p.getInt("themeMode", 0)
        accentColor = p.getInt("accentColor", 0xFF1E88E5.toInt())
        imagesTreeUri = p.getString("imagesTreeUri", null)
        exportsTreeUri = p.getString("exportsTreeUri", null)
        frameIntervalMs = if (p.contains("frameIntervalMs")) p.getInt("frameIntervalMs", 1000)
            else p.getInt("frameIntervalSec", 1) * 1000
        imageFormat = p.getString("imageFormat", "jpg") ?: "jpg"
        jpgQuality = p.getInt("jpgQuality", 95)
        resumeEnabled = p.getBoolean("resumeEnabled", true)
        autoNext = p.getBoolean("autoNext", true)
        defaultScale = try {
            ScaleMode.valueOf(p.getString("defaultScale", "FIT") ?: "FIT")
        } catch (e: Exception) {
            ScaleMode.FIT
        }
    }

    fun save() {
        prefs?.edit()
            ?.putInt("themeMode", themeMode)
            ?.putInt("accentColor", accentColor)
            ?.putString("imagesTreeUri", imagesTreeUri)
            ?.putString("exportsTreeUri", exportsTreeUri)
            ?.putInt("frameIntervalMs", frameIntervalMs)
            ?.putString("imageFormat", imageFormat)
            ?.putInt("jpgQuality", jpgQuality)
            ?.putBoolean("resumeEnabled", resumeEnabled)
            ?.putBoolean("autoNext", autoNext)
            ?.putString("defaultScale", defaultScale.name)
            ?.apply()
    }
}

/** Opções de intervalo entre fotos. */
val FRAME_INTERVALS_MS = listOf(200, 500, 1000, 2000)

fun intervalLabel(ms: Int): String = when (ms) {
    200 -> "0,2s"
    500 -> "0,5s"
    1000 -> "1s"
    2000 -> "2s"
    else -> "${ms / 1000.0}s"
}
