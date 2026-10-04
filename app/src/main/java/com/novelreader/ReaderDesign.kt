package com.novelreader

import android.app.Activity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.novelreader.data.ReaderPreferences
import com.novelreader.reader.RemoteTtsConfig
import kotlin.math.roundToInt

val readingFonts = listOf("Sans Serif", "Serif", "Monospace", "Cursiva", "Decorativa", "Accessible", "Duospace", "OpenDyslexic")
fun previewFont(name: String): FontFamily = when(name) {
    "Serif" -> FontFamily.Serif
    "Monospace" -> FontFamily.Monospace
    "Cursiva" -> FontFamily.Cursive
    "Decorativa" -> FontFamily(android.graphics.Typeface.create("casual", 0))
    "Accessible" -> FontFamily(Font(R.font.accessible))
    "Duospace" -> FontFamily(Font(R.font.duospace))
    "OpenDyslexic" -> FontFamily(Font(R.font.open_dyslexic))
    else -> FontFamily.SansSerif
}
fun ReaderPreferences.isDark() = backgroundColor != "#FFEFEAE0" && backgroundColor != "#FFF5F3EC"
fun ReaderPreferences.withTheme(theme: String) = copy(
    backgroundColor = when(theme) { "Claro" -> "#FFF5F3EC"; "Violeta" -> "#FF211E2B"; else -> "#FF171C1A" },
    textColor = if(theme == "Claro") "#FF252B27" else "#FFF2F3ED"
)

@Composable
fun NovelTheme(prefs: ReaderPreferences, content: @Composable () -> Unit) {
    val dark = prefs.isDark()
    val bg = Color(android.graphics.Color.parseColor(prefs.backgroundColor))
    val scheme = if(dark) darkColorScheme(primary=Color(0xFFB0D5B9), onPrimary=Color(0xFF172E21),
        background=bg, surface=if(prefs.backgroundColor=="#FF211E2B") Color(0xFF302A3C) else Color(0xFF222A26),
        onBackground=Color(0xFFF2F3ED), onSurface=Color(0xFFF2F3ED), onSurfaceVariant=Color(0xFFBEC9BE), secondary=Color(0xFFD3B26C),
        primaryContainer=Color(0xFF2F493C), onPrimaryContainer=Color(0xFFE3F1E3), secondaryContainer=Color(0xFF354A3E), onSecondaryContainer=Color(0xFFE3F1E3), surfaceVariant=Color(0xFF39423C))
    else lightColorScheme(primary=Color(0xFF244C40),onPrimary=Color.White,background=bg,surface=Color(0xFFFFFEF9),
        onBackground=Color(0xFF252B27),onSurface=Color(0xFF252B27),onSurfaceVariant=Color(0xFF62695F),secondary=Color(0xFFA17A2E),
        primaryContainer=Color(0xFFDFE8DB),onPrimaryContainer=Color(0xFF203E31),secondaryContainer=Color(0xFFDFE8DB),onSecondaryContainer=Color(0xFF203E31),surfaceVariant=Color(0xFFE4E5DC))
    val view=LocalView.current
    SideEffect { (view.context as? Activity)?.window?.let { window ->
        WindowCompat.getInsetsController(window,view).apply { isAppearanceLightStatusBars=!dark; isAppearanceLightNavigationBars=!dark }
    } }
    MaterialTheme(colorScheme=scheme,content=content)
}

@Composable
fun ReadingSettings(prefs: ReaderPreferences, change: (ReaderPreferences)->Unit, modifier: Modifier=Modifier, compact: Boolean=false) {
    var remoteTtsInput by remember(prefs.remoteTtsUrl) { mutableStateOf(prefs.remoteTtsUrl) }
    var remoteTtsMessage by remember { mutableStateOf("") }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal=20.dp, vertical=if(compact) 8.dp else 20.dp), verticalArrangement=Arrangement.spacedBy(if(compact) 8.dp else 18.dp)) {
        if(!compact) {
            Text("A tu manera",style=MaterialTheme.typography.headlineMedium,fontFamily=FontFamily.Serif)
            Text("Cambios inmediatos · guardado automático",style=MaterialTheme.typography.bodySmall)
            Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.background) {
                Text("La lluvia se detuvo. Entre las páginas de un libro, Clara encontró el comienzo de otra historia.",
                    Modifier.padding(18.dp),fontFamily=previewFont(prefs.fontFamily),fontSize=(prefs.fontScale*16).sp,
                    lineHeight=(prefs.fontScale*16*prefs.lineSpacing).sp,color=Color(android.graphics.Color.parseColor(prefs.textColor)))
            }
        }
        Text("Servidor de voz M5", style=MaterialTheme.typography.titleSmall)
        Text(
            "Escribe la IP del equipo donde corre el servidor TTS. No tendrás que generar otro APK al cambiar de red.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value=remoteTtsInput,
            onValueChange={remoteTtsInput=it},
            modifier=Modifier.fillMaxWidth(),
            singleLine=true,
            label={Text("IP o URL del servidor")},
            placeholder={Text("192.168.0.40 o tts.midominio.com")},
            supportingText={Text("También puedes usar ws://, wss://, http:// o https://")}
        )
        Button(
            onClick={
                val normalized=RemoteTtsConfig.normalizeWebsocketUrl(remoteTtsInput)
                if(normalized.isBlank()) {
                    remoteTtsMessage="Escribe una IP o URL válida."
                } else {
                    remoteTtsInput=normalized
                    remoteTtsMessage="Dirección guardada. Se usará al abrir la lectura en voz alta."
                    change(prefs.copy(remoteTtsUrl=normalized))
                }
            },
            modifier=Modifier.fillMaxWidth(),
            shape=RoundedCornerShape(14.dp)
        ) { Text("Guardar servidor TTS") }
        if(remoteTtsMessage.isNotBlank()) {
            Text(remoteTtsMessage, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.primary)
        }
        Text("Tema de toda la app",style=MaterialTheme.typography.titleSmall)
        val theme=if(!prefs.isDark()) "Claro" else if(prefs.backgroundColor.contains("211E2B")||prefs.backgroundColor.contains("2A2036")) "Violeta" else "Oscuro"
        ChoiceRow(listOf("Claro","Oscuro","Violeta"),theme) { change(prefs.withTheme(it)) }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Tamaño de lectura")
            OutlinedButton(enabled=prefs.fontScale>0.75f,onClick={change(prefs.copy(fontScale=(prefs.fontScale-1f/16).coerceAtLeast(.75f)))}){Text("A−")}
            Text("${(prefs.fontScale*16).roundToInt()}")
            OutlinedButton(enabled=prefs.fontScale<1.25f,onClick={change(prefs.copy(fontScale=(prefs.fontScale+1f/16).coerceAtMost(1.25f)))}){Text("A+")}
        }
        Slider(value=prefs.fontScale.coerceIn(.75f,1.25f),onValueChange={change(prefs.copy(fontScale=it))},valueRange=.75f..1.25f,steps=7)
        Text("Tipografía",style=MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            readingFonts.forEach { name ->
                FilterChip(selected=prefs.fontFamily==name,onClick={change(prefs.copy(fontFamily=name))},label={
                    Column(Modifier.padding(vertical=6.dp)){ Text("Aa",fontFamily=previewFont(name),fontSize=24.sp);Text(name,fontSize=12.sp) }
                })
            }
        }
        var advanced by remember { mutableStateOf(false) }
        TextButton(onClick={advanced=!advanced}){Text(if(advanced) "Ocultar espaciado y color ↑" else "Espaciado y color ↓")}
        if(advanced) {
            Text("Interlineado · %.1f".format(prefs.lineSpacing))
            Slider(value=prefs.lineSpacing.coerceIn(1f,2.2f),onValueChange={change(prefs.copy(lineSpacing=it))},valueRange=1f..2.2f)
            Text("Entre párrafos · %.1f".format(prefs.paragraphSpacing))
            Slider(value=prefs.paragraphSpacing.coerceIn(0f,2f),onValueChange={change(prefs.copy(paragraphSpacing=it))},valueRange=0f..2f)
            ChoiceRow(listOf("Automático","Blanco","Sepia","Oscuro"),when(prefs.textColor){"#FFFFFFFF"->"Blanco";"#FFDDCFA5"->"Sepia";"#FF252B27"->"Oscuro";else->"Automático"}) {
                change(prefs.copy(textColor=when(it){"Blanco"->"#FFFFFFFF";"Sepia"->"#FFDDCFA5";"Oscuro"->"#FF252B27";else->if(prefs.isDark()) "#FFF2F3ED" else "#FF252B27"}))
            }
        }
        Text("Cómo pasar las páginas",style=MaterialTheme.typography.titleSmall)
        ChoiceRow(listOf("Desplazamiento","Páginas"),if(prefs.verticalReading) "Desplazamiento" else "Páginas") { change(prefs.copy(verticalReading=it=="Desplazamiento")) }
        Text("Orientación",style=MaterialTheme.typography.titleSmall)
        ChoiceRow(listOf("Vertical","Horizontal"),if(prefs.devicePortrait) "Vertical" else "Horizontal") {change(prefs.copy(devicePortrait=it=="Vertical"))}
        Spacer(Modifier.height(8.dp))
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
    var section by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(openContents, Modifier.weight(1f)) { Text("☰  Índice") }
            OutlinedButton(openBookmarks, Modifier.weight(1f)) { Text("🔖  Marcadores") }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Texto", "Tema", "Página").forEachIndexed { index, label ->
                FilterChip(
                    selected = section == index,
                    onClick = { section = index },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        when (section) {
            0 -> ReaderTextSection(prefs, change, Modifier.weight(1f))
            1 -> ReaderThemeSection(prefs, change, Modifier.weight(1f))
            else -> ReaderPageSection(prefs, change, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReaderTextSection(prefs: ReaderPreferences, change: (ReaderPreferences) -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Tamaño", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(enabled = prefs.fontScale > .75f, onClick = { change(prefs.copy(fontScale = (prefs.fontScale - 1f / 16).coerceAtLeast(.75f))) }) { Text("A−") }
            Text("${(prefs.fontScale * 16).roundToInt()} sp", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(enabled = prefs.fontScale < 1.25f, onClick = { change(prefs.copy(fontScale = (prefs.fontScale + 1f / 16).coerceAtMost(1.25f))) }) { Text("A+") }
        }
        Slider(value = prefs.fontScale.coerceIn(.75f, 1.25f), onValueChange = { change(prefs.copy(fontScale = it)) }, valueRange = .75f..1.25f, steps = 7)
        Text("Tipografía", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            readingFonts.forEach { name ->
                FilterChip(selected = prefs.fontFamily == name, onClick = { change(prefs.copy(fontFamily = name)) }, label = {
                    Column(Modifier.padding(vertical = 4.dp)) { Text("Aa", fontFamily = previewFont(name), fontSize = 23.sp); Text(name, fontSize = 11.sp) }
                })
            }
        }
        Text("Los cambios se aplican al libro sin perder tu posición.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReaderThemeSection(prefs: ReaderPreferences, change: (ReaderPreferences) -> Unit, modifier: Modifier) {
    val theme = if (!prefs.isDark()) "Claro" else if (prefs.backgroundColor.contains("211E2B") || prefs.backgroundColor.contains("2A2036")) "Violeta" else "Oscuro"
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Tema de lectura", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(listOf("Claro", "Oscuro", "Violeta"), theme) { change(prefs.withTheme(it)) }
        Text("Color del texto", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(listOf("Automático", "Blanco", "Sepia", "Oscuro"), when (prefs.textColor) { "#FFFFFFFF" -> "Blanco"; "#FFDDCFA5" -> "Sepia"; "#FF252B27" -> "Oscuro"; else -> "Automático" }) {
            change(prefs.copy(textColor = when (it) { "Blanco" -> "#FFFFFFFF"; "Sepia" -> "#FFDDCFA5"; "Oscuro" -> "#FF252B27"; else -> if (prefs.isDark()) "#FFF2F3ED" else "#FF252B27" }))
        }
        Text("El tema también se aplica a la biblioteca y a los demás paneles.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReaderPageSection(prefs: ReaderPreferences, change: (ReaderPreferences) -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Interlineado · %.1f".format(prefs.lineSpacing), style = MaterialTheme.typography.titleSmall)
        Slider(value = prefs.lineSpacing.coerceIn(1f, 2.2f), onValueChange = { change(prefs.copy(lineSpacing = it)) }, valueRange = 1f..2.2f)
        Text("Espacio entre párrafos · %.1f".format(prefs.paragraphSpacing), style = MaterialTheme.typography.titleSmall)
        Slider(value = prefs.paragraphSpacing.coerceIn(0f, 2f), onValueChange = { change(prefs.copy(paragraphSpacing = it)) }, valueRange = 0f..2f)
        Text("Navegación", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(listOf("Desplazamiento", "Páginas"), if (prefs.verticalReading) "Desplazamiento" else "Páginas") { change(prefs.copy(verticalReading = it == "Desplazamiento")) }
        Text("Orientación", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(listOf("Vertical", "Horizontal"), if (prefs.devicePortrait) "Vertical" else "Horizontal") { change(prefs.copy(devicePortrait = it == "Vertical")) }
    }
}
@Composable
private fun ChoiceRow(values: List<String>, selected: String, choose: (String)->Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        values.forEach { value-> FilterChip(selected=value==selected,onClick={choose(value)},label={Text(value)}) }
    }
}
