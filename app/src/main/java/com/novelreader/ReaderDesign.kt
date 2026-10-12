package com.novelreader

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.novelreader.data.ReaderPreferences
import com.novelreader.reader.RemoteTtsConfig
import kotlin.math.roundToInt

data class ReaderThemeSpec(
    val name: String,
    val background: String,
    val surface: String,
    val accent: String,
    val text: String,
    val lightSystemBars: Boolean = false
)

val readerThemes = listOf(
    ReaderThemeSpec("Oscuro", "#FF14181A", "#FF1E2427", "#FFE0B36A", "#FFF1EFE8"),
    ReaderThemeSpec("Negro", "#FF000000", "#FF111214", "#FF8FB8FF", "#FFE6E6E6"),
    ReaderThemeSpec("Bosque", "#FF121C16", "#FF1B2A21", "#FFA9D6A0", "#FFE8F0E4"),
    ReaderThemeSpec("Violeta", "#FF1A1626", "#FF251F36", "#FFC4A8FF", "#FFEFEAFA"),
    ReaderThemeSpec("Océano", "#FF0F1C28", "#FF172A3B", "#FF7FC8E8", "#FFE8F1F7"),
    ReaderThemeSpec("Sepia", "#FFF3E9D6", "#FFEADCC2", "#FF8A4B22", "#FF3B2D1D", true),
    ReaderThemeSpec("Claro", "#FFFBFAF7", "#FFF0EEE8", "#FF1F5F4A", "#FF1D2321", true),
    ReaderThemeSpec("Gris", "#FFD9D9D6", "#FFCBCBC7", "#FF3B4A5C", "#FF222222", true)
)

val readingFonts = listOf("Times New Roman", "Georgia", "Palatino", "Garamond", "Sans", "Mono")

fun previewFont(name: String): FontFamily = when (name) {
    "Sans" -> FontFamily.SansSerif
    "Mono" -> FontFamily.Monospace
    else -> FontFamily.Serif
}

fun ReaderPreferences.themeSpec(): ReaderThemeSpec = readerThemes.firstOrNull {
    it.background.equals(backgroundColor, ignoreCase = true)
} ?: readerThemes.first()

fun ReaderPreferences.isDark(): Boolean = !themeSpec().lightSystemBars

fun ReaderPreferences.withTheme(theme: String): ReaderPreferences {
    val selected = readerThemes.firstOrNull { it.name == theme } ?: readerThemes.first()
    return copy(backgroundColor = selected.background, textColor = selected.text)
}

private fun color(value: String): Color = Color(AndroidColor.parseColor(value))

@Composable
fun NovelTheme(prefs: ReaderPreferences, content: @Composable () -> Unit) {
    val theme = prefs.themeSpec()
    val background = color(theme.background)
    val surface = color(theme.surface)
    val primary = color(theme.accent)
    val text = color(theme.text)
    val surfaceVariant = if (theme.lightSystemBars) Color.Black.copy(alpha = .08f) else Color.White.copy(alpha = .10f)
    val scheme = if (theme.lightSystemBars) {
        lightColorScheme(
            primary = primary,
            onPrimary = background,
            background = background,
            surface = surface,
            onBackground = text,
            onSurface = text,
            onSurfaceVariant = text.copy(alpha = .70f),
            secondary = primary,
            primaryContainer = primary.copy(alpha = .16f),
            onPrimaryContainer = text,
            secondaryContainer = primary.copy(alpha = .13f),
            onSecondaryContainer = text,
            surfaceVariant = surfaceVariant
        )
    } else {
        darkColorScheme(
            primary = primary,
            onPrimary = background,
            background = background,
            surface = surface,
            onBackground = text,
            onSurface = text,
            onSurfaceVariant = text.copy(alpha = .68f),
            secondary = primary,
            primaryContainer = primary.copy(alpha = .20f),
            onPrimaryContainer = text,
            secondaryContainer = primary.copy(alpha = .16f),
            onSecondaryContainer = text,
            surfaceVariant = surfaceVariant,
            error = Color(0xFFFFB4AB)
        )
    }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = theme.lightSystemBars
                isAppearanceLightNavigationBars = theme.lightSystemBars
            }
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun ReadingSettings(
    prefs: ReaderPreferences,
    change: (ReaderPreferences) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    var remoteTtsInput by remember(prefs.remoteTtsUrl) { mutableStateOf(prefs.remoteTtsUrl) }
    var remoteTtsMessage by remember { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val fontSp = (prefs.fontScale * 16f).roundToInt().coerceIn(13, 28)

    Column(
        modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = if (compact) 8.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 18.dp)
    ) {
        if (!compact) {
            Text("A tu manera", style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
            Text("Cambios inmediatos · guardado automático", style = MaterialTheme.typography.bodySmall)
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.background) {
                Text(
                    "La lluvia se detuvo. Entre las páginas de un libro, Clara encontró el comienzo de otra historia.",
                    Modifier.padding(18.dp),
                    fontFamily = previewFont(prefs.fontFamily),
                    fontSize = fontSp.sp,
                    lineHeight = (fontSp * prefs.lineSpacing).sp,
                    color = color(prefs.textColor)
                )
            }
        }

        Text("Servidor de voz M5", style = MaterialTheme.typography.titleSmall)
        Text(
            "Escribe la IP del equipo donde corre el servidor TTS. No tendrás que generar otro APK al cambiar de red.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = remoteTtsInput,
            onValueChange = { remoteTtsInput = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("IP o URL del servidor") },
            placeholder = { Text("192.168.0.40 o tts.midominio.com") },
            supportingText = { Text("También puedes usar ws://, wss://, http:// o https://") }
        )
        Button(
            onClick = {
                val normalized = RemoteTtsConfig.normalizeWebsocketUrl(remoteTtsInput)
                if (normalized.isBlank()) {
                    remoteTtsMessage = "Escribe una IP o URL válida."
                } else {
                    remoteTtsInput = normalized
                    remoteTtsMessage = "Dirección guardada. Se usará al abrir la lectura en voz alta."
                    change(prefs.copy(remoteTtsUrl = normalized))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Guardar servidor TTS") }
        if (remoteTtsMessage.isNotBlank()) {
            Text(remoteTtsMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        Text("Tema de toda la app", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(readerThemes.map { it.name }, prefs.themeSpec().name) { change(prefs.withTheme(it)) }

        FontSizeControl(prefs, change)
        Text("Tipografía", style = MaterialTheme.typography.titleSmall)
        FontChoices(prefs, change)

        TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "Ocultar espaciado y color ↑" else "Espaciado, alineación y color ↓")
        }
        if (advanced) {
            Text("Interlineado · %.2f".format(prefs.lineSpacing))
            Slider(
                value = prefs.lineSpacing.coerceIn(1.3f, 2.3f),
                onValueChange = { change(prefs.copy(lineSpacing = it)) },
                valueRange = 1.3f..2.3f,
                steps = 19
            )
            Text("Entre párrafos · %.1f em".format(prefs.paragraphSpacing))
            Slider(
                value = prefs.paragraphSpacing.coerceIn(0f, 2.5f),
                onValueChange = { change(prefs.copy(paragraphSpacing = it)) },
                valueRange = 0f..2.5f,
                steps = 24
            )
            Text("Alineación", style = MaterialTheme.typography.titleSmall)
            ChoiceRow(listOf("Izquierda", "Justificado"), if (prefs.justifiedText) "Justificado" else "Izquierda") {
                change(prefs.copy(justifiedText = it == "Justificado"))
            }
            Text("Color del texto", style = MaterialTheme.typography.titleSmall)
            ChoiceRow(
                listOf("Automático", "Blanco", "Sepia", "Oscuro"),
                when (prefs.textColor.uppercase()) {
                    "#FFFFFFFF" -> "Blanco"
                    "#FFDDCFA5" -> "Sepia"
                    "#FF252B27" -> "Oscuro"
                    else -> "Automático"
                }
            ) {
                change(
                    prefs.copy(
                        textColor = when (it) {
                            "Blanco" -> "#FFFFFFFF"
                            "Sepia" -> "#FFDDCFA5"
                            "Oscuro" -> "#FF252B27"
                            else -> prefs.themeSpec().text
                        }
                    )
                )
            }
        }

        Text("Cómo pasar las páginas", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(listOf("Desplazamiento", "Páginas"), if (prefs.verticalReading) "Desplazamiento" else "Páginas") {
            change(prefs.copy(verticalReading = it == "Desplazamiento"))
        }
        Text("Orientación", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(listOf("Vertical", "Horizontal"), if (prefs.devicePortrait) "Vertical" else "Horizontal") {
            change(prefs.copy(devicePortrait = it == "Vertical"))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FontSizeControl(prefs: ReaderPreferences, change: (ReaderPreferences) -> Unit) {
    val size = (prefs.fontScale * 16f).roundToInt().coerceIn(13, 28)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Tamaño de lectura")
        OutlinedButton(enabled = size > 13, onClick = { change(prefs.copy(fontScale = (size - 1) / 16f)) }) { Text("A−") }
        Text("$size")
        OutlinedButton(enabled = size < 28, onClick = { change(prefs.copy(fontScale = (size + 1) / 16f)) }) { Text("A+") }
    }
    Slider(
        value = size.toFloat(),
        onValueChange = { change(prefs.copy(fontScale = it.roundToInt() / 16f)) },
        valueRange = 13f..28f,
        steps = 14
    )
}

@Composable
private fun FontChoices(prefs: ReaderPreferences, change: (ReaderPreferences) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        readingFonts.forEach { name ->
            FilterChip(
                selected = prefs.fontFamily == name,
                onClick = { change(prefs.copy(fontFamily = name)) },
                label = {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text("Aa", fontFamily = previewFont(name), fontSize = 24.sp)
                        Text(name, fontSize = 12.sp)
                    }
                }
            )
        }
    }
}

@Composable
fun ReaderSettingsPanel(
    prefs: ReaderPreferences,
    change: (ReaderPreferences) -> Unit,
    openContents: () -> Unit,
    openBookmarks: () -> Unit,
    modifier: Modifier = Modifier
) {
    var server by remember(prefs.remoteTtsUrl) { mutableStateOf(prefs.remoteTtsUrl) }
    val size = (prefs.fontScale * 16f).roundToInt().coerceIn(13, 28)
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard("TEMA") {
            readerThemes.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { theme ->
                        val active = prefs.themeSpec().name == theme.name
                        Surface(
                            onClick = { change(prefs.withTheme(theme.name)) },
                            modifier = Modifier.weight(1f).aspectRatio(1.35f),
                            shape = RoundedCornerShape(14.dp),
                            color = color(theme.background),
                            border = androidx.compose.foundation.BorderStroke(if (active) 2.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .35f))
                        ) {}
                    }
                    repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        SettingsCard("TIPOGRAFÍA") {
            readingFonts.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { font ->
                        val active = prefs.fontFamily == font
                        Surface(
                            onClick = { change(prefs.copy(fontFamily = font)) },
                            modifier = Modifier.weight(1f).height(78.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.background,
                            border = androidx.compose.foundation.BorderStroke(if (active) 2.dp else 0.dp, MaterialTheme.colorScheme.primary)
                        ) {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text("Aa", fontFamily = previewFont(font), fontSize = 26.sp)
                                Text(font, maxLines = 1, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Text("Tamaño", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                FilledTonalButton({ change(prefs.copy(fontScale = (size - 1).coerceAtLeast(13) / 16f)) }, enabled = size > 13, shape = RoundedCornerShape(14.dp)) { Text("A−") }
                Text(size.toString(), fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                FilledTonalButton({ change(prefs.copy(fontScale = (size + 1).coerceAtMost(28) / 16f)) }, enabled = size < 28, shape = RoundedCornerShape(14.dp)) { Text("A+") }
            }
            SettingSlider("Interlineado", "%.2f".format(prefs.lineSpacing), prefs.lineSpacing, 1.3f..2.3f, 19) { change(prefs.copy(lineSpacing = it)) }
            SettingSlider("Separación entre párrafos", "%.1f".format(prefs.paragraphSpacing), prefs.paragraphSpacing, 0f..2.5f, 24) { change(prefs.copy(paragraphSpacing = it)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Izquierda", "Justificado").forEach { label ->
                    val selected = (label == "Justificado") == prefs.justifiedText
                    Button(
                        onClick = { change(prefs.copy(justifiedText = label == "Justificado")) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
                    ) { Text(label) }
                }
            }
        }

        SettingsCard("SERVIDOR DE VOZ M5") {
            OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("ws://servidor:8765/ws/tts") })
            Button(
                onClick = { RemoteTtsConfig.normalizeWebsocketUrl(server).takeIf { it.isNotBlank() }?.let { server = it; change(prefs.copy(remoteTtsUrl = it)) } },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)
            ) { Text("Guardar servidor") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .22f))
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun SettingSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, change: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text(valueText, color = MaterialTheme.colorScheme.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
        Slider(value, change, valueRange = range, steps = steps)
    }
}

@Composable
private fun ChoiceRow(values: List<String>, selected: String, choose: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(selected = value == selected, onClick = { choose(value) }, label = { Text(value) })
        }
    }
}
