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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelreader.NovelTheme
import com.novelreader.ReaderSettingsPanel
import com.novelreader.isDark
import com.novelreader.themeSpec
import android.graphics.Color as AndroidColor
import android.content.pm.ActivityInfo
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.ImageButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.navigator.media.tts.AndroidTtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.publication.services.search.search
import kotlinx.coroutines.Job

@OptIn(ExperimentalReadiumApi::class)
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
    private val audioControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(AudioPlaybackService.EXTRA_COMMAND)) {
                AudioPlaybackService.ACTION_PLAY_PAUSE -> toggleAudioFromNotification()
                AudioPlaybackService.ACTION_PREVIOUS -> moveRemoteReader(-1)
                AudioPlaybackService.ACTION_NEXT -> moveRemoteReader(1)
                AudioPlaybackService.ACTION_STOP -> stopTts()
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
    private var ttsSpeed by mutableFloatStateOf(1.4f)
    private var ttsPitch by mutableFloatStateOf(1f)
    private var ttsVoices by mutableStateOf<List<AndroidTtsEngine.Voice>>(emptyList())
    private var probeTts: TextToSpeech? = null
    private var directReaderTts: TextToSpeech? = null
    private var directTtsMode = false
    private var directTtsSpeaking by mutableStateOf(false)
    private var directTtsLocatorKey = ""
    private var directTtsChunks: List<String> = emptyList()
    private var directTtsChunkIndex = 0
    private var remoteTtsMode by mutableStateOf(false)
    private var remoteTtsSpeaking by mutableStateOf(false)
    private var remoteTtsLocatorKey = ""
    private var remotePlayingLocatorKey = ""
    private var remoteTts: RemoteTtsPlaybackManager? = null
    private val remoteTtsLocators = mutableMapOf<String, org.readium.r2.shared.publication.Locator>()
    private val remoteTtsTexts = mutableMapOf<String, String>()
    private var playbackPositionPromptShown = false
    private var audioParagraphSelectionMode = false
    private var selectedAudioStartLocator: Locator? = null
    private var selectedAudioStartPending = false
    private var selectedAudioStartText by mutableStateOf("")
    private var savedAudioResumeLocator: Locator? = null
    private var remoteReconnectAttempts = 0
    private var remoteReconnectJob: Job? = null
    private val ttsJobs = mutableListOf<Job>()
    private var ttsVisualNavigator: DecorableNavigator? = null
    private var sleepTimerMinutes by mutableIntStateOf(0)
    private var sleepTimerRemainingSeconds by mutableLongStateOf(0L)
    private var pendingSleepTimerMinutes by mutableIntStateOf(0)
    private var audioNotificationActive = false
    private var currentBookId: String = ""
    private var currentBookPath: String = ""
    private var bookmarkActive = false
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
        ContextCompat.registerReceiver(
            this,
            audioControlReceiver,
            IntentFilter(AudioPlaybackService.ACTION_COMMAND),
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
        currentBookPath = path
        lifecycleScope.launch {
            try {
                val db = DatabaseProvider.get(this@ReaderActivity)
                val initialLocator = db.readingDao().progress(bookId)?.locatorJson?.let(LocatorStorage::decode)
                savedAudioResumeLocator = db.readingDao()
                    .findBookmarkByKind(bookId, Bookmark.KIND_AUTO_AUDIO)
                    ?.locatorJson
                    ?.let(LocatorStorage::decode)
                val readerPreferences = PreferencesStore(this@ReaderActivity).preferences.first()
                currentPreferences = readerPreferences
                ttsSpeed = readerPreferences.ttsSpeed
                ttsPitch = readerPreferences.ttsPitch
                applyReaderColors(readerPreferences.backgroundColor, readerPreferences.textColor)
                requestedOrientation = if (readerPreferences.devicePortrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                ReadiumPublicationRepository(this@ReaderActivity).open(File(path)).onSuccess { publication ->
                activePublication = publication
                findViewById<TextView>(R.id.reader_title).text = publication.metadata.title ?: "NovelReader"
                findViewById<ImageButton>(R.id.reader_back).setOnClickListener { finish() }
                lateinit var readerNavigator: EpubNavigatorFragment
                 findViewById<ImageButton>(R.id.reader_search).setOnClickListener {
                     showReaderSearchDialog(publication, readerNavigator)
                 }
                findViewById<Button>(R.id.reader_settings).setOnClickListener {
                    showReaderSettings(
                        publication = publication,
                        navigator = readerNavigator,
                        openContents = { showTableOfContents(publication, publication.tableOfContents, readerNavigator) },
                        openBookmarks = { showBookmarksDialog(readerNavigator) }
                    )
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
                installAudioParagraphTapSelector(navigator)
                findViewById<ImageButton>(R.id.reader_audio).setOnClickListener {
                    lifecycleScope.launch {
                        if (getOrCreateTts(publication, navigator) != null) {
                            toggleAudioFromNotification()
                        }
                    }
                }
                (navigator as? OverflowableNavigator)?.addInputListener(DirectionalNavigationAdapter(navigator, animatedTransition = true))
                findViewById<ImageButton>(R.id.bookmark_button).setOnClickListener {
                    addCurrentBookmark(navigator)
                }
                findViewById<Button>(R.id.toc_button).setOnClickListener {
                    showTableOfContents(publication, publication.tableOfContents, navigator)
                }
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        navigator.currentLocator.collect { locator ->
                            val locatorJson = LocatorStorage.encode(locator)
                            db.readingDao().saveProgress(ReadingProgress(bookId, locatorJson, locator.locations.totalProgression))
                            val isMarked = db.readingDao().findBookmark(bookId, locatorJson, Bookmark.KIND_READING) != null
                            bookmarkActive = isMarked
                            updateBookmarkIcon(isMarked)
                            val percent = ((locator.locations.totalProgression ?: 0.0) * 100).toInt().coerceIn(0, 100)
                            progressPercent = percent
                            findViewById<android.widget.ProgressBar>(R.id.reader_progress).progress = percent
                            findViewById<TextView>(R.id.reader_progress_label).text = "$percent%"
                            findViewById<TextView>(R.id.reader_status).text = "Voz M5 · %.2f×".format(ttsSpeed)
                            updateReaderScrollIndicator(locator.locations.progression ?: locator.locations.totalProgression ?: 0.0)
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
                val centerSize = root.width * 0.56f
                val centerLeft = (root.width - centerSize) / 2f
                val centerRight = centerLeft + centerSize
                val centerTop = readingTop + (readingHeight - centerSize) / 2f
                val centerBottom = centerTop + centerSize
                val inCenter = event.x in centerLeft..centerRight && event.y in centerTop..centerBottom
                val shouldOpenFromCenter = activeSheet == null && !audioParagraphSelectionMode && inReadingArea && inCenter
                val isLongPress = event.eventTime - event.downTime >= android.view.ViewConfiguration.getLongPressTimeout()
                val audioIsActive = directTtsSpeaking || ttsPlaying
                if (moved < android.view.ViewConfiguration.get(this).scaledTouchSlop && isLongPress && inReadingArea && activeSheet == null && !audioIsActive && !audioParagraphSelectionMode) {
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

    private fun installAudioParagraphTapSelector(navigator: EpubNavigatorFragment) {
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                if (!audioParagraphSelectionMode) return false
                lifecycleScope.launch {
                    selectAudioParagraphAt(navigator, event.point.x, event.point.y)
                }
                return true
            }
        })
    }

    private suspend fun selectAudioParagraphAt(
        navigator: EpubNavigatorFragment,
        x: Float,
        y: Float
    ) {
        val script = """
            (() => {
              let node = document.elementFromPoint(${x}, ${y});
              if (!node) return false;
              const selector = 'p, li, blockquote, h1, h2, h3, h4, h5, h6, td, figcaption';
              node = node.closest(selector) || node;
              if (!node.textContent || !node.textContent.trim()) return false;
              const range = document.createRange();
              range.selectNodeContents(node);
              const selection = window.getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              return true;
            })();
        """.trimIndent()
        navigator.evaluateJavascript(script)
        delay(40L)
        val locator = runCatching { navigator.currentSelection()?.locator }.getOrNull()
        navigator.clearSelection()
        if (locator == null) {
            Toast.makeText(this, "No se pudo identificar ese párrafo. Toca sobre el texto.", Toast.LENGTH_SHORT).show()
            return
        }
        selectAudioStartParagraph(locator)
    }

    private fun updateReaderScrollIndicator(progression: Double) {
        val track = findViewById<View>(R.id.reader_scroll_indicator)
        val thumb = findViewById<View>(R.id.reader_scroll_thumb)
        track.post {
            val travel = (track.height - thumb.height).coerceAtLeast(0)
            thumb.translationY = (travel * progression.coerceIn(0.0, 1.0)).toFloat()
            track.animate().cancel()
            track.visibility = View.VISIBLE
            track.alpha = 1f
            track.removeCallbacks(hideScrollIndicator)
            track.postDelayed(hideScrollIndicator, 1100L)
        }
    }

    private val hideScrollIndicator = Runnable {
        findViewById<View>(R.id.reader_scroll_indicator)
            ?.animate()
            ?.alpha(0f)
            ?.setDuration(250L)
            ?.withEndAction {
                findViewById<View>(R.id.reader_scroll_indicator)?.visibility = View.INVISIBLE
            }
            ?.start()
    }

    private fun addCurrentBookmark(navigator: EpubNavigatorFragment? = activeNavigator) {
        val target = navigator ?: return
        val locator = target.currentLocator.value
        val locatorJson = LocatorStorage.encode(locator)
        val label = ttsUtterance.takeIf { it.isNotBlank() }?.let { utterance ->
            "Párrafo · ${utterance.replace(Regex("\\s+"), " ").trim().take(72)}"
        } ?: "Párrafo · $progressPercent% leído"
        lifecycleScope.launch {
            val dao = DatabaseProvider.get(this@ReaderActivity).readingDao()
            val existing = dao.findBookmark(currentBookId, locatorJson, Bookmark.KIND_READING)
            if (existing != null) {
                dao.deleteBookmark(existing.id)
                bookmarkActive = false
                updateBookmarkIcon(false)
                Toast.makeText(this@ReaderActivity, "Marca quitada", Toast.LENGTH_SHORT).show()
            } else {
                dao.addBookmark(
                    Bookmark(
                        bookId = currentBookId,
                        locatorJson = locatorJson,
                        label = label,
                        kind = Bookmark.KIND_READING
                    )
                )
                bookmarkActive = true
                updateBookmarkIcon(true)
                Toast.makeText(this@ReaderActivity, "Párrafo marcado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addAudioBookmark() {
        val locator = ttsNavigator?.location?.value?.utteranceLocator ?: activeNavigator?.currentLocator?.value ?: return
        val label = ttsUtterance.takeIf { it.isNotBlank() }?.let { utterance ->
            "Audio · ${utterance.replace(Regex("\\s+"), " ").trim().take(72)}"
        } ?: "Audio · $progressPercent% leído"
        lifecycleScope.launch {
            DatabaseProvider.get(this@ReaderActivity).readingDao().addBookmark(
                Bookmark(
                    bookId = currentBookId,
                    locatorJson = LocatorStorage.encode(locator),
                    label = label,
                    kind = Bookmark.KIND_AUDIO
                )
            )
            Toast.makeText(this@ReaderActivity, "Posición de audio guardada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBookmarksDialog(navigator: EpubNavigatorFragment) {
        if (activeSheet != null || sheetTransitionPending) return
        val publication = activePublication ?: return
        lifecycleScope.launch {
            val bookmarks = DatabaseProvider.get(this@ReaderActivity).readingDao().bookmarks(currentBookId).first()
            activeSheetHeightFraction = .72f
            activeSheetDraggable = true
            val dialog = BottomSheetDialog(this@ReaderActivity, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_BottomSheetDialog)
            activeSheet = dialog
            val compose = ComposeView(this@ReaderActivity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    NovelTheme(currentPreferences) {
                        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)) {
                            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                                Text("━", Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
                                ReaderSheetTabs(
                                    selected = 3,
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
                                    openContents = {
                                        pendingSheetAction = { showTableOfContents(publication, publication.tableOfContents, navigator) }
                                        dialog.dismiss()
                                    },
                                    openBookmarks = {}
                                )
                                HorizontalDivider()
                                if (bookmarks.isEmpty()) {
                                    Column(
                                        Modifier.fillMaxSize().padding(28.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Outlined.BookmarkBorder, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.height(12.dp))
                                        Text("Aún no tienes marcas", style = MaterialTheme.typography.titleMedium)
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "Toca el marcador para guardar un párrafo.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                                        items(bookmarks) { bookmark ->
                                            val type = when (bookmark.kind) {
                                                Bookmark.KIND_AUTO_AUDIO -> "Autoguardado de audio"
                                                Bookmark.KIND_AUDIO -> "Audio"
                                                else -> "Lectura"
                                            }
                                            val label = bookmark.label?.ifBlank { type } ?: type
                                            Row(
                                                Modifier.fillMaxWidth().clickable {
                                                    LocatorStorage.decode(bookmark.locatorJson)?.let { locator ->
                                                        lifecycleScope.launch { navigator.go(locator) }
                                                    }
                                                    dialog.dismiss()
                                                    setReaderChromeVisible(false)
                                                }.padding(horizontal = 20.dp, vertical = 14.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(if (bookmark.kind == Bookmark.KIND_AUDIO || bookmark.kind == Bookmark.KIND_AUTO_AUDIO) "▶" else "♦", color = MaterialTheme.colorScheme.primary)
                                                Spacer(Modifier.width(14.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(label, maxLines = 2)
                                                    Text(type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            HorizontalDivider(Modifier.padding(start = 54.dp))
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
    }

    private fun showAudioBookmarksDialog(navigator: AndroidTtsNavigator, publication: Publication) {
        lifecycleScope.launch {
            val bookmarks = DatabaseProvider.get(this@ReaderActivity).readingDao().bookmarks(currentBookId).first()
                .filter { it.kind == Bookmark.KIND_AUDIO || it.kind == Bookmark.KIND_AUTO_AUDIO }
            if (bookmarks.isEmpty()) {
                Toast.makeText(this@ReaderActivity, "Aún no hay posiciones de audio guardadas", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = bookmarks.mapIndexed { index, bookmark ->
                bookmark.label?.ifBlank { "Audio ${index + 1}" } ?: "Audio ${index + 1}"
            }.toTypedArray()
            AlertDialog.Builder(this@ReaderActivity)
                .setTitle("Iniciar audio desde")
                .setItems(labels) { _, index ->
                    bookmarks.getOrNull(index)?.let { bookmark ->
                        LocatorStorage.decode(bookmark.locatorJson)?.let { locator ->
                            lifecycleScope.launch {
                                playbackPositionPromptShown = true
                                beginAudioNotification()
                                if (remoteTtsMode) {
                                    startRemoteReaderTtsFromLocator(navigator, locator)
                                } else if (directTtsMode) {
                                    navigator.go(locator)
                                    startDirectReaderTts(navigator)
                                } else {
                                    navigator.go(locator)
                                    navigator.submitPreferences(ttsPreferences())
                                    navigator.play()
                                }
                                startSleepTimer(sleepTimerMinutes)
                            }
                        }
                    }
                }
                .setPositiveButton("Cerrar", null)
                .show()
        }
    }

    private fun showReaderSearchDialog(publication: Publication, navigator: EpubNavigatorFragment) {
        val theme = currentPreferences.themeSpec()
        val backgroundColor = AndroidColor.parseColor(theme.surface)
        val textColor = AndroidColor.parseColor(theme.text)
        val accentColor = AndroidColor.parseColor(theme.accent)
        val input = EditText(this).apply {
            hint = "Buscar una palabra o frase"
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(textColor)
            setHintTextColor(textColor.withAlpha(150))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(backgroundColor)
                setStroke(dp(1), accentColor.withAlpha(120))
            }
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            setBackgroundColor(backgroundColor)
            addView(input, LinearLayout.LayoutParams(-1, dp(52)))
        }
        val dialog = AlertDialog.Builder(this, readerDialogTheme())
            .setTitle("Buscar en la novela")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Buscar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isBlank()) {
                    input.error = "Escribe algo para buscar"
                    return@setOnClickListener
                }
                dialog.dismiss()
                searchPublication(publication, navigator, query)
            }
        }
        dialog.show()
        styleReaderDialog(dialog)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
    }

    private fun searchPublication(publication: Publication, navigator: EpubNavigatorFragment, query: String) {
        lifecycleScope.launch {
            val iterator = runCatching {
                publication.search(
                    query,
                    SearchService.Options(caseSensitive = false, diacriticSensitive = false, wholeWord = false)
                )
            }.getOrNull()
            if (iterator == null) {
                Toast.makeText(this@ReaderActivity, "Este EPUB no permite búsquedas", Toast.LENGTH_LONG).show()
                return@launch
            }
            val results = mutableListOf<Locator>()
            var failureMessage: String? = null
            try {
                while (results.size < 100) {
                    val page = iterator.next()
                    val collection = page.getOrNull()
                    if (collection == null) {
                        failureMessage = page.failureOrNull()?.toString()
                        break
                    }
                    results += collection.locators
                }
            } finally {
                iterator.close()
            }
            if (failureMessage != null && results.isEmpty()) {
                Toast.makeText(this@ReaderActivity, "No se pudo buscar en este EPUB", Toast.LENGTH_LONG).show()
            } else {
                showSearchResultsDialog(navigator, query, results)
            }
        }
    }

    private fun showSearchResultsDialog(navigator: EpubNavigatorFragment, query: String, results: List<Locator>) {
        if (results.isEmpty()) {
            Toast.makeText(this, "No se encontraron coincidencias para «$query»", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = results.mapIndexed { index, locator ->
            val context = listOfNotNull(locator.text.before, locator.text.highlight, locator.text.after)
                .joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(180)
            "${index + 1}. ${locator.title?.takeIf { it.isNotBlank() } ?: "Resultado"}\n$context"
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this, readerDialogTheme())
            .setTitle("Resultados: $query")
            .setItems(labels) { _, index ->
                results.getOrNull(index)?.let { locator ->
                    lifecycleScope.launch {
                        navigator.go(locator)
                        setReaderChromeVisible(false)
                    }
                }
            }
            .setPositiveButton("Cerrar", null)
            .create()
        dialog.setOnShowListener { styleReaderDialog(dialog) }
        dialog.show()
    }

    private fun readerDialogTheme(): Int = if (currentPreferences.isDark()) {
        android.R.style.Theme_Material_Dialog_Alert
    } else {
        android.R.style.Theme_Material_Light_Dialog_Alert
    }

    private fun styleReaderDialog(dialog: AlertDialog) {
        val theme = currentPreferences.themeSpec()
        val surface = AndroidColor.parseColor(theme.surface)
        val text = AndroidColor.parseColor(theme.text)
        val accent = AndroidColor.parseColor(theme.accent)
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(surface)
            setStroke(dp(1), text.withAlpha(35))
        })
        dialog.findViewById<TextView>(android.R.id.title)?.setTextColor(text)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(text.withAlpha(200))
        dialog.listView?.setBackgroundColor(surface)
    }

    private fun Int.withAlpha(alpha: Int): Int = AndroidColor.argb(
        alpha.coerceIn(0, 255), AndroidColor.red(this), AndroidColor.green(this), AndroidColor.blue(this)
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun saveCurrentProgress() {
        val navigator = activeNavigator ?: return
        val locator = navigator.currentLocator.value
        lifecycleScope.launch {
            DatabaseProvider.get(this@ReaderActivity).readingDao().saveProgress(
                ReadingProgress(currentBookId, LocatorStorage.encode(locator), locator.locations.totalProgression)
            )
        }
    }

    private fun saveAudioProgress(locator: Locator, spokenText: String? = null) {
        if (currentBookId.isBlank()) return
        val audioLocator = spokenText?.takeIf { it.isNotBlank() }?.let { text ->
            locator.copy(text = locator.text.copy(highlight = text))
        } ?: locator
        val locatorJson = LocatorStorage.encode(audioLocator)
        savedAudioResumeLocator = audioLocator
        val progression = audioLocator.locations.totalProgression
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dao = DatabaseProvider.get(this@ReaderActivity).readingDao()
            dao.saveProgress(ReadingProgress(currentBookId, locatorJson, progression))
            val savedAt = System.currentTimeMillis()
            val autoBookmark = dao.findBookmarkByKind(currentBookId, Bookmark.KIND_AUTO_AUDIO)
            if (autoBookmark == null) {
                dao.addBookmark(
                    Bookmark(
                        bookId = currentBookId,
                        locatorJson = locatorJson,
                        label = "Autoguardado",
                        kind = Bookmark.KIND_AUTO_AUDIO,
                        createdAt = savedAt
                    )
                )
            } else {
                dao.updateBookmark(
                    autoBookmark.copy(locatorJson = locatorJson, label = "Autoguardado", createdAt = savedAt)
                )
            }
        }
    }

    override fun onPause() {
        remoteTtsLocators[remotePlayingLocatorKey]?.let { locator ->
            saveAudioProgress(locator, remoteTtsTexts[remotePlayingLocatorKey])
        } ?: saveCurrentProgress()
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
        readerChromeVisible = visible
        findViewById<View>(R.id.reader_top_bar)?.visibility = if (visible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.reader_bottom_panel)?.visibility = if (visible) View.VISIBLE else View.GONE
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
        val theme = currentPreferences.themeSpec()
        val surfaceColor = runCatching { AndroidColor.parseColor(theme.surface) }.getOrDefault(backgroundColor)
        val accentColor = runCatching { AndroidColor.parseColor(theme.accent) }.getOrDefault(AndroidColor.rgb(224, 179, 106))
        val translucentSurface = (surfaceColor and 0x00FFFFFF) or (0xD9 shl 24)
        findViewById<View>(R.id.reader_bottom_panel).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 41f * resources.displayMetrics.density
            setColor(translucentSurface)
            setStroke((1f * resources.displayMetrics.density).roundToInt(), (textColor and 0x00FFFFFF) or (0x24 shl 24))
        }
        listOf(R.id.toc_button, R.id.reader_bookmarks)
            .forEach { findViewById<Button>(it).setTextColor(textColor) }
        findViewById<Button>(R.id.reader_settings).apply {
            setTextColor(textColor)
            backgroundTintList = null
            this.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((textColor and 0x00FFFFFF) or (0x18 shl 24))
            }
        }
        findViewById<android.widget.ProgressBar>(R.id.reader_progress).apply {
            progressTintList = ColorStateList.valueOf(accentColor)
            progressBackgroundTintList = ColorStateList.valueOf((textColor and 0x00FFFFFF) or (0x24 shl 24))
        }
        updateMiniPlayerButton()
        findViewById<ImageButton>(R.id.reader_back).setColorFilter(textColor)
        findViewById<ImageButton>(R.id.reader_search).setColorFilter(textColor)
        updateBookmarkIcon(bookmarkActive)
        findViewById<TextView>(R.id.reader_title).setTextColor(textColor)
        findViewById<TextView>(R.id.reader_progress_label).setTextColor(textColor)
        findViewById<TextView>(R.id.reader_status).setTextColor(textColor)
    }

    private fun updateBookmarkIcon(active: Boolean) {
        val theme = currentPreferences.themeSpec()
        val color = runCatching {
            AndroidColor.parseColor(if (active) theme.accent else theme.text)
        }.getOrDefault(if (active) AndroidColor.rgb(224, 179, 106) else AndroidColor.WHITE)
        findViewById<ImageButton>(R.id.bookmark_button).apply {
            setImageResource(if (active) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline)
            setColorFilter(color)
            background = null
        }
    }

    private fun updateMiniPlayerButton() {
        val playing = remoteTtsSpeaking || directTtsSpeaking || ttsPlaying
        val theme = currentPreferences.themeSpec()
        val accent = runCatching { AndroidColor.parseColor(theme.accent) }.getOrDefault(AndroidColor.rgb(224, 179, 106))
        val iconColor = runCatching { AndroidColor.parseColor(theme.background) }.getOrDefault(AndroidColor.rgb(36, 26, 8))
        findViewById<ImageButton>(R.id.reader_audio).apply {
            setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            setColorFilter(iconColor)
            contentDescription = if (playing) "Pausar lectura" else "Reproducir lectura"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
        }
    }

    private fun epubPreferences(value: ReaderPreferences) = EpubPreferences(
        fontFamily = when (value.fontFamily) {
            "Sans" -> FontFamily.SANS_SERIF
            "Mono" -> FontFamily.MONOSPACE
            else -> FontFamily.SERIF
        },
        // Readium 3.2 recibe un multiplicador: 1.0 = 100 %, 0.75 = 75 %.
        // Internamente lo convierte a WebView textZoom multiplicándolo por 100.
        fontSize = value.fontScale.coerceIn(13f / 16f, 28f / 16f).toDouble(),
        textColor = ReadiumColor(runCatching { AndroidColor.parseColor(value.textColor) }.getOrDefault(AndroidColor.WHITE)),
        backgroundColor = ReadiumColor(runCatching { AndroidColor.parseColor(value.backgroundColor) }.getOrDefault(AndroidColor.DKGRAY)),
        lineHeight = value.lineSpacing.coerceIn(1.3f, 2.3f).toDouble(),
        paragraphSpacing = value.paragraphSpacing.coerceIn(0f, 2.5f).toDouble(),
        textAlign = if (value.justifiedText) TextAlign.JUSTIFY else TextAlign.LEFT,
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
            Triple(Icons.Outlined.TextFields, "Lectura", openSettings),
            Triple(Icons.Outlined.Headphones, "Audio", openAudio),
            Triple(Icons.Outlined.List, "Índice", openContents),
            Triple(Icons.Outlined.BookmarkBorder, "Marcas", openBookmarks)
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            actions.forEachIndexed { index, (icon, label, action) ->
                TextButton(onClick = action, modifier = Modifier.weight(1f), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (index == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)) {
                        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                            Text("━", Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally).padding(top = 4.dp))
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
                            ReaderSettingsPanel(currentPreferences, { value ->
                                val previous = currentPreferences
                                currentPreferences = value
                                applyReaderPreferences(navigator, value)
                                applyReaderColors(value.backgroundColor, value.textColor)
                                preferenceWrites.trySend(value)
                                if (previous.devicePortrait != value.devicePortrait) {
                                    requestedOrientation = if (value.devicePortrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                }
                            }, openContents, openBookmarks, modifier = Modifier.weight(1f))
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
                    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)) {
                        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                            Text("━", Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
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
                                    Spacer(Modifier.height(18.dp))
                                    Button(onClick = { dialog.dismiss(); setReaderChromeVisible(false) }) {
                                        Text("Seguir leyendo")
                                    }
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
                    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)) {
                        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                            Text("━", Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
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
                                             Button(
                                                 onClick = {
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
                                                 modifier = Modifier.fillMaxWidth()
                                             ) { Text("Configurar servidor") }
                                         }
                                    }
                                }
                                val audioIsPlaying = if (remoteTtsMode) remoteTtsSpeaking else if (directTtsMode) directTtsSpeaking else ttsPlaying
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilledTonalIconButton(
                                        onClick = { moveRemoteReader(-1) },
                                        enabled = remoteTtsMode,
                                        modifier = Modifier.size(58.dp)
                                    ) { Icon(Icons.Filled.SkipPrevious, "Párrafo anterior", Modifier.size(28.dp)) }
                                    FilledIconButton(
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
                                        enabled = remoteTtsMode,
                                        modifier = Modifier.size(80.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                                    ) { Icon(if (audioIsPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (audioIsPlaying) "Pausar" else "Reproducir", Modifier.size(38.dp)) }
                                    FilledTonalIconButton(
                                        onClick = { moveRemoteReader(1) },
                                        enabled = remoteTtsMode,
                                        modifier = Modifier.size(58.dp)
                                    ) { Icon(Icons.Filled.SkipNext, "Párrafo siguiente", Modifier.size(28.dp)) }
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text("Inicio de la lectura", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            if (selectedAudioStartText.isBlank()) {
                                                "Si no eliges uno, la lectura comenzará desde la posición actual."
                                            } else {
                                                "Párrafo elegido: «${selectedAudioStartText.take(120)}${if (selectedAudioStartText.length > 120) "…" else ""}»"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Button(
                                            onClick = {
                                                pauseAudioForParagraphSelection()
                                                audioParagraphSelectionMode = true
                                                pendingSheetAction = {
                                                    Toast.makeText(
                                                        this@ReaderActivity,
                                                        "Toca el párrafo desde el que quieres comenzar",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                                dialog.dismiss()
                                            },
                                            enabled = remoteTtsMode,
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("Elegir párrafo inicial") }
                                    }
                                }
                                Text("Velocidad · ${formatTtsValue(ttsSpeed)}×", style = MaterialTheme.typography.titleSmall)
                                Slider(
                                    value = ttsSpeed.coerceIn(.5f, 2f),
                                    onValueChange = { value ->
                                        val stepped = (value * 20f).roundToInt() / 20f
                                        ttsSpeed = stepped
                                        currentPreferences = currentPreferences.copy(ttsSpeed = stepped)
                                        preferenceWrites.trySend(currentPreferences)
                                        applyTtsPreferences()
                                    },
                                    onValueChangeFinished = {
                                        if (remoteTtsMode && remoteTtsSpeaking) restartRemoteReaderTts()
                                    },
                                    valueRange = .5f..2f,
                                    steps = 29,
                                    enabled = remoteTtsMode
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
                                                // La elección del temporizador es una acción
                                                // independiente del estado transitorio de Readium.
                                                // Con M5, ttsPlaying puede ser false durante la
                                                // precarga aunque el audio continúe reproduciéndose.
                                                if (minutes == 0) stopSleepTimer()
                                                else startSleepTimer(minutes)
                                            },
                                            label = { Text(if (minutes == 0) "Sin límite" else "$minutes min", maxLines = 1, softWrap = false, fontSize = 12.sp) },
                                            modifier = Modifier.height(36.dp)
                                        )
                                    }
                                }
                                 Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                     OutlinedButton(
                                         onClick = { ttsNavigator?.let { showAudioBookmarksDialog(it, publication) } },
                                         modifier = Modifier.weight(1f).heightIn(min = 46.dp)
                                     ) { Text("Elegir marcador", maxLines = 1, softWrap = false, fontSize = 12.sp) }
                                     OutlinedButton(
                                         onClick = { addAudioBookmark() },
                                         modifier = Modifier.weight(1f).heightIn(min = 46.dp)
                                     ) { Text("Guardar posición", maxLines = 1, softWrap = false, fontSize = 12.sp) }
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
        playbackPositionPromptShown = true
        playAudioFromLocator(navigator, locator)
        showSystemAudioControls(publication, visualNavigator)
    }

    private fun pauseAudioForParagraphSelection() {
        when {
            remoteTtsSpeaking -> pauseRemoteReaderTts()
            directTtsSpeaking -> pauseDirectReaderTts()
            ttsPlaying -> ttsNavigator?.pause()
        }
        stopSleepTimer()
    }

    private suspend fun selectAudioStartParagraph(locator: Locator) {
        audioParagraphSelectionMode = false
        val publication = activePublication ?: return
        val visualNavigator = activeNavigator ?: return
        val navigator = getOrCreateTts(publication, visualNavigator) ?: return
        val paragraphLocator = resolveTtsParagraphLocator(navigator, locator)
        val paragraphText = paragraphLocator.text.highlight
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

        selectedAudioStartLocator = paragraphLocator
        selectedAudioStartPending = true
        selectedAudioStartText = paragraphText
        (visualNavigator as? DecorableNavigator)?.applyDecorations(
            listOf(
                Decoration(
                    "audio-start",
                    paragraphLocator,
                    Decoration.Style.Highlight(AndroidColor.argb(90, 232, 184, 94))
                )
            ),
            group = "audio-start"
        )
        Toast.makeText(this, "Párrafo inicial seleccionado. Pulsa reproducir.", Toast.LENGTH_LONG).show()
        showSystemAudioControls(publication, visualNavigator)
    }

    private suspend fun resolveTtsParagraphLocator(
        navigator: AndroidTtsNavigator,
        target: Locator
    ): Locator {
        navigator.pause()
        navigator.go(target)
        val selectedText = target.text.highlight
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

        repeat(30) {
            delay(50L)
            val location = navigator.location.value
            val sameResource = location.utteranceLocator.href.toString() == target.href.toString()
            val utterance = location.utterance.replace(Regex("\\s+"), " ").trim()
            val containsSelection = selectedText.isBlank() || utterance.contains(selectedText, ignoreCase = true)
            if (sameResource && utterance.isNotBlank() && containsSelection) {
                return location.utteranceLocator.copy(
                    text = location.utteranceLocator.text.copy(highlight = utterance)
                )
            }
        }
        return target
    }

    private suspend fun playAudioFromLocator(navigator: AndroidTtsNavigator, locator: Locator) {
        beginAudioNotification()
        if (remoteTtsMode) {
            startRemoteReaderTtsFromLocator(navigator, locator)
        } else if (directTtsMode) {
            navigator.go(locator)
            startDirectReaderTts(navigator)
        } else {
            navigator.go(locator)
            navigator.submitPreferences(ttsPreferences())
            navigator.play()
        }
        startSleepTimer(sleepTimerMinutes)
        ttsStatus = "Leyendo desde el párrafo seleccionado"
    }

    private fun requestPlaybackStart(navigator: AndroidTtsNavigator, publication: Publication) {
        fun startFromCurrentPosition() {
            if (remoteTtsMode) {
                val resume = savedAudioResumeLocator
                if (resume != null && !resume.text.highlight.isNullOrBlank()) {
                    lifecycleScope.launch { startRemoteReaderTtsFromLocator(navigator, resume) }
                } else {
                    startRemoteReaderTts(navigator)
                }
            } else if (directTtsMode) {
                startDirectReaderTts(navigator)
            } else {
                navigator.submitPreferences(ttsPreferences())
                navigator.play()
            }
            startSleepTimer(sleepTimerMinutes)
        }

        val selectedStart = selectedAudioStartLocator
        if (selectedAudioStartPending && selectedStart != null) {
            selectedAudioStartPending = false
            playbackPositionPromptShown = true
            lifecycleScope.launch {
                playAudioFromLocator(navigator, selectedStart)
            }
            return
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
            .setNeutralButton("Marcadores") { _, _ ->
                showAudioBookmarksDialog(navigator, publication)
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

    /**
     * TtsNavigator.go(locator) moves its iterator asynchronously but does not
     * immediately publish the new utterance. Read the saved sentence itself
     * first so a stale page-level utterance cannot be spoken instead.
     */
    private suspend fun startRemoteReaderTtsFromLocator(
        navigator: AndroidTtsNavigator,
        target: org.readium.r2.shared.publication.Locator
    ) {
        remoteTtsSpeaking = false
        remoteTtsLocatorKey = ""
        remotePlayingLocatorKey = ""
        remoteTts?.stop()
        navigator.go(target)

        val targetText = target.text.highlight?.trim()?.replace(Regex("\\s+"), " ")
        val textToRead = targetText?.takeIf { it.isNotBlank() }
        if (textToRead == null) {
            ttsStatus = "Este marcador no contiene el texto del párrafo. Reproduce el audio una vez para actualizar el autoguardado."
            Toast.makeText(this, ttsStatus, Toast.LENGTH_LONG).show()
            return
        }

        // The TTS navigator's go(locator) is asynchronous and may keep exposing
        // its previous page-level utterance. Start the saved sentence directly;
        // the navigator has already been asked to seek so it can continue with
        // the following utterance after this one finishes.
        activeNavigator?.go(target, animated = false)
        val key = target.toString()
        remoteTtsLocators[key] = target
        remoteTtsTexts[key] = textToRead
        savedAudioResumeLocator = target.copy(text = target.text.copy(highlight = textToRead))
        remoteTtsSpeaking = true
        ttsPlaying = true
        startRemoteReaderUtterance(textToRead, key)
    }

    private fun toggleAudioFromNotification() {
        val navigator = ttsNavigator ?: return
        when {
            remoteTtsMode -> {
                if (remoteTtsSpeaking) {
                    pauseRemoteReaderTts()
                    stopSleepTimer()
                } else {
                    beginAudioNotification()
                    remoteTtsSpeaking = true
                    ttsPlaying = true
                    val resume = savedAudioResumeLocator
                    if (resume != null && !resume.text.highlight.isNullOrBlank()) {
                        lifecycleScope.launch { startRemoteReaderTtsFromLocator(navigator, resume) }
                    } else {
                        startRemoteReaderTts(navigator)
                    }
                    startSleepTimer(sleepTimerMinutes)
                }
            }
            directTtsMode -> {
                if (directTtsSpeaking) {
                    pauseDirectReaderTts()
                    stopSleepTimer()
                } else {
                    beginAudioNotification()
                    startDirectReaderTts(navigator)
                    startSleepTimer(sleepTimerMinutes)
                }
            }
            else -> {
                beginAudioNotification()
                navigator.submitPreferences(ttsPreferences())
                if (ttsPlaying) navigator.pause() else navigator.play()
                updateAudioNotification()
            }
        }
    }

    private fun beginAudioNotification() {
        audioNotificationActive = true
        updateAudioNotification()
    }

    private fun updateAudioNotification() {
        updateMiniPlayerButton()
        if (!audioNotificationActive) return
        val title = activePublication?.metadata?.title?.takeIf { it.isNotBlank() } ?: "NovelReader"
        val playing = remoteTtsSpeaking || directTtsSpeaking || ttsPlaying
        val chapter = ttsNavigator?.location?.value?.utteranceLocator?.title
            ?: activeNavigator?.currentLocator?.value?.title
            ?: "Posición $progressPercent%"
        val coverPath = File(currentBookPath).parentFile
            ?.listFiles()
            ?.firstOrNull { it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
            ?.absolutePath
        AudioPlaybackService.update(
            context = this,
            title = title,
            text = "$chapter · Voz M5 · ${formatTtsValue(ttsSpeed)}×",
            playing = playing,
            progress = progressPercent,
            bookId = currentBookId,
            bookPath = currentBookPath,
            coverPath = coverPath
        )
    }

    private fun endAudioNotification() {
        audioNotificationActive = false
        updateMiniPlayerButton()
        AudioPlaybackService.stop(this)
    }

    private fun restartRemoteReaderTts() {
        if (!remoteTtsMode || !remoteTtsSpeaking) return
        val navigator = ttsNavigator ?: return
        remoteTts?.stop()
        remoteTtsLocatorKey = ""
        remoteTtsSpeaking = true
        ttsPlaying = true
        val resume = savedAudioResumeLocator
        if (resume != null && !resume.text.highlight.isNullOrBlank()) {
            lifecycleScope.launch { startRemoteReaderTtsFromLocator(navigator, resume) }
        } else {
            startRemoteReaderTts(navigator)
        }
    }

    private fun startRemoteReaderUtterance(text: String, locatorKey: String) {
        if (!remoteTtsMode || text.isBlank() || locatorKey == remoteTtsLocatorKey) return
        val manager = remoteTts ?: return
        beginAudioNotification()
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
                    updateAudioNotification()
                }

                override fun onSegmentStarted(segmentId: Int) = Unit

                override fun onSegmentFinished(segmentId: Int) = Unit

                override fun onUtteranceStarted(utteranceKey: String) {
                    val locator = remoteTtsLocators[utteranceKey] ?: return
                    remotePlayingLocatorKey = utteranceKey
                    remoteReconnectAttempts = 0
                    ttsUtterance = remoteTtsTexts[utteranceKey].orEmpty()
                    saveAudioProgress(locator, remoteTtsTexts[utteranceKey])
                    val navigator = activeNavigator ?: return
                    navigator.go(locator, animated = false)
                    lifecycleScope.launch {
                        ttsVisualNavigator?.applyDecorations(
                            listOf(Decoration("tts-current", locator, Decoration.Style.Highlight(AndroidColor.argb(90, 176, 213, 185)))),
                            group = "tts"
                        )
                    }
                }

                override fun onUtteranceFinished(utteranceKey: String) {
                    val locator = remoteTtsLocators[utteranceKey] ?: return
                    saveAudioProgress(locator, remoteTtsTexts[utteranceKey])
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
                    remotePlayingLocatorKey = ""
                    remoteTtsLocators.clear()
                    remoteTtsTexts.clear()
                    ttsStatus = "Lectura terminada"
                    endAudioNotification()
                }

                override fun onError(message: String) {
                    val resumeKey = remotePlayingLocatorKey
                    val resume = remoteTtsLocators[resumeKey] ?: savedAudioResumeLocator
                    if (resume != null) saveAudioProgress(resume, remoteTtsTexts[resumeKey])
                    remoteTtsSpeaking = false
                    ttsPlaying = false
                    ttsStatus = message
                    updateAudioNotification()
                    scheduleRemoteReconnect(resume, message)
                }
            }
        )
    }

    private fun pauseRemoteReaderTts() {
        remoteTtsLocators[remotePlayingLocatorKey]?.let { locator ->
            saveAudioProgress(locator, remoteTtsTexts[remotePlayingLocatorKey])
        } ?: savedAudioResumeLocator?.let(::saveAudioProgress) ?: saveCurrentProgress()
        remoteReconnectJob?.cancel()
        remoteTtsSpeaking = false
        remoteTtsLocatorKey = ""
        remoteTts?.pause()
        ttsPlaying = false
        ttsStatus = "En pausa"
        updateAudioNotification()
    }

    private fun scheduleRemoteReconnect(locator: Locator?, reason: String) {
        remoteReconnectJob?.cancel()
        if (locator == null || locator.text.highlight.isNullOrBlank() || remoteReconnectAttempts >= 3) {
            ttsStatus = "$reason. Pulsa reproducir para reanudar."
            Toast.makeText(this, ttsStatus, Toast.LENGTH_LONG).show()
            return
        }
        remoteReconnectAttempts += 1
        val attempt = remoteReconnectAttempts
        ttsStatus = "Conexión interrumpida. Reintentando ($attempt/3)…"
        updateAudioNotification()
        remoteReconnectJob = lifecycleScope.launch {
            delay(1500L * attempt)
            val navigator = ttsNavigator ?: return@launch
            beginAudioNotification()
            startRemoteReaderTtsFromLocator(navigator, locator)
        }
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
        beginAudioNotification()
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
                        updateAudioNotification()
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
                        endAudioNotification()
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
        endAudioNotification()
        if (stopNeural) stopSleepTimer()
        ttsJobs.forEach { it.cancel() }
        ttsJobs.clear()
        remoteTts?.close()
        remoteTts = null
        remoteTtsSpeaking = false
        remoteTtsMode = false
        remoteTtsLocatorKey = ""
        remotePlayingLocatorKey = ""
        remoteReconnectJob?.cancel()
        remoteReconnectJob = null
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
        updateMiniPlayerButton()
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
        runCatching { unregisterReceiver(audioControlReceiver) }
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

