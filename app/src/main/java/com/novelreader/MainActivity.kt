package com.novelreader

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.novelreader.data.*
import com.novelreader.epub.EpubMetadataParser
import com.novelreader.epub.PdfMetadataParser
import com.novelreader.reader.PdfReaderActivity
import com.novelreader.reader.ReaderActivity
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var externalDocument by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        externalDocument = intent.documentUri()
        setContent {
            NovelReaderApp(
                intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false),
                externalDocument,
                { externalDocument = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalDocument = intent.documentUri()
    }

    companion object { const val EXTRA_OPEN_SETTINGS = "open_settings" }
}

private fun Intent.documentUri(): Uri? = data?.takeIf { action == Intent.ACTION_VIEW }

class NovelReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = DatabaseProvider.get(app).bookDao()
    private val store = PreferencesStore(app)
    private val selectedPreferences = MutableStateFlow(ReaderPreferences())
    private val preferenceWrites = Channel<ReaderPreferences>(Channel.CONFLATED)
    private var pendingPreferences: ReaderPreferences? = null

    val books = dao.observeSummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val preferences: StateFlow<ReaderPreferences> = selectedPreferences

    init {
        viewModelScope.launch {
            store.preferences.collect { value ->
                if (pendingPreferences == null || pendingPreferences == value) {
                    selectedPreferences.value = value
                    pendingPreferences = null
                }
            }
        }
        viewModelScope.launch { for (value in preferenceWrites) store.save(value) }
    }

    fun savePreferences(value: ReaderPreferences) {
        pendingPreferences = value
        selectedPreferences.value = value
        preferenceWrites.trySend(value)
    }

    fun import(uri: Uri) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            val resolver = app.contentResolver
            val originalName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            val mime = resolver.getType(uri).orEmpty()
            val looksPdf = mime.equals("application/pdf", true) || originalName?.endsWith(".pdf", true) == true
            val folder = File(app.filesDir, "books/${UUID.randomUUID()}").apply { mkdirs() }
            var local = File(folder, if (looksPdf) "book.pdf" else "book.epub")
            resolver.openInputStream(uri)?.use { input -> local.outputStream().use(input::copyTo) }
                ?: run { folder.deleteRecursively(); return@withContext }
            val isPdf = looksPdf || local.inputStream().use { input ->
                val signature = ByteArray(4)
                input.read(signature) == 4 && signature.contentEquals(byteArrayOf(37, 80, 68, 70))
            }
            if (isPdf && local.extension != "pdf") {
                val pdf = File(folder, "book.pdf")
                local.renameTo(pdf)
                local = pdf
            }
            val sha = hash(local)
            if (dao.findByHash(sha) != null) { folder.deleteRecursively(); return@withContext }
            val title: String
            val author: String
            val cover: String?
            if (isPdf) {
                title = originalName?.substringBeforeLast('.', originalName).orEmpty().ifBlank { PdfMetadataParser.title(local) }
                author = ""
                cover = PdfMetadataParser.renderCover(local, File(folder, "cover.jpg"))
            } else {
                val metadata = runCatching { EpubMetadataParser.read(local) }.getOrElse {
                    folder.deleteRecursively(); return@withContext
                }
                title = metadata.title.ifBlank { originalName?.substringBeforeLast('.') ?: "Novela" }
                author = metadata.author
                cover = metadata.coverEntry?.let { extractCover(local, it) }
            }
            dao.insert(Book(folder.name, title, author, local.absolutePath, cover, sha))
        }
    }

    fun delete(book: BookSummary) = viewModelScope.launch {
        dao.delete(book.id)
        withContext(Dispatchers.IO) { File(book.localPath).parentFile?.deleteRecursively() }
    }

    private fun extractCover(epub: File, entry: String): String? = runCatching {
        val cover = File(epub.parentFile, "cover.${entry.substringAfterLast('.', "jpg")}")
        ZipFile(epub).use { zip ->
            val item = zip.getEntry(entry) ?: return@runCatching null
            zip.getInputStream(item).use { input -> cover.outputStream().use(input::copyTo) }
        }
        cover.absolutePath
    }.getOrNull()

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private enum class RootScreen { LIBRARY, SETTINGS }

@Composable
private fun NovelReaderApp(
    openSettings: Boolean,
    externalDocument: Uri?,
    consumeExternalDocument: () -> Unit,
    vm: NovelReaderViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    var screen by remember(openSettings) { mutableStateOf(if (openSettings) RootScreen.SETTINGS else RootScreen.LIBRARY) }
    val books by vm.books.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    val importBook = { picker.launch(arrayOf("application/epub+zip", "application/pdf", "application/zip")) }

    LaunchedEffect(externalDocument) {
        externalDocument?.let { vm.import(it); consumeExternalDocument() }
    }

    NovelTheme(prefs) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .92f), tonalElevation = 0.dp) {
                    NavigationBarItem(
                        selected = screen == RootScreen.LIBRARY,
                        onClick = { screen = RootScreen.LIBRARY },
                        icon = { Icon(Icons.Outlined.LibraryBooks, null) },
                        label = { Text("Biblioteca") },
                        colors = navColors()
                    )
                    NavigationBarItem(
                        selected = screen == RootScreen.SETTINGS,
                        onClick = { screen = RootScreen.SETTINGS },
                        icon = { Icon(Icons.Outlined.Settings, null) },
                        label = { Text("Ajustes") },
                        colors = navColors()
                    )
                }
            }
        ) { padding ->
            when (screen) {
                RootScreen.LIBRARY -> LibraryScreen(
                    books, Modifier.padding(padding), importBook, vm::delete
                ) { book ->
                    val activity = if (book.localPath.endsWith(".pdf", true)) PdfReaderActivity::class.java else ReaderActivity::class.java
                    context.startActivity(
                        Intent(context, activity)
                            .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                            .putExtra(ReaderActivity.EXTRA_BOOK_PATH, book.localPath)
                    )
                }
                RootScreen.SETTINGS -> ReadingSettings(prefs, vm::savePreferences, Modifier.padding(padding).fillMaxSize())
            }
        }
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primary,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
private fun LibraryScreen(
    books: List<BookSummary>, modifier: Modifier, importBook: () -> Unit,
    deleteBook: (BookSummary) -> Unit, openBook: (BookSummary) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Todas") }
    var pendingDelete by remember { mutableStateOf<BookSummary?>(null) }
    val filtered = books.filter { book ->
        val progress = book.totalProgression ?: 0.0
        val inFilter = when (filter) {
            "Leyendo" -> progress > 0.0 && progress < .999
            "Terminadas" -> progress >= .999
            else -> true
        }
        inFilter && (book.title.contains(query, true) || book.author.contains(query, true))
    }
    val resume = books.filter { (it.totalProgression ?: 0.0) in 0.0001..0.9989 }.maxByOrNull { it.importedAt }

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(142.dp), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 112.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Tu biblioteca", fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${books.size} ${if (books.size == 1) "novela" else "novelas"} · sin conexión",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
                    )
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = if (query.isNotEmpty()) {{ IconButton({ query = "" }) { Icon(Icons.Outlined.Close, "Limpiar") } }} else null,
                        placeholder = { Text("Buscar novela o autor…") }, singleLine = true,
                        shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)
                    )
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Todas", "Leyendo", "Terminadas").forEach { label ->
                            FilterChip(
                                selected = filter == label, onClick = { filter = label }, label = { Text(label) },
                                shape = RoundedCornerShape(50),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }
            }
            if (query.isBlank() && filter == "Todas" && resume != null) {
                item(span = { GridItemSpan(maxLineSpan) }) { ContinueCard(resume) { openBook(resume) } }
            }
            if (filtered.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("TUS HISTORIAS", fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(filtered.size.toString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(filtered, key = { it.id }) { book -> BookCard(book, { openBook(book) }, { pendingDelete = book }) }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyLibrary(books.isNotEmpty(), { query = ""; filter = "Todas" }, importBook)
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = importBook, icon = { Icon(Icons.Outlined.Add, null) },
            text = { Text("Importar EPUB o PDF", fontWeight = FontWeight.Bold) },
            shape = RoundedCornerShape(50), containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        )
    }
    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Eliminar novela", fontFamily = FontFamily.Serif) },
            text = { Text("Se eliminarán la copia local, el progreso y las marcas de “${book.title}”.") },
            confirmButton = { TextButton({ deleteBook(book); pendingDelete = null }) { Text("Eliminar") } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun ContinueCard(book: BookSummary, open: () -> Unit) {
    val cover by rememberCover(book.coverPath)
    val progress = (book.totalProgression ?: 0.0).toFloat().coerceIn(0f, 1f)
    Card(onClick = open, shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(book, cover, Modifier.width(74.dp).aspectRatio(.67f))
            Column(Modifier.weight(1f)) {
                Text("CONTINUAR LEYENDO", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                Text(book.title, fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                Text("${(progress * 100).toInt()} % leído", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp), color = MaterialTheme.colorScheme.primary)
                Button(open, Modifier.padding(top = 10.dp).fillMaxWidth(), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("Continuar →", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun BookCard(book: BookSummary, open: () -> Unit, delete: () -> Unit) {
    val cover by rememberCover(book.coverPath)
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(onClick = open)) {
        Box {
            Cover(book, cover, Modifier.fillMaxWidth().aspectRatio(.67f))
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .9f), shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                IconButton({ menu = true }, Modifier.size(36.dp)) { Icon(Icons.Outlined.MoreVert, "Más opciones") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Eliminar") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { menu = false; delete() })
                }
            }
        }
        Text(book.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(book.author.ifBlank { "Autor desconocido" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        val progress = (book.totalProgression ?: 0.0).toFloat().coerceIn(0f, 1f)
        LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(top = 7.dp).height(3.dp), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun EmptyLibrary(hasBooks: Boolean, clear: () -> Unit, importBook: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if (hasBooks) Icons.Outlined.Search else Icons.Outlined.AutoStories, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            if (hasBooks) "No encontramos novelas" else "Tu próxima historia empieza aquí",
            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp)
        )
        Text(
            if (hasBooks) "Prueba otro título, autor o filtro." else "Importa un EPUB o PDF para leer a tu ritmo, incluso sin conexión.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        Button(if (hasBooks) clear else importBook, Modifier.padding(top = 18.dp), shape = RoundedCornerShape(13.dp)) {
            Text(if (hasBooks) "Limpiar búsqueda y filtros" else "Importar EPUB o PDF")
        }
    }
}

@Composable
private fun rememberCover(path: String?): State<ImageBitmap?> = produceState(null, path) {
    value = withContext(Dispatchers.IO) {
        path?.takeIf { File(it).isFile }?.let {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(it, bounds)
            BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = (bounds.outHeight / 720).coerceAtLeast(1) })?.asImageBitmap()
        }
    }
}

@Composable
private fun Cover(book: BookSummary, cover: ImageBitmap?, modifier: Modifier) {
    val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 4.dp)
    if (cover != null) {
        Image(cover, "Portada de ${book.title}", modifier.clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.clip(shape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text(book.title.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, fontFamily = FontFamily.Serif, fontSize = 42.sp, fontWeight = FontWeight.Bold)
        }
    }
}
