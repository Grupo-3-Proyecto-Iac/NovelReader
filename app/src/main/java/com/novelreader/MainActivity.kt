package com.novelreader

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelreader.data.Book
import com.novelreader.data.BookSummary
import com.novelreader.data.DatabaseProvider
import com.novelreader.data.PreferencesStore
import com.novelreader.data.ReaderPreferences
import com.novelreader.epub.EpubMetadataParser
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        setContent { NovelReaderApp(openSettings = intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) }
    }

    companion object { const val EXTRA_OPEN_SETTINGS = "open_settings" }
}

class NovelReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val db = DatabaseProvider.get(app)
    private val dao = db.bookDao()
    private val store = PreferencesStore(app)
    val books = dao.observeSummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val selectedPreferences = kotlinx.coroutines.flow.MutableStateFlow(ReaderPreferences())
    private var pendingPreferences: ReaderPreferences? = null
    val preferences: kotlinx.coroutines.flow.StateFlow<ReaderPreferences> = selectedPreferences
    private val preferenceWrites = kotlinx.coroutines.channels.Channel<ReaderPreferences>(kotlinx.coroutines.channels.Channel.CONFLATED)
    init {
        viewModelScope.launch { store.preferences.collect { value ->
            if (pendingPreferences == null || pendingPreferences == value) {
                selectedPreferences.value = value
                pendingPreferences = null
            }
        } }
        viewModelScope.launch { for (value in preferenceWrites) store.save(value) }
    }
    fun import(uri: Uri) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val file = File(getApplication<Application>().filesDir, "books/${UUID.randomUUID()}/book.epub").apply { parentFile?.mkdirs() }
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use(input::copyTo) } ?: return@withContext
            val sha256 = hash(file)
            if (dao.findByHash(sha256) != null) { file.parentFile?.deleteRecursively(); return@withContext }
            val metadata = runCatching { EpubMetadataParser.read(file) }.getOrElse { file.parentFile?.deleteRecursively(); return@withContext }
            val cover = metadata.coverEntry?.let { entry -> extractCover(file, entry) }
            dao.insert(Book(file.parentFile!!.name, metadata.title, metadata.author, file.absolutePath, cover, sha256))
        }
    }
    fun delete(book: BookSummary) = viewModelScope.launch { dao.delete(book.id); File(book.localPath).parentFile?.deleteRecursively() }
    fun savePreferences(value: ReaderPreferences) { pendingPreferences = value; selectedPreferences.value = value; preferenceWrites.trySend(value) }
    private fun extractCover(epub: File, entry: String): String? = runCatching {
        val cover = File(epub.parentFile, "cover.${entry.substringAfterLast('.', "jpg")}")
        ZipFile(epub).use { zip -> zip.getInputStream(zip.getEntry(entry)).use { input -> cover.outputStream().use(input::copyTo) } }
        cover.absolutePath
    }.getOrNull()
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelReaderApp(openSettings: Boolean = false, vm: NovelReaderViewModel = viewModel()) {
    var screen by remember(openSettings) { mutableStateOf(if (openSettings) "settings" else "library") }
    val context = LocalContext.current
    val books by vm.books.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    NovelTheme(prefs) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Image(
                                painterResource(R.drawable.novelreader_logo),
                                contentDescription = null,
                                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                            )
                            Text("NovelReader", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 23.sp)
                        }
                    },
                    actions = {
                        OutlinedButton(
                            onClick = { vm.savePreferences(prefs.withTheme(if (prefs.isDark()) "Claro" else "Oscuro")) },
                            modifier = Modifier.padding(end = 12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(13.dp)
                        ) { Text(if (prefs.isDark()) "☀ Claro" else "☾ Oscuro", fontSize = 12.sp) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    NavigationBarItem(
                        selected = screen == "library",
                        onClick = { screen = "library" },
                        icon = { Text("▤", fontSize = 21.sp) },
                        label = { Text("Biblioteca", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary, selectedIconColor = MaterialTheme.colorScheme.onPrimary)
                    )
                    NavigationBarItem(
                        selected = screen == "settings",
                        onClick = { screen = "settings" },
                        icon = { Text("⚙", fontSize = 21.sp) },
                        label = { Text("Ajustes", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary, selectedIconColor = MaterialTheme.colorScheme.onPrimary)
                    )
                }
            }
        ) { pad ->
            if (screen == "library") {
                Library(books, Modifier.padding(pad), { picker.launch(arrayOf("application/epub+zip", "application/zip")) }, vm::delete) { book ->
                    context.startActivity(Intent(context, com.novelreader.reader.ReaderActivity::class.java).putExtra(com.novelreader.reader.ReaderActivity.EXTRA_BOOK_ID, book.id).putExtra(com.novelreader.reader.ReaderActivity.EXTRA_BOOK_PATH, book.localPath))
                }
            } else ReadingSettings(prefs, vm::savePreferences, Modifier.padding(pad).fillMaxSize())
        }
    }
}

@Composable
private fun Library(books: List<BookSummary>, modifier: Modifier, import: () -> Unit, delete: (BookSummary) -> Unit, open: (BookSummary) -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Todas") }
    val filtered = books
        .let { source ->
            when (filter) {
                "Leyendo" -> source.filter { (it.totalProgression ?: 0.0) > 0.0 && (it.totalProgression ?: 0.0) < 0.999 }
                "Terminadas" -> source.filter { (it.totalProgression ?: 0.0) >= 0.999 }
                "Recientes" -> source.sortedByDescending { it.importedAt }
                else -> source
            }
        }
        .filter { it.title.contains(query, true) || it.author.contains(query, true) }
    Box(modifier.fillMaxSize()) {
        var pendingDelete by remember { mutableStateOf<BookSummary?>(null) }
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text("Tu biblioteca", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 32.sp, color = MaterialTheme.colorScheme.onBackground)
                Text("${books.size} novelas · disponibles sin conexión", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Buscar novela o autor…") },
                    leadingIcon = { Text("⌕", fontSize = 22.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Todas", "Leyendo", "Terminadas", "Recientes").forEach { name ->
                    FilterChip(
                        selected = filter == name,
                        onClick = { filter = name },
                        label = { Text(name) },
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val resume = books.firstOrNull { (it.totalProgression ?: 0.0) > 0 && (it.totalProgression ?: 0.0) < .999 }
                if (query.isBlank() && filter == "Todas" && resume != null) item {
                    Card(
                        onClick = { open(resume) },
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF214A3D), contentColor = Color(0xFFF8F6E9)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("TU SIGUIENTE CAPÍTULO", fontSize = 10.sp, letterSpacing = 1.6.sp, color = Color(0xFFC7D6BD), fontWeight = FontWeight.Bold)
                            Text(resume.title, fontFamily = FontFamily.Serif, fontSize = 27.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                            Text("${resume.author} · ${((resume.totalProgression ?: 0.0) * 100).toInt()} % leído", fontSize = 12.sp, color = Color(0xFFD0DBCE))
                            LinearProgressIndicator(progress = { (resume.totalProgression ?: 0.0).toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Color(0xFFDBC789), trackColor = Color.White.copy(alpha = .14f))
                            Button(onClick = { open(resume) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8EDCE), contentColor = Color(0xFF193B2E)), shape = RoundedCornerShape(12.dp)) { Text("Continuar leyendo  →", fontWeight = FontWeight.Bold) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (filtered.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("TUS HISTORIAS", fontSize = 10.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        Text("${filtered.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(filtered, key = { it.id }) { book -> BookRow(book, open) { pendingDelete = book } }
                if (filtered.isEmpty()) item {
                    EmptyLibrary(hasBooks = books.isNotEmpty(), clear = { query = ""; filter = "Todas" }, import = import)
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = import,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).semantics { contentDescription = "Importar novela EPUB" }
        ) { Text("＋  Importar EPUB", fontWeight = FontWeight.Bold) }
        pendingDelete?.let { book ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Eliminar de la biblioteca", fontFamily = FontFamily.Serif) },
                text = { Text("Se eliminarán la copia local, el progreso y los marcadores de \"${book.title}\". El archivo EPUB original se conserva.") },
                confirmButton = { TextButton(onClick = { delete(book); pendingDelete = null }) { Text("Eliminar") } },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") } }
            )
        }
    }
}

@Composable
private fun EmptyLibrary(hasBooks: Boolean, clear: () -> Unit, import: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(if (hasBooks) "⌕" else "▤", fontSize = 54.sp, color = MaterialTheme.colorScheme.primary)
        Text(if (hasBooks) "No encontramos novelas" else "Tu próxima historia empieza aquí", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(if (hasBooks) "Prueba otro título, autor o filtro." else "Importa una novela EPUB para leer a tu ritmo, incluso sin conexión.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Button(onClick = if (hasBooks) clear else import, shape = RoundedCornerShape(14.dp)) { Text(if (hasBooks) "Limpiar búsqueda y filtros" else "Importar EPUB") }
    }
}

@Composable
private fun BookRow(book: BookSummary, open: (BookSummary) -> Unit, delete: () -> Unit) {
    val cover by produceState<ImageBitmap?>(null, book.coverPath) {
        value = withContext(Dispatchers.IO) {
            book.coverPath?.let { path ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val options = BitmapFactory.Options().apply { inSampleSize = (bounds.outHeight / 240).coerceAtLeast(1) }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }
        }
    }
    val progress = ((book.totalProgression ?: 0.0) * 100).toInt().coerceIn(0, 100)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { open(book) },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (cover != null) Image(cover!!, "Portada de ${book.title}", Modifier.size(62.dp, 96.dp).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 4.dp)), contentScale = ContentScale.Crop)
            else Surface(Modifier.size(62.dp, 96.dp), color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(topStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 4.dp)) {
                Column(Modifier.padding(7.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                    Text("NOVELREADER", color = Color.White.copy(alpha = .8f), fontFamily = FontFamily.Serif, fontSize = 6.sp)
                    Text(book.title.take(1).uppercase(), color = Color.White, fontFamily = FontFamily.Serif, fontSize = 34.sp)
                    Text("EDICIÓN DIGITAL", color = Color.White.copy(alpha = .8f), fontFamily = FontFamily.Serif, fontSize = 5.sp)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(book.title, color = MaterialTheme.colorScheme.onBackground, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 21.sp, maxLines = 2)
                Text(book.author, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth().height(4.dp), color = MaterialTheme.colorScheme.secondary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                Text(if (progress == 100) "Terminada" else "$progress % leído", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
            TextButton(onClick = delete, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp).semantics { contentDescription = "Opciones de ${book.title}" }) { Text("⋮", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 24.sp) }
        }
    }
}
