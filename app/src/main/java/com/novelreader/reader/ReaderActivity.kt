package com.novelreader.reader

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelreader.NovelTheme
import com.novelreader.ReadingSettings
import com.novelreader.isDark
import android.graphics.Color as AndroidColor
import android.content.pm.ActivityInfo
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import android.util.Log
import android.provider.Settings
import androidx.fragment.app.FragmentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.fragment.app.commitNow
import com.novelreader.R
import com.novelreader.data.DatabaseProvider
import com.novelreader.data.NovelReaderDatabase
import com.novelreader.data.ReadingProgress
import com.novelreader.data.Bookmark
import com.novelreader.data.PreferencesStore
import com.novelreader.data.ReaderPreferences
import com.novelreader.epub.ReadiumPublicationRepository
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.navigator.media.tts.AndroidTtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.r2.shared.publication.Publication
import kotlinx.coroutines.Job

class ReaderActivity : FragmentActivity() {
    private val sleepTimerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SleepTimerService.ACTION_TICK -> {
                    sleepTimerRemainingSeconds = intent.getLongExtra(SleepTimerService.EXTRA_REMAINING_SECONDS, 0L)
                }
                SleepTimerService.ACTION_FINISHED -> {
                    sleepTimerRemainingSeconds = 0L
                    ttsStatus = "Temporizador finalizado"
                    if (remoteTtsSpeaking) {
                        pauseRemoteReaderTts()
                    } else if (directTtsSpeaking) {
                        pauseDirectReaderTts()
                    } else {
                        ttsNavigator?.pause()
                    }
                    saveCurrentProgress()
                    ttsPlaying = false
                }
                SleepTimerService.ACTION_CANCELLED -> sleepTimerRemainingSeconds = 0L
            }
        }
    }
    private var readerChromeVisible = true
    private var currentPreferences by mutableStateOf(ReaderPreferences())
    private var progressPercent by mutableStateOf(0)
    private var activeSheet: BottomSheetDialog? = null
    private var pendingSheetAction: (() -> Unit)? = null
    private var sheetTransitionPending = false
    private var activeSheetHeightFraction = .68f
    private var activeSheetDraggable = true
    private var ttsNavigator: AndroidTtsNavigator? = null
    private var ttsPlaying by mutableStateOf(false)
    private var ttsStatus by mutableStateOf("Listo para leer desde la posición actual")
    private var ttsUtterance by mutableStateOf("")
    private var ttsSpeed by mutableFloatStateOf(1f)
    private var ttsPitch by mutableFloatStateOf(1f)
    private var ttsVoices by mutableStateOf<List<AndroidTtsEngine.Voice>>(emptyList())
    private var probeTts: TextToSpeech? = null
    private var directReaderTts: TextToSpeech? = null
    private var directTtsMode = false
    private var directTtsSpeaking = false
    private var directTtsLocatorKey = ""
    private var directTtsChunks: List<String> = emptyList()
    private var directTtsChunkIndex = 0
    private var remoteTtsMode by mutableStateOf(false)
    private var remoteTtsSpeaking = false
    private var remoteTtsLocatorKey = ""
    private var remoteTts: RemoteTtsPlaybackManager? = null
    private val remoteTtsLocators = mutableMapOf<String, org.readium.r2.shared.publication.Locator>()
    private val remoteTtsTexts = mutableMapOf<String, String>()
    private var playbackPositionPromptShown = false
    private val ttsJobs = mutableListOf<Job>()
    private var ttsVisualNavigator: DecorableNavigator? = null
    private var sleepTimerMinutes by mutableIntStateOf(0)
    private var sleepTimerRemainingSeconds by mutableLongStateOf(0L)
    private var pendingSleepTimerMinutes by mutableIntStateOf(0)
    private var currentBookId: String = ""
    private var activeNavigator: EpubNavigatorFragment? = null
    private var activePublication: Publication? = null
    private val preferenceWrites = kotlinx.coroutines.channels.Channel<ReaderPreferences>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var touchDownX = 0f
    private var touchDownY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        // Restore the EPUB from its locator after installing Readium's fragment factory.
        super.onCreate(null)
        ContextCompat.registerReceiver(
            this,
            sleepTimerReceiver,
            IntentFilter().apply {
                addAction(SleepTimerService.ACTION_TICK)
                addAction(SleepTimerService.ACTION_FINISHED)
                addAction(SleepTimerService.ACTION_CANCELLED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        lifecycleScope.launch {
            val store = PreferencesStore(this@ReaderActivity)
            for (value in preferenceWrites) store.save(value)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        setContentView(R.layout.activity_reader)
        configureSystemInsets()
        setReaderChromeVisible(true)
        val path = intent.getStringExtra(EXTRA_BOOK_PATH) ?: run { finish(); return }
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { finish(); return }
        currentBookId = bookId
        lifecycleScope.launch {
            try {
                val db = DatabaseProvider.get(this@ReaderActivity)
                val initialLocator = db.readingDao().progress(bookId)?.locatorJson?.let(LocatorStorage::decode)
                val readerPreferences = PreferencesStore(this@ReaderActivity).preferences.first()
                currentPreferences = readerPreferences
                ttsSpeed = readerPreferences.ttsSpeed
                ttsPitch = readerPreferences.ttsPitch
                applyReaderColors(readerPreferences.backgroundColor, readerPreferences.textColor)
                requestedOrientation = if (readerPreferences.devicePortrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                ReadiumPublicationRepository(this@ReaderActivity).open(File(path)).onSuccess { publication ->
                activePublication = publication
                findViewById<TextView>(R.id.reader_title).text = publication.metadata.title ?: "NovelReader"
                findViewById<Button>(R.id.reader_back).setOnClickListener { finish() }
                lateinit var readerNavigator: EpubNavigatorFragment
                findViewById<Button>(R.id.reader_settings).setOnClickListener {
                    showReaderSettings(
                        publication = publication,
                        navigator = readerNavigator,
                        openContents = { showTableOfContents(publication, publication.tableOfContents, readerNavigator) },
                        openBookmarks = { showBookmarksDialog(readerNavigator) }
                    )
                }
                findViewById<Button>(R.id.reader_audio).setOnClickListener {
                    showSystemAudioControls(publication, readerNavigator)
                }
                findViewById<Button>(R.id.reader_bookmarks).setOnClickListener {
                    lifecycleScope.launch {
                        val bookmarks = db.readingDao().bookmarks(bookId).first()
                        val labels = if (bookmarks.isEmpty()) arrayOf("Aún no hay marcadores") else bookmarks.mapIndexed { index, bookmark -> "Marcador ${index + 1} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(bookmark.createdAt))}" }.toTypedArray()
                        AlertDialog.Builder(this@ReaderActivity)
                            .setTitle("Marcadores")
                            .setItems(labels) { _, index ->
                                bookmarks.getOrNull(index)?.let { bookmark ->
                                    LocatorStorage.decode(bookmark.locatorJson)?.let { locator ->
                                        lifecycleScope.launch { readerNavigator.go(locator) }
                                    }
                                }
                            }
                            .setPositiveButton("Cerrar", null)
                            .show()
                    }
                }
                val preferences = epubPreferences(readerPreferences)
                val factory = EpubNavigatorFactory(publication)
                val navigatorConfiguration = EpubNavigatorFragment.Configuration().apply {
                    // Si es true, Readium conserva exclusivamente el font-size del
                    // CSS del EPUB e ignora EpubPreferences.fontSize. Para que A+
                    // y el control deslizante funcionen, debe estar desactivado.
                    useReadiumCssFontSize = false
                }
                supportFragmentManager.fragmentFactory = factory.createFragmentFactory(
                    initialLocator = initialLocator,
                    initialPreferences = preferences,
                    configuration = navigatorConfiguration
                )
                supportFragmentManager.commitNow { replace(R.id.reader_container, EpubNavigatorFragment::class.java, Bundle()) }
                val navigator = supportFragmentManager.findFragmentById(R.id.reader_container) as? EpubNavigatorFragment ?: return@onSuccess
                readerNavigator = navigator
                activeNavigator = navigator
                applyReaderPreferences(navigator, readerPreferences)
                (navigator as? OverflowableNavigator)?.addInputListener(DirectionalNavigationAdapter(navigator, animatedTransition = true))
                findViewById<Button>(R.id.bookmark_button).setOnClickListener {
                    addCurrentBookmark(navigator)
                }
                findViewById<Button>(R.id.toc_button).setOnClickListener {
                    showTableOfContents(publication, publication.tableOfContents, navigator)
                }
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        navigator.currentLocator.collect { locator ->
                            db.readingDao().saveProgress(ReadingProgress(bookId, LocatorStorage.encode(locator), locator.locations.totalProgression))
                            val percent = ((locator.locations.totalProgression ?: 0.0) * 100).toInt().coerceIn(0, 100)
                            progressPercent = percent
                            findViewById<android.widget.ProgressBar>(R.id.reader_progress).progress = percent
                            findViewById<TextView>(R.id.reader_progress_label).text = "$percent%"
                            findViewById<TextView>(R.id.reader_status).text = "Progreso guardado"
                        }
                    }
                }
                }.onFailure { error -> showReaderError(error) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showReaderError(error)
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val moved = kotlin.math.hypot(event.x - touchDownX, event.y - touchDownY)
                val top = findViewById<View>(R.id.reader_top_bar)
                val bottom = findViewById<View>(R.id.reader_bottom_panel)
                val root = findViewById<View>(R.id.reader_root)
                val readingTop = root.paddingTop + top.height
                val readingBottom = root.height - root.paddingBottom
                val readingHeight = (readingBottom - readingTop).coerceAtLeast(1)
                val inReadingArea = event.y > readingTop && event.y < readingBottom
                // Zona deliberadamente pequeña: solo el rectángulo central (44% x 44%).
                val centerLeft = root.width * 0.30f
                val centerRight = root.width * 0.70f
                val centerTop = readingTop + readingHeight * 0.34f
                val centerBottom = readingTop + readingHeight * 0.66f
                val inCenter = event.x in centerLeft..centerRight && event.y in centerTop..centerBottom
                val shouldOpenFromCenter = activeSheet == null && inReadingArea && inCenter
                val isLongPress = event.eventTime - event.downTime >= android.view.ViewConfiguration.getLongPressTimeout()
                val audioIsActive = directTtsSpeaking || ttsPlaying
                if (moved < android.view.ViewConfiguration.get(this).scaledTouchSlop && isLongPress && inReadingArea && activeSheet == null && !audioIsActive) {
                    val navigator = activeNavigator
                    super.dispatchTouchEvent(event)
                    lifecycleScope.launch {
                        delay(120L)
                        val locator = try {
                            (navigator as? SelectableNavigator)?.currentSelection()?.locator
                                ?: navigator?.firstVisibleElementLocator()
                        } catch (_: Throwable) {
                            null
                        }
                        if (locator != null) {
                            (navigator as? SelectableNavigator)?.clearSelection()
                            startAudioFromLocator(locator)
                        } else {
                            Toast.makeText(this@ReaderActivity, "No se pudo identificar ese párrafo", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return true
                }
                if (moved < android.view.ViewConfiguration.get(this).scaledTouchSlop && !isLongPress && shouldOpenFromCenter) {
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    setReaderChromeVisible(true)
                    findViewById<Button>(R.id.reader_settings)?.performClick()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun addCurrentBookmark(navigator: EpubNavigatorFragment? = activeNavigator) {
        val target = navigator ?: return
        val locator = target.currentLocator.value
        val label = ttsUtterance.takeIf { it.isNotBlank() }?.let { utterance ->
            "Párrafo · ${utterance.replace(Regex("\\s+"), " ").trim().take(72)}"
        } ?: "Párrafo · $progressPercent% leído"
        lifecycleScope.launch {
            DatabaseProvider.get(this@ReaderActivity).readingDao().addBookmark(
                Bookmark(
                    bookId = currentBookId,
                    locatorJson = LocatorStorage.encode(locator),
                    label = label
                )
            )
            Toast.makeText(this@ReaderActivity, "Párrafo marcado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBookmarksDialog(navigator: EpubNavigatorFragment) {
        lifecycleScope.launch {
            val bookmarks = DatabaseProvider.get(this@ReaderActivity).readingDao().bookmarks(currentBookId).first()
            val labels = if (bookmarks.isEmpty()) {
                arrayOf("Aún no hay marcadores")
            } else {
                bookmarks.mapIndexed { index, bookmark ->
                    bookmark.label?.ifBlank {
                        "Marcador ${index + 1} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(bookmark.createdAt))}"
                    } ?: "Marcador ${index + 1}"
                }.toTypedArray()
            }
            AlertDialog.Builder(this@ReaderActivity)
                .setTitle("Marcadores")
                .setItems(labels) { _, index ->
                    bookmarks.getOrNull(index)?.let { bookmark ->
                        LocatorStorage.decode(bookmark.locatorJson)?.let { locator ->
                            lifecycleScope.launch { navigator.go(locator) }
                        }
                    }
                }
                .setPositiveButton("Cerrar", null)
                .show()
        }
    }

    private fun saveCurrentProgress() {
        val navigator = activeNavigator ?: return
        val locator = navigator.currentLocator.value
        lifecycleScope.launch {
            DatabaseProvider.get(this@ReaderActivity).readingDao().saveProgress(
                ReadingProgress(currentBookId, LocatorStorage.encode(locator), locator.locations.totalProgression)
            )
        }
    }

    override fun onPause() {
        saveCurrentProgress()
        super.onPause()
    }

    private fun configureSystemInsets() {
        val root = findViewById<View>(R.id.reader_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setReaderChromeVisible(visible: Boolean) {
        readerChromeVisible = true
        findViewById<View>(R.id.reader_top_bar)?.visibility = View.VISIBLE
        // Los controles del lector viven únicamente en el modal; no mostramos una barra fija inferior.
        findViewById<View>(R.id.reader_bottom_panel)?.visibility = View.GONE
    }

    private fun finishSheet(dismissed: BottomSheetDialog) {
        if (activeSheet !== dismissed) return
        val next = pendingSheetAction
        pendingSheetAction = null
        activeSheet = null
        activeSheetDraggable = true
        sheetTransitionPending = next != null
        setReaderChromeVisible(true)
        if (next != null) {
            window.decorView.postDelayed({
                if (isFinishing || isDestroyed || activeSheet != null) {
                    sheetTransitionPending = false
                    return@postDelayed
                }
                sheetTransitionPending = false
                runCatching { next() }.onFailure {
                    Log.e("NovelReaderSheet", "Error al cambiar de módulo", it)
                    Toast.makeText(this, "No se pudo abrir esa sección del lector", Toast.LENGTH_SHORT).show()
                }
            }, 220L)
        } else {
            sheetTransitionPending = false
        }
    }

    private fun applyReaderColors(background: String, text: String) {
        val backgroundColor = runCatching { AndroidColor.parseColor(background) }.getOrDefault(AndroidColor.rgb(48, 48, 48))
        val textColor = runCatching { AndroidColor.parseColor(text) }.getOrDefault(AndroidColor.WHITE)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !currentPreferences.isDark()
            isAppearanceLightNavigationBars = !currentPreferences.isDark()
        }
        findViewById<View>(R.id.reader_root).setBackgroundColor(backgroundColor)
        findViewById<View>(R.id.reader_top_bar).background = ColorDrawable(backgroundColor)
        findViewById<View>(R.id.reader_bottom_panel).background = ColorDrawable(backgroundColor)
        listOf(R.id.reader_back, R.id.toc_button, R.id.bookmark_button, R.id.reader_settings, R.id.reader_audio, R.id.reader_bookmarks)
            .forEach { findViewById<Button>(it).setTextColor(textColor) }
        findViewById<TextView>(R.id.reader_title).setTextColor(textColor)
        findViewById<TextView>(R.id.reader_progress_label).setTextColor(textColor)
        findViewById<TextView>(R.id.reader_status).setTextColor(textColor)
    }

    private fun epubPreferences(value: ReaderPreferences) = EpubPreferences(
        fontFamily = when (value.fontFamily) {
            "Serif" -> FontFamily.SERIF
            "Monospace" -> FontFamily.MONOSPACE
            "Cursiva" -> FontFamily.CURSIVE
            "Decorativa" -> FontFamily.FANTASY
            "Accessible" -> FontFamily.ACCESSIBLE_DFA
            "Duospace" -> FontFamily.IA_WRITER_DUOSPACE
            "OpenDyslexic" -> FontFamily.OPEN_DYSLEXIC
            else -> FontFamily.SANS_SERIF
        },
        // Readium 3.2 recibe un multiplicador: 1.0 = 100 %, 0.75 = 75 %.
        // Internamente lo convierte a WebView textZoom multiplicándolo por 100.
        fontSize = value.fontScale.coerceIn(.75f, 1.25f).toDouble(),
        textColor = ReadiumColor(runCatching { AndroidColor.parseColor(value.textColor) }.getOrDefault(AndroidColor.WHITE)),
        backgroundColor = ReadiumColor(runCatching { AndroidColor.parseColor(value.backgroundColor) }.getOrDefault(AndroidColor.DKGRAY)),
        lineHeight = value.lineSpacing.toDouble(),
        paragraphSpacing = value.paragraphSpacing.toDouble(),
        scroll = value.verticalReading,
        publisherStyles = false
    )

    private fun applyReaderPreferences(navigator: EpubNavigatorFragment, value: ReaderPreferences) {
        runCatching { navigator.submitPreferences(epubPreferences(value)) }
            .onFailure { error ->
                Toast.makeText(this, "No se pudo aplicar el ajuste: ${error.message ?: "error de Readium"}", Toast.LENGTH_SHORT).show()
            }
    }

    @Composable
    private fun ReaderSheetTabs(
        selected: Int,
        openSettings: () -> Unit,
        openAudio: () -> Unit,
        openContents: () -> Unit,
        openBookmarks: () -> Unit
    ) {
        val actions = listOf(
            "Aa  Ajustes" to openSettings,
            "▶  Audio" to openAudio,
            "☰  Índice" to openContents,
            "◆  Marcas" to openBookmarks
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            actions.forEachIndexed { index, (label, action) ->
                TextButton(onClick = action, shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, fontSize = 11.sp, fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal)
                        Spacer(Modifier.height(3.dp))
                        Box(Modifier.width(34.dp).height(3.dp).background(if (index == selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
    }

    private fun showReaderSettings(
        publication: Publication,
        navigator: EpubNavigatorFragment,
        openContents: () -> Unit,
        openBookmarks: () -> Unit
    ) {
        if (activeSheet != null || sheetTransitionPending) return
        activeSheetHeightFraction = .68f
        // El desplazamiento debe pertenecer al contenido de ajustes, no al modal.
        // Así un gesto vertical no cierra accidentalmente la configuración.
        activeSheetDraggable = false
        val dialog = BottomSheetDialog(this, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_BottomSheetDialog)
        activeSheet = dialog
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NovelTheme(currentPreferences) {
                    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                            Text("━", Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally).padding(top = 4.dp))
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Tu lectura", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = { dialog.dismiss() }) { Text("Listo") }
                            }
                            Text("Lectura · $progressPercent%  ·  Posición guardada", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(progress = { progressPercent / 100f }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.secondary)
                            ReaderSheetTabs(
                                selected = 0,
                                openSettings = {},
                                openAudio = {
                                    pendingSheetAction = { showSystemAudioControls(publication, navigator) }
                                    dialog.dismiss()
                                },
                                openContents = {
                                    pendingSheetAction = openContents
                                    dialog.dismiss()
                                },
                                openBookmarks = {
                                    pendingSheetAction = openBookmarks
                                    dialog.dismiss()
                                }
                            )
                            HorizontalDivider()
                            ReadingSettings(currentPreferences, { value ->
                                val previous = currentPreferences
                                currentPreferences = value
                                applyReaderPreferences(navigator, value)
                                applyReaderColors(value.backgroundColor, value.textColor)
                                preferenceWrites.trySend(value)
                                if (previous.devicePortrait != value.devicePortrait) {
                                    requestedOrientation = if (value.devicePortrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                }
                            }, modifier = Modifier.weight(1f), compact = true)
                        }
                    }
                }
            }
        }
        dialog.setContentView(compose)
        dialog.setOnShowListener { resizeSheet() }
        dialog.setOnDismissListener { finishSheet(dialog) }
        dialog.show()
        dialog.window?.setDimAmount(0f)
    }

    private fun resizeSheet() {
        val sheet = activeSheet?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val root = findViewById<View>(R.id.reader_root)
        val height = ((root.height - root.paddingTop - root.paddingBottom) * activeSheetHeightFraction).roundToInt()
        sheet.layoutParams = sheet.layoutParams.apply { this.height = height }
        sheet.background = ColorDrawable(AndroidColor.TRANSPARENT)
        BottomSheetBehavior.from(sheet).apply {
            isFitToContents = true
            skipCollapsed = true
            isDraggable = activeSheetDraggable
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        findViewById<View>(R.id.reader_root).post { resizeSheet() }
    }

    private fun showTableOfContents(
        publication: Publication,
        links: List<org.readium.r2.shared.publication.Link>,
        navigator: EpubNavigatorFragment
    ) {
        if (activeSheet != null || sheetTransitionPending) return
        activeSheetHeightFraction = .82f
        val entries = flattenWithDepth(links)
        val dialog = BottomSheetDialog(this, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_BottomSheetDialog)
        activeSheet = dialog
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NovelTheme(currentPreferences) {
                    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                            Text("━", Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Índice", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (entries.isEmpty()) "Sin capítulos disponibles" else "${entries.size} secciones",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { dialog.dismiss() }) { Text("Cerrar") }
                            }
                            ReaderSheetTabs(
                                selected = 2,
                                openSettings = {
                                    pendingSheetAction = {
                                        showReaderSettings(
                                            publication,
                                            navigator,
                                            openContents = { showTableOfContents(publication, publication.tableOfContents, navigator) },
                                            openBookmarks = { showBookmarksDialog(navigator) }
                                        )
                                    }
                                    dialog.dismiss()
                                },
                                openAudio = {
                                    pendingSheetAction = { showSystemAudioControls(publication, navigator) }
                                    dialog.dismiss()
                                },
                                openContents = {},
                                openBookmarks = {
                                    pendingSheetAction = { showBookmarksDialog(navigator) }
                                    dialog.dismiss()
                                }
                            )
                            HorizontalDivider()
                            if (entries.isEmpty()) {
                                Column(
                                    Modifier.fillMaxSize().padding(28.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("Este EPUB no incluye un índice navegable.", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Podrás seguir leyendo normalmente, pero el archivo necesita una tabla de contenidos para mostrar capítulos aquí.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                                    items(entries) { entry ->
                                        Row(
                                            Modifier.fillMaxWidth().clickable {
                                                lifecycleScope.launch { navigator.go(entry.link) }
                                                dialog.dismiss()
                                                setReaderChromeVisible(false)
                                            }.padding(start = (20 + entry.depth * 20).dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (entry.depth > 0) Text("›", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(end = 10.dp))
                                            Text(entry.link.title?.takeIf { it.isNotBlank() } ?: "Sección sin título", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                        }
                                        HorizontalDivider(Modifier.padding(start = (20 + entry.depth * 20).dp), color = MaterialTheme.colorScheme.surfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        dialog.setContentView(compose)
        dialog.setOnShowListener { resizeSheet() }
        dialog.setOnDismissListener { finishSheet(dialog) }
        dialog.show()
        dialog.window?.setDimAmount(0f)
    }

    private fun showSystemAudioControls(publication: Publication, visualNavigator: EpubNavigatorFragment) {
        if (activeSheet != null || sheetTransitionPending) return
        activeSheetHeightFraction = .72f
        activeSheetDraggable = false
        val dialog = BottomSheetDialog(this, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_BottomSheetDialog)
        activeSheet = dialog
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NovelTheme(currentPreferences) {
                    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                            Text("━", Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Lectura en voz alta", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text(ttsStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { dialog.dismiss() }) { Text("Ocultar") }
                            }
                            Text("Lectura · $progressPercent%", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(progress = { progressPercent / 100f }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.secondary)
                            ReaderSheetTabs(
                                selected = 1,
                                openSettings = {
                                    pendingSheetAction = {
                                        showReaderSettings(
                                            publication,
                                            visualNavigator,
                                            openContents = { showTableOfContents(publication, publication.tableOfContents, visualNavigator) },
                                            openBookmarks = { showBookmarksDialog(visualNavigator) }
                                        )
                                    }
                                    dialog.dismiss()
                                },
                                openAudio = {},
                                openContents = {
                                    pendingSheetAction = { showTableOfContents(publication, publication.tableOfContents, visualNavigator) }
                                    dialog.dismiss()
                                },
                                openBookmarks = {
                                    pendingSheetAction = { showBookmarksDialog(visualNavigator) }
                                    dialog.dismiss()
                                }
                            )
                            HorizontalDivider()
                            LaunchedEffect(publication) {
                                getOrCreateTts(publication, visualNavigator)
                            }
                            Column(
                                Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                 Text(
                                     if (remoteTtsMode) "Voz M5 · Supertonic" else "Voz M5 no disponible",
                                     style = MaterialTheme.typography.titleMedium,
                                     fontWeight = FontWeight.SemiBold
                                 )
                                 Text(
                                     if (remoteTtsMode) "La voz M5 se genera en el servidor y el teléfono reproduce el audio sin guardar archivos."
                                     else "Configura el servidor TTS remoto para utilizar la voz M5.",
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant
                                 )
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                         Text("Voz seleccionada", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                             "M5 · Supertonic · Español",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                         if (!remoteTtsMode) {
                                             Text(
                                                 "El servidor TTS M5 no está configurado.",
                                                 style = MaterialTheme.typography.bodySmall,
                                                 color = MaterialTheme.colorScheme.error
                                             )
                                         }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { moveRemoteReader(-1) },
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) { Text("Anterior", maxLines = 1, softWrap = false, fontSize = 12.sp) }
                                    Button(
                                        onClick = {
                                            lifecycleScope.launch {
                                                val tts = getOrCreateTts(publication, visualNavigator)
                                                if (tts != null) {
                                                    if (remoteTtsMode) {
                                                        if (remoteTtsSpeaking) {
                                                            pauseRemoteReaderTts()
                                                            stopSleepTimer()
                                                        } else {
                                                            requestPlaybackStart(tts, publication)
                                                        }
                                                    } else if (directTtsMode) {
                                                        if (directTtsSpeaking) {
                                                            pauseDirectReaderTts()
                                                            stopSleepTimer()
                                                        } else {
                                                            requestPlaybackStart(tts, publication)
                                                        }
                                                    } else {
                                                        // Reenvía siempre la configuración actual. Esto evita que una voz
                                                        // antigua guardada en preferencias deje al motor sin voz válida.
                                                        tts.submitPreferences(ttsPreferences())
                                                        if (ttsPlaying) {
                                                            tts.pause()
                                                            saveCurrentProgress()
                                                            stopSleepTimer()
                                                        } else {
                                                            requestPlaybackStart(tts, publication)
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1.35f).heightIn(min = 48.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) { Text(if (if (remoteTtsMode) remoteTtsSpeaking else if (directTtsMode) directTtsSpeaking else ttsPlaying) "Pausar" else "Reproducir", maxLines = 1, softWrap = false, fontSize = 12.sp) }
                                    OutlinedButton(
                                        onClick = { moveRemoteReader(1) },
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) { Text("Siguiente", maxLines = 1, softWrap = false, fontSize = 12.sp) }
                                }
                                Text("Velocidad · ${formatTtsValue(ttsSpeed)}×", style = MaterialTheme.typography.titleSmall)
                                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(.75f, 1f, 1.25f, 1.4f, 1.5f, 2f).forEach { value ->
                                        FilterChip(
                                            selected = kotlin.math.abs(ttsSpeed - value) < .01f,
                                            onClick = {
                                                ttsSpeed = value
                                                currentPreferences = currentPreferences.copy(ttsSpeed = value)
                                                preferenceWrites.trySend(currentPreferences)
                                                applyTtsPreferences()
                                                 if (remoteTtsMode && remoteTtsSpeaking) {
                                                     restartRemoteReaderTts()
                                                 }
                                            },
                                            label = { Text("${formatTtsValue(value)}×", maxLines = 1, softWrap = false, fontSize = 12.sp) },
                                            modifier = Modifier.height(36.dp)
                                        )
                                    }
                                }
                                Text("Tono · ${formatTtsValue(ttsPitch)}", style = MaterialTheme.typography.titleSmall)
                                Slider(
                                    value = ttsPitch,
                                    onValueChange = {
                                        ttsPitch = it
                                        currentPreferences = currentPreferences.copy(ttsPitch = it)
                                        preferenceWrites.trySend(currentPreferences)
                                        applyTtsPreferences()
                                    },
                                    valueRange = .7f..1.3f
                                )
                                Text(
                                    if (sleepTimerRemainingSeconds > 0L) "Temporizador · ${formatRemainingTime(sleepTimerRemainingSeconds)} restantes"
                                    else if (sleepTimerMinutes == 0) "Temporizador · desactivado"
                                    else "Temporizador · $sleepTimerMinutes min",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(0, 15, 30, 45, 60, 90).forEach { minutes ->
                                        FilterChip(
                                            selected = sleepTimerMinutes == minutes,
                                            onClick = {
                                                sleepTimerMinutes = minutes
                                                if (ttsPlaying) startSleepTimer(minutes) else if (minutes == 0) stopSleepTimer()
                                            },
                                            label = { Text(if (minutes == 0) "Sin límite" else "$minutes min", maxLines = 1, softWrap = false, fontSize = 12.sp) },
                                            modifier = Modifier.height(36.dp)
                                        )
                                    }
                                }
                                Button(
                                    onClick = { stopTts() },
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) { Text("Detener lectura") }
                            }
                        }
                    }
                }
            }
        }
        dialog.setContentView(compose)
        dialog.setOnShowListener { resizeSheet() }
        dialog.setOnDismissListener { finishSheet(dialog) }
        dialog.show()
        dialog.window?.setDimAmount(0f)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != SLEEP_TIMER_PERMISSION_REQUEST) return
        val minutes = pendingSleepTimerMinutes
        pendingSleepTimerMinutes = 0
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            SleepTimerService.start(this, minutes)
            sleepTimerRemainingSeconds = minutes * 60L
        } else {
            Toast.makeText(this, "Activa las notificaciones para mostrar el temporizador en segundo plano", Toast.LENGTH_LONG).show()
        }
    }

    private fun startSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            stopSleepTimer()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingSleepTimerMinutes = minutes
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), SLEEP_TIMER_PERMISSION_REQUEST)
            return
        }
        pendingSleepTimerMinutes = 0
        SleepTimerService.start(this, minutes)
        sleepTimerRemainingSeconds = minutes * 60L
    }

    private fun stopSleepTimer() {
        pendingSleepTimerMinutes = 0
        sleepTimerRemainingSeconds = 0L
        SleepTimerService.stop(this)
    }

    private fun formatRemainingTime(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / 3600L
        val minutes = (safe % 3600L) / 60L
        val remaining = safe % 60L
        return if (hours > 0L) {
            "%02d:%02d:%02d".format(hours, minutes, remaining)
        } else {
            "%02d:%02d".format(minutes, remaining)
        }
    }

    private suspend fun getOrCreateTts(publication: Publication, visualNavigator: EpubNavigatorFragment): AndroidTtsNavigator? {
        val configuredRemoteTtsUrl = currentPreferences.remoteTtsUrl.trim()
            .ifBlank { RemoteTtsConfig.websocketUrl }
        if (configuredRemoteTtsUrl.isBlank()) {
            remoteTtsMode = false
            directTtsMode = false
            ttsStatus = "Configura el servidor TTS remoto para usar la voz M5"
            return null
        }
        ttsNavigator?.let { return it }
        return runCatching {
            val factory = AndroidTtsNavigatorFactory(
                application,
                publication,
                // HayaiTTS expone identificadores internos de sherpa-onnx que
                // no siempre son aceptados por la API TextToSpeech al llamar
                // a setVoice(). La voz elegida en HayaiTTS ya es la voz del
                // sistema; dejamos que el motor la seleccione por sí mismo.
                voiceSelector = { _, _ -> null }
            )
                ?: error("Este EPUB no contiene texto compatible con lectura en voz alta.")
            val result = factory.createNavigator(
                listener = object : org.readium.navigator.media.tts.TtsNavigator.Listener {
                    override fun onStopRequested() { runOnUiThread { stopTts() } }
                },
                initialLocator = visualNavigator.currentLocator.value,
                initialPreferences = AndroidTtsPreferences(speed = ttsSpeed.toDouble(), pitch = ttsPitch.toDouble())
            )
            val created = result.getOrNull() ?: error("No se pudo iniciar el motor de voz. Comprueba que Android tenga una voz instalada.")
            created.also { tts ->
                ttsNavigator = tts
                 // La aplicación usa exclusivamente Supertonic M5 en el servidor.
                 // El Android TTS solo se utiliza para que Readium localice y
                 // resalte el texto; nunca se usa como voz de reproducción.
                 remoteTtsMode = true
                 directTtsMode = false
                 remoteTts = RemoteTtsPlaybackManager(configuredRemoteTtsUrl, RemoteTtsConfig.token)
                 ttsVoices = emptyList()
                ttsVisualNavigator = visualNavigator as? DecorableNavigator
                ttsJobs += lifecycleScope.launch {
                    tts.playback.collect { playback ->
                        ttsPlaying = playback.playWhenReady
                        ttsStatus = if (playback.playWhenReady) "Leyendo" else "En pausa"
                    }
                }
                ttsJobs += lifecycleScope.launch {
                    tts.location.collect { location ->
                        if (remoteTtsMode && remoteTtsSpeaking) {
                            // El navegador TTS avanza para permitir el
                            // prefetch, pero el resaltado espera a que el
                            // audio de ese párrafo empiece realmente.
                            val key = location.utteranceLocator.toString()
                            remoteTtsLocators[key] = location.utteranceLocator
                            remoteTtsTexts[key] = location.utterance
                            startRemoteReaderUtterance(location.utterance, location.utteranceLocator.toString())
                        } else {
                            ttsUtterance = location.utterance
                            visualNavigator.go(location.utteranceLocator, animated = false)
                            ttsVisualNavigator?.applyDecorations(
                                listOf(Decoration("tts-current", location.utteranceLocator, Decoration.Style.Highlight(AndroidColor.argb(90, 176, 213, 185)))),
                                group = "tts"
                            )
                            if (directTtsMode && directTtsSpeaking) {
                            speakDirectReaderUtterance(location.utterance, location.utteranceLocator.toString())
                            }
                        }
                    }
                }
            }
        }.onFailure { error ->
            ttsStatus = error.message ?: "No se pudo iniciar la voz del dispositivo"
            Toast.makeText(this, ttsStatus, Toast.LENGTH_LONG).show()
        }.getOrNull()
    }

    private fun applyTtsPreferences() {
        runCatching { ttsNavigator?.submitPreferences(ttsPreferences()) }
            .onFailure { error ->
                ttsStatus = "No se pudo aplicar la configuración de voz"
                Toast.makeText(this, error.message ?: "No se pudo cambiar la voz", Toast.LENGTH_SHORT).show()
            }
    }

    private fun ttsPreferences(): AndroidTtsPreferences {
        return AndroidTtsPreferences(
            speed = ttsSpeed.toDouble(),
            pitch = ttsPitch.toDouble()
        )
    }

    private fun isHayaiTtsSelected(): Boolean {
        val engine = Settings.Secure.getString(contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH).orEmpty()
        return engine == "dev.ahmedmohamed.hayaitts"
    }

    private fun configureDirectReaderTts(engine: TextToSpeech): Boolean {
        engine.setSpeechRate(ttsSpeed)
        engine.setPitch(ttsPitch)
        val spanishReady = setSpanishLanguage(engine)
        val selected = engine.voices?.firstOrNull { it.name == currentPreferences.ttsVoiceId }
            ?: engine.voices?.firstOrNull { it.locale.language == "es" }
            ?: engine.voices?.firstOrNull()
        if (selected != null) {
            engine.voice = selected
            Log.d("NovelReaderTTS", "voz=${selected.name} idioma=${selected.locale} español=$spanishReady")
            // HayaiTTS etiqueta actualmente sus voces Supertonic como "ar"
            // aunque el modelo es multilingüe. No descartamos la voz por ese
            // metadato: intentamos es-ES y dejamos que Hayai use el hablante.
            return true
        }
        return spanishReady
    }

    private fun setSpanishLanguage(engine: TextToSpeech): Boolean {
        val spanishLocales = listOf(
            java.util.Locale("es", "ES"),
            java.util.Locale("es", "MX"),
            java.util.Locale("es")
        )
        return spanishLocales.any { locale ->
            val result = engine.setLanguage(locale)
            result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private suspend fun startAudioFromLocator(locator: org.readium.r2.shared.publication.Locator) {
        val publication = activePublication ?: return
        val visualNavigator = activeNavigator ?: return
        Toast.makeText(this, "Iniciando audio desde ese párrafo", Toast.LENGTH_SHORT).show()
        val navigator = getOrCreateTts(publication, visualNavigator) ?: return
        navigator.go(locator)
        playbackPositionPromptShown = true
        if (remoteTtsMode) {
            startRemoteReaderTts(navigator)
        } else if (directTtsMode) {
            startDirectReaderTts(navigator)
        } else {
            navigator.submitPreferences(ttsPreferences())
            navigator.play()
        }
        startSleepTimer(sleepTimerMinutes)
        ttsStatus = "Leyendo desde el párrafo seleccionado"
        showSystemAudioControls(publication, visualNavigator)
    }

    private fun requestPlaybackStart(navigator: AndroidTtsNavigator, publication: Publication) {
        fun startFromCurrentPosition() {
            if (remoteTtsMode) {
                startRemoteReaderTts(navigator)
            } else if (directTtsMode) {
                startDirectReaderTts(navigator)
            } else {
                navigator.submitPreferences(ttsPreferences())
                navigator.play()
            }
            startSleepTimer(sleepTimerMinutes)
        }

        if (playbackPositionPromptShown || progressPercent <= 0) {
            startFromCurrentPosition()
            return
        }

        playbackPositionPromptShown = true
        AlertDialog.Builder(this)
            .setTitle("Continuar lectura")
            .setMessage("Esta novela tiene un punto guardado en el $progressPercent%. ¿Desde dónde quieres iniciar el audio?")
            .setNegativeButton("Desde el inicio") { _, _ ->
                publication.readingOrder.firstOrNull()?.let { firstLink ->
                    publication.locatorFromLink(firstLink)?.let { locator -> navigator.go(locator) }
                }
                startFromCurrentPosition()
            }
            .setPositiveButton("Punto guardado") { _, _ ->
                startFromCurrentPosition()
            }
            .setOnCancelListener {
                playbackPositionPromptShown = false
            }
            .show()
    }

    private fun startRemoteReaderTts(navigator: AndroidTtsNavigator) {
        val current = navigator.location.value
        val key = current.utteranceLocator.toString()
        remoteTtsLocators[key] = current.utteranceLocator
        remoteTtsTexts[key] = current.utterance
        startRemoteReaderUtterance(current.utterance, key)
    }

    private fun restartRemoteReaderTts() {
        if (!remoteTtsMode || !remoteTtsSpeaking) return
        val navigator = ttsNavigator ?: return
        remoteTts?.stop()
        remoteTtsLocatorKey = ""
        remoteTtsSpeaking = true
        ttsPlaying = true
        startRemoteReaderTts(navigator)
    }

    private fun startRemoteReaderUtterance(text: String, locatorKey: String) {
        if (!remoteTtsMode || text.isBlank() || locatorKey == remoteTtsLocatorKey) return
        val manager = remoteTts ?: return
        remoteTtsLocatorKey = locatorKey
        remoteTtsSpeaking = true
        ttsPlaying = true
        ttsStatus = "Preparando voz remota…"
        if (manager.isRunning()) {
            manager.append(text, locatorKey)
            return
        }

        manager.start(
            text = text,
            speed = ttsSpeed,
            voice = "M5",
            utteranceKey = locatorKey,
            listener = object : RemoteTtsPlaybackManager.Listener {
                override fun onStatus(status: String) {
                    ttsStatus = status
                    ttsPlaying = status != "En pausa" && status != "Detenido"
                }

                override fun onSegmentStarted(segmentId: Int) = Unit

                override fun onSegmentFinished(segmentId: Int) = Unit

                override fun onUtteranceStarted(utteranceKey: String) {
                    val locator = remoteTtsLocators[utteranceKey] ?: return
                    ttsUtterance = remoteTtsTexts[utteranceKey].orEmpty()
                    val navigator = activeNavigator ?: return
                    navigator.go(locator, animated = false)
                    lifecycleScope.launch {
                        ttsVisualNavigator?.applyDecorations(
                            listOf(Decoration("tts-current", locator, Decoration.Style.Highlight(AndroidColor.argb(90, 176, 213, 185)))),
                            group = "tts"
                        )
                    }
                }

                override fun onNeedsNextUtterance() {
                    if (!remoteTtsSpeaking) return
                    val navigator = ttsNavigator ?: return
                    if (navigator.hasNextUtterance()) {
                        navigator.skipToNextUtterance()
                    } else {
                        manager.finishInput()
                    }
                }

                override fun onFinished() {
                    if (!remoteTtsSpeaking) return
                    remoteTtsSpeaking = false
                    ttsPlaying = false
                    remoteTtsLocatorKey = ""
                    remoteTtsLocators.clear()
                    remoteTtsTexts.clear()
                    ttsStatus = "Lectura terminada"
                }

                override fun onError(message: String) {
                    remoteTtsSpeaking = false
                    ttsPlaying = false
                    ttsStatus = message
                    Toast.makeText(this@ReaderActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun pauseRemoteReaderTts() {
        saveCurrentProgress()
        remoteTtsSpeaking = false
        remoteTtsLocatorKey = ""
        remoteTts?.pause()
        ttsPlaying = false
        ttsStatus = "En pausa"
    }

    private fun moveRemoteReader(direction: Int) {
        if (!remoteTtsMode) {
            if (direction < 0) ttsNavigator?.skipToPreviousUtterance()
            else ttsNavigator?.skipToNextUtterance()
            return
        }
        remoteTts?.stop()
        remoteTtsLocatorKey = ""
        remoteTtsSpeaking = true
        ttsPlaying = true
        if (direction < 0) ttsNavigator?.skipToPreviousUtterance()
        else ttsNavigator?.skipToNextUtterance()
        ttsNavigator?.location?.value?.let { location ->
            startRemoteReaderUtterance(location.utterance, location.utteranceLocator.toString())
        }
    }

    private fun startDirectReaderTts(navigator: AndroidTtsNavigator) {
        directTtsSpeaking = true
        ttsPlaying = true
        ttsStatus = "Preparando voz..."
        val current = navigator.location.value
        val existing = directReaderTts
        if (existing != null) {
            if (configureDirectReaderTts(existing)) {
                speakDirectReaderUtterance(current.utterance, current.utteranceLocator.toString())
            } else {
                directTtsSpeaking = false
                ttsPlaying = false
                ttsStatus = "HayaiTTS no tiene una voz instalada compatible"
            }
            return
        }
        directReaderTts = TextToSpeech(this, { result ->
            val engine = directReaderTts
            if (result != TextToSpeech.SUCCESS || engine == null || !configureDirectReaderTts(engine)) {
                directTtsSpeaking = false
                ttsPlaying = false
                ttsStatus = "HayaiTTS no tiene una voz instalada compatible"
                return@TextToSpeech
            }
            speakDirectReaderUtterance(current.utterance, current.utteranceLocator.toString())
        }, Settings.Secure.getString(contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)).also { engine ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread {
                        ttsPlaying = true
                        ttsStatus = "Leyendo"
                    }
                }

                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        if (!directTtsSpeaking) return@runOnUiThread
                        if (directTtsChunkIndex + 1 < directTtsChunks.size) {
                            directTtsChunkIndex += 1
                            speakNextDirectTtsChunk()
                        } else {
                            directTtsChunks = emptyList()
                            directTtsChunkIndex = 0
                            ttsNavigator?.skipToNextUtterance()
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        directTtsSpeaking = false
                        ttsPlaying = false
                        ttsStatus = "HayaiTTS no pudo reproducir este fragmento"
                    }
                }
            })
        }
    }

    private fun speakDirectReaderUtterance(text: String, locatorKey: String) {
        if (!directTtsSpeaking || text.isBlank() || locatorKey == directTtsLocatorKey) return
        directTtsLocatorKey = locatorKey
        directTtsChunks = splitTtsText(text)
        directTtsChunkIndex = 0
        speakNextDirectTtsChunk()
    }

    private fun speakNextDirectTtsChunk() {
        val engine = directReaderTts ?: return
        val chunk = directTtsChunks.getOrNull(directTtsChunkIndex) ?: return
        val queued = engine.speak(
            chunk,
            TextToSpeech.QUEUE_FLUSH,
            spanishSpeechParams(),
            "novelreader-reader-${System.nanoTime()}"
        )
        Log.d(
            "NovelReaderTTS",
            "reader chunk ${directTtsChunkIndex + 1}/${directTtsChunks.size} chars=${chunk.length} result=$queued"
        )
        if (queued != TextToSpeech.SUCCESS) {
            directTtsSpeaking = false
            ttsPlaying = false
            ttsStatus = "HayaiTTS rechazó este fragmento"
        } else {
            ttsPlaying = true
            ttsStatus = "Leyendo"
        }
    }

    /**
     * Algunos motores de terceros truncan entradas largas sin devolver un
     * error. Limitamos cada entrada y conservamos el orden para no perder
     * palabras ni avanzar al siguiente párrafo antes de tiempo.
     */
    private fun splitTtsText(text: String, maxCharacters: Int = 180): List<String> {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= maxCharacters) return listOf(normalized)

        val chunks = mutableListOf<String>()
        var remaining = normalized
        while (remaining.length > maxCharacters) {
            val boundary = remaining.lastIndexOf(' ', maxCharacters)
                .takeIf { it >= maxCharacters / 2 }
                ?: maxCharacters
            chunks += remaining.substring(0, boundary).trim()
            remaining = remaining.substring(boundary).trimStart()
        }
        if (remaining.isNotBlank()) chunks += remaining
        return chunks
    }

    private fun spanishSpeechParams(): Bundle = Bundle().apply {
        // HayaiTTS no expone las constantes antiguas en todos los SDK.
        // Android reconoce estas claves al recibir los parámetros de habla.
        putString("language", "es")
        putString("country", "ES")
    }

    private fun pauseDirectReaderTts() {
        saveCurrentProgress()
        directTtsSpeaking = false
        directTtsChunks = emptyList()
        directTtsChunkIndex = 0
        directReaderTts?.stop()
        ttsPlaying = false
        ttsStatus = "En pausa"
    }

    /**
     * Prueba el motor TTS del sistema sin Readium. Esto evita que una voz de
     * terceros sea descartada por la selección de idioma/voz del navegador.
     */
    private fun testSystemTts() {
        Log.d("NovelReaderTTS", "Inicio de prueba directa")
        probeTts?.stop()
        probeTts?.shutdown()
        ttsStatus = "Probando motor de voz..."
        probeTts = TextToSpeech(this, { result ->
            if (result != TextToSpeech.SUCCESS) {
                ttsStatus = "El motor no pudo inicializarse"
                return@TextToSpeech
            }
            val engine = probeTts ?: return@TextToSpeech
            engine.setSpeechRate(ttsSpeed)
            engine.setPitch(ttsPitch)
            val spanishReady = setSpanishLanguage(engine)
            val installedVoice = engine.voices
                ?.firstOrNull {
                    it.name == currentPreferences.ttsVoiceId
                }
                ?: engine.voices?.firstOrNull { it.locale.language == "es" }
                ?: engine.voices?.firstOrNull()
            if (installedVoice != null) {
                engine.voice = installedVoice
            } else if (!spanishReady) {
                ttsStatus = "El motor no tiene una voz instalada compatible con español"
                return@TextToSpeech
            }
            val queued = engine.speak(
                "Esta es una prueba de voz de NovelReader.",
                TextToSpeech.QUEUE_FLUSH,
                spanishSpeechParams(),
                "novelreader-voice-test"
            )
            Log.d("NovelReaderTTS", "speak result=$queued voice=${installedVoice?.name}")
            ttsStatus = if (queued == TextToSpeech.SUCCESS) "Reproduciendo prueba" else "El motor rechazó el audio"
        }, Settings.Secure.getString(contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH))
    }

    private fun selectTtsVoice(voice: AndroidTtsEngine.Voice) {
        currentPreferences = currentPreferences.copy(ttsVoiceId = voice.id.value)
        preferenceWrites.trySend(currentPreferences)
        if (directTtsMode && directReaderTts != null) {
            val engine = directReaderTts
            if (engine != null && configureDirectReaderTts(engine)) {
                directTtsLocatorKey = ""
                if (directTtsSpeaking) {
                    ttsNavigator?.location?.value?.let { location ->
                        speakDirectReaderUtterance(location.utterance, location.utteranceLocator.toString())
                    }
                }
            }
        } else {
            applyTtsPreferences()
        }
        ttsStatus = "Voz ${speakerLabel(voice, ttsVoices.indexOf(voice))} seleccionada"
    }

    private fun speakerLabel(voice: AndroidTtsEngine.Voice, index: Int): String {
        val match = Regex("speaker[_-]?(\\d+)", RegexOption.IGNORE_CASE).find(voice.id.value)
        return match?.groupValues?.getOrNull(1)?.let { "Voz $it" } ?: "Voz ${index + 1}"
    }

    private fun preferredVoice(
        language: org.readium.r2.shared.util.Language?,
        voices: Set<AndroidTtsEngine.Voice>
    ): AndroidTtsEngine.Voice? {
        val requestedLanguage = language?.removeRegion()
        val matching = if (requestedLanguage == null) {
            voices.toList()
        } else {
            voices.filter { it.language.removeRegion() == requestedLanguage }.ifEmpty { voices.toList() }
        }
        return matching.firstOrNull { it.id.value == currentPreferences.ttsVoiceId }
            ?: orderedVoices(matching.toSet()).firstOrNull()
    }

    private fun orderedVoices(voices: Set<AndroidTtsEngine.Voice>): List<AndroidTtsEngine.Voice> {
        val femaleHints = listOf("female", "femen", "woman", "mujer", "f1", "f2", "f3", "f4", "f5")
        return voices.sortedWith(
            compareBy<AndroidTtsEngine.Voice> {
                val id = it.id.value.lowercase()
                if (femaleHints.any(id::contains)) 0 else 1
            }.thenBy { it.requiresNetwork }.thenByDescending { it.quality.ordinal }.thenBy { it.id.value }
        )
    }

    private fun stopTts(stopNeural: Boolean = true) {
        saveCurrentProgress()
        if (stopNeural) stopSleepTimer()
        ttsJobs.forEach { it.cancel() }
        ttsJobs.clear()
        remoteTts?.close()
        remoteTts = null
        remoteTtsSpeaking = false
        remoteTtsMode = false
        remoteTtsLocatorKey = ""
        remoteTtsLocators.clear()
        remoteTtsTexts.clear()
        ttsNavigator?.close()
        ttsNavigator = null
        directTtsSpeaking = false
        directTtsMode = false
        directTtsLocatorKey = ""
        directTtsChunks = emptyList()
        directTtsChunkIndex = 0
        directReaderTts?.stop()
        directReaderTts?.shutdown()
        directReaderTts = null
        probeTts?.stop()
        probeTts?.shutdown()
        probeTts = null
        ttsVoices = emptyList()
        val decorated = ttsVisualNavigator
        ttsVisualNavigator = null
        lifecycleScope.launch { decorated?.applyDecorations(emptyList(), group = "tts") }
        ttsPlaying = false
        ttsUtterance = ""
        ttsStatus = "Lectura detenida"
    }

    private fun openTtsSettings() {
        Log.w("NovelReaderTTS", "Se solicitó abrir ajustes de TTS")
        // El navegador TTS conserva el motor/voz con el que fue creado. Al volver
        // de los ajustes se recreará con la voz que el usuario haya elegido.
        stopTts(stopNeural = false)
        val settings = Intent("com.android.settings.TTS_SETTINGS")
        if (settings.resolveActivity(packageManager) != null) startActivity(settings)
        else Toast.makeText(this, "Configura la voz desde los ajustes de texto a voz de Android", Toast.LENGTH_LONG).show()
    }

    private fun formatTtsValue(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else "%.2f".format(value).trimEnd('0')

    override fun onDestroy() {
        // El servicio neural continúa aunque la Activity se destruya al
        // apagar la pantalla o al cambiar temporalmente de aplicación.
        stopTts(stopNeural = false)
        runCatching { unregisterReceiver(sleepTimerReceiver) }
        super.onDestroy()
    }

    private fun showReaderError(error: Throwable) {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: "El archivo EPUB no pudo abrirse."
        findViewById<TextView>(R.id.reader_status)?.text = "No se pudo abrir el EPUB"
        AlertDialog.Builder(this)
            .setTitle("No se pudo abrir la novela")
            .setMessage(detail)
            .setPositiveButton("Volver") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private data class ChapterEntry(val link: org.readium.r2.shared.publication.Link, val depth: Int)
    private data class PendingNeuralAudio(
        val text: String,
        val speed: Float,
        val voice: String,
        val timerMinutes: Int
    )

    private fun flattenWithDepth(
        links: List<org.readium.r2.shared.publication.Link>,
        depth: Int = 0
    ): List<ChapterEntry> = links.flatMap { link -> listOf(ChapterEntry(link, depth)) + flattenWithDepth(link.children, depth + 1) }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_BOOK_PATH = "book_path"
        const val SLEEP_TIMER_PERMISSION_REQUEST = 7002
    }
}

