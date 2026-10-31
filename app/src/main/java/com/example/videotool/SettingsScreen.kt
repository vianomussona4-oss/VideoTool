package com.example.videotool

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

const val DEVELOPER_EMAIL = "vianomussona4@gmail.com"

private val PRESET_COLORS = listOf(
    0xFF1E88E5, 0xFF00ACC1, 0xFF43A047, 0xFFFB8C00,
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF546E7A
)

private fun treeLabel(s: String?): String =
    if (s == null) "Padrão do sistema" else Uri.decode(Uri.parse(s).lastPathSegment ?: s)

private fun persistTree(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    } catch (_: Exception) {
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider()
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onDuplicates: () -> Unit) {
    val context = LocalContext.current
    fun change(block: () -> Unit) {
        block()
        AppSettings.save()
    }

    val imagesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTree(context, uri)
            change { AppSettings.imagesTreeUri = uri.toString() }
        }
    }
    val exportsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTree(context, uri)
            change { AppSettings.exportsTreeUri = uri.toString() }
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Voltar") }
            Text("Configurações", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---------------- Aparência ----------------
            SectionTitle("Aparência")
            Text("Tema")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Sistema", "Claro", "Escuro").forEachIndexed { i, label ->
                    OptionChip(label, AppSettings.themeMode == i) { change { AppSettings.themeMode = i } }
                }
            }
            Text("Cor do app")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PRESET_COLORS.forEach { c ->
                    val color = Color(c)
                    val selected = AppSettings.accentColor == color.toArgb()
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(color)
                            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground, CircleShape) else Modifier)
                            .clickable { change { AppSettings.accentColor = color.toArgb() } }
                    )
                }
            }
            val cur = Color(AppSettings.accentColor)
            var r by remember(AppSettings.accentColor) { mutableFloatStateOf(cur.red) }
            var g by remember(AppSettings.accentColor) { mutableFloatStateOf(cur.green) }
            var b by remember(AppSettings.accentColor) { mutableFloatStateOf(cur.blue) }
            Text("Cor personalizada (R ${(r * 255).roundToInt()}  G ${(g * 255).roundToInt()}  B ${(b * 255).roundToInt()})")
            Slider(r, { r = it; change { AppSettings.accentColor = Color(r, g, b).toArgb() } })
            Slider(g, { g = it; change { AppSettings.accentColor = Color(r, g, b).toArgb() } })
            Slider(b, { b = it; change { AppSettings.accentColor = Color(r, g, b).toArgb() } })

            // ---------------- Armazenamento ----------------
            SectionTitle("Onde guardar os arquivos")
            Text("Fotos extraídas dos vídeos:")
            Text(treeLabel(AppSettings.imagesTreeUri), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { imagesPicker.launch(null) }) { Text("Escolher pasta") }
                OutlinedButton(onClick = { change { AppSettings.imagesTreeUri = null } }) { Text("Usar padrão") }
            }
            Text("Vídeos e áudios convertidos:")
            Text(treeLabel(AppSettings.exportsTreeUri), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportsPicker.launch(null) }) { Text("Escolher pasta") }
                OutlinedButton(onClick = { change { AppSettings.exportsTreeUri = null } }) { Text("Usar padrão") }
            }
            Text(
                "Padrão: Imagens/VideoFrames, Filmes/VideoTool e Música/VideoTool.",
                style = MaterialTheme.typography.bodySmall
            )

            // ---------------- Fotos ----------------
            SectionTitle("Fotos dos vídeos")
            Text("Gerar uma foto a cada:")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 5, 10, 30).forEach { s ->
                    OptionChip("${s}s", AppSettings.frameIntervalSec == s) { change { AppSettings.frameIntervalSec = s } }
                }
            }
            Text("Formato da imagem:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip("JPG (menor)", AppSettings.imageFormat == "jpg") { change { AppSettings.imageFormat = "jpg" } }
                OptionChip("PNG (qualidade total)", AppSettings.imageFormat == "png") { change { AppSettings.imageFormat = "png" } }
            }
            if (AppSettings.imageFormat == "jpg") {
                Text("Qualidade JPG: ${AppSettings.jpgQuality}%")
                Slider(
                    value = AppSettings.jpgQuality.toFloat(),
                    onValueChange = { change { AppSettings.jpgQuality = it.roundToInt() } },
                    valueRange = 50f..100f
                )
            }

            // ---------------- Reprodução ----------------
            SectionTitle("Reprodução")
            SwitchRow("Retomar de onde parou", AppSettings.resumeEnabled) { change { AppSettings.resumeEnabled = it } }
            SwitchRow("Tocar o próximo vídeo automaticamente", AppSettings.autoNext) { change { AppSettings.autoNext = it } }
            Text("Tamanho padrão do vídeo na tela:")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScaleMode.values().forEach { m ->
                    OptionChip(m.label, AppSettings.defaultScale == m) { change { AppSettings.defaultScale = m } }
                }
            }
            OutlinedButton(onClick = {
                context.getSharedPreferences("resume", Context.MODE_PRIVATE).edit().clear().apply()
                Toast.makeText(context, "Histórico de retomar apagado", Toast.LENGTH_SHORT).show()
            }) { Text("Limpar histórico de retomar") }

            // ---------------- Ferramentas ----------------
            SectionTitle("Ferramentas")
            Button(onClick = onDuplicates, modifier = Modifier.fillMaxWidth()) {
                Text("Procurar duplicados e capas parecidas")
            }
            Text(
                "Encontra vídeos e áudios idênticos e arquivos com capas semelhantes, para você liberar espaço.",
                style = MaterialTheme.typography.bodySmall
            )

            // ---------------- Sobre e contacto ----------------
            SectionTitle("Sobre e contacto")
            Text("VideoTool 1.0")
            Text("Contacto do desenvolvedor:")
            Text(DEVELOPER_EMAIL, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:")
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(DEVELOPER_EMAIL))
                        putExtra(Intent.EXTRA_SUBJECT, "VideoTool - Contacto")
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "Nenhum app de e-mail encontrado", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Enviar e-mail") }
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("email", DEVELOPER_EMAIL))
                    Toast.makeText(context, "E-mail copiado", Toast.LENGTH_SHORT).show()
                }) { Text("Copiar e-mail") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
