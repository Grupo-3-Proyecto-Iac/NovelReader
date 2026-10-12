package com.novelreader.reader

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.novelreader.BuildConfig
import com.novelreader.R
import com.novelreader.data.DatabaseProvider
import com.novelreader.data.PreferencesStore
import com.novelreader.data.ReaderPreferences
import com.novelreader.data.ReadingProgress
import com.novelreader.epub.PdfMetadataParser
import com.novelreader.readerThemes
import com.novelreader.isDark
import com.novelreader.themeSpec
import com.novelreader.withTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Lector PDF offline con narración remota M5 para documentos con capa de texto. */
class PdfReaderActivity : FragmentActivity() {
    private var renderer: PdfRenderer? = null
    private var textDocument: PDDocument? = null
    private var pageIndex = 0
    private var playing = false
    private var textMode = false
    private var renderJob: Job? = null
    private var currentBitmap: Bitmap? = null
    private var tts: RemoteTtsPlaybackManager? = null
    private var preferences = ReaderPreferences()
    private var bookId = ""
    private lateinit var pdfFile: File
    private lateinit var pageImage: ImageView
    private lateinit var pageFrame: FrameLayout
    private lateinit var pageText: TextView
    private lateinit var pageTextScroll: ScrollView
    private lateinit var textModeButton: Button
    private lateinit var readerOptionsButton: Button
    private lateinit var pageLabel: TextView
    private lateinit var previousButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var playButton: ImageButton
    private lateinit var audioStatus: TextView
    private lateinit var audioProgress: ProgressBar
    private lateinit var speedButton: Button
    private lateinit var miniPlayer: LinearLayout

    private val audioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(AudioPlaybackService.EXTRA_COMMAND)) {
                AudioPlaybackService.ACTION_PLAY_PAUSE -> toggleAudio()
                AudioPlaybackService.ACTION_PREVIOUS -> movePage(-1, restartAudio = true)
                AudioPlaybackService.ACTION_NEXT -> movePage(1, restartAudio = true)
                AudioPlaybackService.ACTION_STOP -> stopAudio()
            }
        }
    }
    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SleepTimerService.ACTION_FINISHED) pauseAudio()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(ReaderActivity.EXTRA_BOOK_PATH)
        pdfFile = path?.let(::File) ?: File("")
        bookId = intent.getStringExtra(ReaderActivity.EXTRA_BOOK_ID).orEmpty()
        if (!pdfFile.isFile) {
            Toast.makeText(this, "No se encontró el PDF", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        renderer = runCatching { PdfMetadataParser.open(pdfFile) }.getOrElse {
            Toast.makeText(this, "No se pudo abrir el PDF: ${it.message.orEmpty()}", Toast.LENGTH_LONG).show()
            finish(); return
        }
        PDFBoxResourceLoader.init(applicationContext)
        buildView()
        registerControls()
        requestNotificationPermission()
        lifecycleScope.launch {
            preferences = PreferencesStore(this@PdfReaderActivity).preferences.first()
            pageIndex = restorePage().coerceIn(0, ((renderer?.pageCount ?: 1) - 1).coerceAtLeast(0))
            textDocument = withContext(Dispatchers.IO) { runCatching { PDDocument.load(pdfFile) }.getOrNull() }
            textMode = extractPageText(pageIndex).isNotBlank()
            pageTextScroll.visibility = if (textMode) View.VISIBLE else View.GONE
            pageImage.visibility = if (textMode) View.GONE else View.VISIBLE
            textModeButton.text = if (textMode) "Página" else "Texto"
            speedButton.text = "${formatSpeed(preferences.ttsSpeed)}×"
            applyPdfTheme()
            renderPage()
        }
    }

    private fun buildView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 24, 26))
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.rgb(20, 24, 26))
        }
        val back = iconButton(R.drawable.ic_arrow_back, "Volver") { finish() }
        pageLabel = TextView(this).apply {
            setTextColor(Color.rgb(241, 239, 232)); textSize = 14f; gravity = Gravity.CENTER
        }
        readerOptionsButton = Button(this).apply {
            text = "Aa"; contentDescription = "Ajustes de lectura PDF"
            setOnClickListener { showReadingOptions() }
        }
        textModeButton = Button(this).apply {
            text = "Texto"; contentDescription = "Cambiar entre página PDF y texto adaptable"
            setOnClickListener { toggleTextMode() }
        }
        previousButton = iconButton(android.R.drawable.ic_media_previous, "Página anterior") { movePage(-1) }
        nextButton = iconButton(android.R.drawable.ic_media_next, "Página siguiente") { movePage(1) }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(pageLabel, LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(textModeButton, LinearLayout.LayoutParams(dp(62), dp(42)))
        toolbar.addView(readerOptionsButton, LinearLayout.LayoutParams(dp(48), dp(42)))
        toolbar.addView(previousButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(nextButton, LinearLayout.LayoutParams(dp(48), dp(48)))

        pageImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(238, 238, 235))
            contentDescription = "Página del PDF"
        }
        pageText = TextView(this).apply {
            setPadding(dp(26), dp(24), dp(26), dp(36))
            textSize = 18f
            setTextColor(Color.rgb(30, 30, 30))
            setLineSpacing(dp(5).toFloat(), 1.25f)
            setTextIsSelectable(true)
        }
        pageTextScroll = ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = true
            addView(pageText, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        pageFrame = FrameLayout(this).apply {
            addView(pageImage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(pageTextScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                when {
                    event.x < pageImage.width * .30f -> movePage(-1)
                    event.x > pageImage.width * .70f -> movePage(1)
                    else -> return false
                }
                return true
            }
            override fun onFling(start: MotionEvent?, end: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val first = start ?: return false
                val distance = end.x - first.x
                if (kotlin.math.abs(distance) < dp(48) || kotlin.math.abs(velocityX) < 250f) return false
                movePage(if (distance < 0) 1 else -1)
                return true
            }
        })
        pageImage.setOnTouchListener { view, event ->
            val handled = gestures.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !handled) view.performClick()
            handled
        }
        miniPlayer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(Color.argb(225, 30, 36, 39), 40f, Color.argb(55, 164, 174, 176))
        }
        playButton = iconButton(R.drawable.ic_play, "Reproducir PDF") { toggleAudio() }.apply {
            background = circle(Color.rgb(224, 179, 106))
            setColorFilter(Color.rgb(36, 26, 8))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val audioInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        audioStatus = TextView(this).apply {
            text = "Voz M5 · PDF"; setTextColor(Color.WHITE); textSize = 14f
        }
        audioProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Color.rgb(224, 179, 106))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(56, 65, 69))
        }
        audioInfo.addView(audioStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
        audioInfo.addView(audioProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        speedButton = Button(this).apply {
            text = "1.40×"; contentDescription = "Velocidad y temporizador"
            setTextColor(Color.WHITE); background = circle(Color.argb(40, 255, 255, 255))
            setOnClickListener { showAudioOptions() }
        }
        miniPlayer.addView(playButton, LinearLayout.LayoutParams(dp(58), dp(58)))
        miniPlayer.addView(audioInfo, LinearLayout.LayoutParams(0, dp(68), 1f))
        miniPlayer.addView(speedButton, LinearLayout.LayoutParams(dp(64), dp(54)))

        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        root.addView(pageFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(miniPlayer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82)).apply {
            setMargins(dp(16), dp(8), dp(16), dp(14))
        })
        setContentView(root)
        applyPdfTheme()
        pageImage.post { renderPage() }
    }

    private fun registerControls() {
        ContextCompat.registerReceiver(this, audioReceiver, IntentFilter(AudioPlaybackService.ACTION_COMMAND), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, timerReceiver, IntentFilter(SleepTimerService.ACTION_FINISHED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun renderPage() {
        val current = renderer ?: return
        if (pageIndex !in 0 until current.pageCount || !::pageImage.isInitialized) return
        val targetPage = pageIndex
        val targetWidth = pageImage.width.takeIf { it > 0 } ?: 1200
        val total = current.pageCount.coerceAtLeast(1)
        val percent = (((pageIndex + 1).toFloat() / total) * 100).toInt().coerceIn(0, 100)
        pageLabel.text = "Página ${pageIndex + 1} de $total"
        pageTextScroll.scrollTo(0, 0)
        audioProgress.progress = percent
        previousButton.isEnabled = pageIndex > 0
        nextButton.isEnabled = pageIndex < total - 1
        saveProgress(percent)
        updateNotification()
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                synchronized(current) {
                    current.openPage(targetPage).use { page ->
                        val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                        Bitmap.createBitmap(
                            targetWidth,
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        ).also { output ->
                            output.eraseColor(Color.WHITE)
                            page.render(output, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
            val extractedText = extractPageText(targetPage)
            if (targetPage == pageIndex && !isFinishing && !isDestroyed) {
                val previous = currentBitmap
                currentBitmap = bitmap
                pageImage.setImageBitmap(bitmap)
                pageText.text = extractedText.ifBlank { "No se pudo extraer texto de esta página. Puede ser una página escaneada." }
                applyPdfTextPreferences()
                previous?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
            } else if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun movePage(direction: Int, restartAudio: Boolean = false) {
        val total = renderer?.pageCount ?: return
        val target = (pageIndex + direction).coerceIn(0, total - 1)
        if (target == pageIndex) return
        pageIndex = target
        renderPage()
        if (restartAudio || playing) startAudio()
    }

    private fun toggleTextMode() {
        textMode = !textMode
        pageTextScroll.visibility = if (textMode) View.VISIBLE else View.GONE
        pageImage.visibility = if (textMode) View.GONE else View.VISIBLE
        textModeButton.text = if (textMode) "Página" else "Texto"
        textModeButton.contentDescription = if (textMode) "Mostrar página original del PDF" else "Mostrar texto adaptable"
        if (textMode && pageText.text.isNullOrBlank()) renderPage()
    }

    private fun applyPdfTheme() {
        val spec = preferences.themeSpec()
        val background = parseColor(spec.background, Color.rgb(20, 24, 26))
        val surface = parseColor(spec.surface, background)
        val foreground = parseColor(spec.text, Color.WHITE)
        val accent = parseColor(spec.accent, Color.rgb(224, 179, 106))
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = if (spec.lightSystemBars) {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else 0
        (pageFrame.parent as? View)?.setBackgroundColor(background)
        pageFrame.setBackgroundColor(background)
        miniPlayer.background = rounded(surface, 40f, foreground.withAlpha(35))
        pageImage.setBackgroundColor(surface)
        pageTextScroll.setBackgroundColor(background)
        pageLabel.setTextColor(foreground)
        audioStatus.setTextColor(foreground)
        listOf(previousButton, nextButton).forEach { it.setColorFilter(foreground) }
        listOf(readerOptionsButton, textModeButton, speedButton).forEach { button ->
            button.setTextColor(foreground)
            button.background = rounded(surface, 14f, foreground.withAlpha(35))
        }
        playButton.background = circle(accent)
        playButton.setColorFilter(parseColor(spec.background, Color.BLACK))
        audioProgress.progressTintList = ColorStateList.valueOf(accent)
        audioProgress.progressBackgroundTintList = ColorStateList.valueOf(foreground.withAlpha(45))
        applyPdfTextPreferences()
    }

    private fun applyPdfTextPreferences() {
        val spec = preferences.themeSpec()
        pageText.setTextColor(parseColor(spec.text, Color.BLACK))
        pageText.textSize = (preferences.fontScale * 16f).coerceIn(13f, 32f)
        pageText.typeface = when (preferences.fontFamily) {
            "Times New Roman", "Palatino", "Garamond" -> Typeface.SERIF
            "Mono" -> Typeface.MONOSPACE
            "Sans" -> Typeface.SANS_SERIF
            else -> Typeface.create(preferences.fontFamily, Typeface.NORMAL)
        }
        pageText.setLineSpacing(dp(4).toFloat(), preferences.lineSpacing)
        pageTextScroll.setBackgroundColor(parseColor(spec.background, Color.WHITE))
    }

    private fun showReadingOptions() {
        val spec = preferences.themeSpec()
        val foreground = parseColor(spec.text, Color.WHITE)
        val surface = parseColor(spec.surface, Color.DKGRAY)
        val accent = parseColor(spec.accent, Color.YELLOW)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            setBackgroundColor(surface)
        }
        val themeCaption = TextView(this).apply {
            text = "TEMA DE LECTURA"; setTextColor(foreground); textSize = 12f
        }
        panel.addView(themeCaption)
        val themeChoices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        readerThemes.forEach { theme ->
            themeChoices.addView(Button(this).apply {
                text = theme.name.take(1)
                contentDescription = theme.name
                setTextColor(parseColor(theme.text, foreground))
                background = rounded(parseColor(theme.background, surface), 12f, parseColor(theme.accent, accent))
                setOnClickListener {
                    preferences = preferences.withTheme(theme.name)
                    applyPdfTheme()
                    persistPreferences()
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(8), dp(2), dp(14)) })
        }
        panel.addView(themeChoices)
        val fontCaption = TextView(this).apply {
            text = "TAMAÑO · ${(preferences.fontScale * 16).toInt()} sp"
            setTextColor(foreground); textSize = 14f
        }
        val fontSize = SeekBar(this).apply {
            max = 15; progress = ((preferences.fontScale * 16).toInt() - 13).coerceIn(0, 15)
            progressTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    preferences = preferences.copy(fontScale = (progress + 13) / 16f)
                    fontCaption.text = "TAMAÑO · ${progress + 13} sp"
                    applyPdfTextPreferences()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) { persistPreferences() }
            })
        }
        panel.addView(fontCaption)
        panel.addView(fontSize)
        AlertDialog.Builder(this, readerDialogTheme())
            .setTitle("Ajustes de lectura PDF")
            .setView(panel)
            .setPositiveButton("Listo", null)
            .show()
            .also { stylePdfDialog(it) }
    }

    private fun persistPreferences() {
        lifecycleScope.launch { PreferencesStore(this@PdfReaderActivity).save(preferences) }
    }

    private fun readerDialogTheme(): Int = if (preferences.isDark()) {
        android.R.style.Theme_Material_Dialog_Alert
    } else android.R.style.Theme_Material_Light_Dialog_Alert

    private fun stylePdfDialog(dialog: AlertDialog) {
        val spec = preferences.themeSpec()
        dialog.window?.setBackgroundDrawable(rounded(parseColor(spec.surface, Color.DKGRAY), 22f, parseColor(spec.text, Color.WHITE).withAlpha(35)))
        dialog.findViewById<TextView>(android.R.id.title)?.setTextColor(parseColor(spec.text, Color.WHITE))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(parseColor(spec.accent, Color.YELLOW))
    }

    private fun parseColor(value: String, fallback: Int): Int = runCatching { Color.parseColor(value) }.getOrDefault(fallback)
    private fun Int.withAlpha(alpha: Int): Int = Color.argb(alpha.coerceIn(0, 255), Color.red(this), Color.green(this), Color.blue(this))

    private fun toggleAudio() {
        if (playing) pauseAudio() else startAudio()
    }

    private fun startAudio() {
        val url = RemoteTtsConfig.normalizeWebsocketUrl(preferences.remoteTtsUrl.ifBlank { RemoteTtsConfig.websocketUrl })
        if (url.isBlank()) {
            Toast.makeText(this, "Configura el servidor de voz M5 en Ajustes", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            audioStatus.text = "Preparando página ${pageIndex + 1}…"
            val text = extractPageText(pageIndex)
            if (text.isBlank()) {
                audioStatus.text = "Esta página no contiene texto extraíble"
                Toast.makeText(this@PdfReaderActivity, "El PDF puede ser una imagen escaneada; necesita OCR", Toast.LENGTH_LONG).show()
                return@launch
            }
            tts?.close()
            tts = RemoteTtsPlaybackManager(url, BuildConfig.REMOTE_TTS_TOKEN)
            playing = true
            updatePlayState()
            tts?.start(text, preferences.ttsSpeed, "M5", pageIndex.toString(), pdfListener())
            updateNotification()
        }
    }

    private fun pdfListener() = object : RemoteTtsPlaybackManager.Listener {
        override fun onStatus(status: String) { audioStatus.text = "$status · Página ${pageIndex + 1}" }
        override fun onSegmentStarted(segmentId: Int) = Unit
        override fun onSegmentFinished(segmentId: Int) = Unit
        override fun onUtteranceStarted(utteranceKey: String) {
            utteranceKey.toIntOrNull()?.let { page ->
                if (page != pageIndex) { pageIndex = page; renderPage() }
            }
        }
        override fun onNeedsNextUtterance() {
            val next = pageIndex + 1
            if (next >= (renderer?.pageCount ?: 0)) { tts?.finishInput(); return }
            lifecycleScope.launch {
                val text = extractPageText(next)
                if (text.isBlank()) tts?.finishInput() else tts?.append(text, next.toString())
            }
        }
        override fun onFinished() {
            playing = false; audioStatus.text = "Lectura terminada"; updatePlayState(); stopNotification()
        }
        override fun onError(message: String) {
            val total = (renderer?.pageCount ?: 1).coerceAtLeast(1)
            saveProgress((((pageIndex + 1f) / total) * 100).toInt())
            playing = false; audioStatus.text = message; updatePlayState(); stopNotification()
            Toast.makeText(this@PdfReaderActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun extractPageText(index: Int): String = withContext(Dispatchers.IO) {
        val document = textDocument ?: return@withContext ""
        runCatching {
            PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }.getText(document)
                .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        }.getOrDefault("")
    }

    private fun pauseAudio() {
        val total = (renderer?.pageCount ?: 1).coerceAtLeast(1)
        saveProgress((((pageIndex + 1f) / total) * 100).toInt())
        tts?.pause(); playing = false; audioStatus.text = "En pausa · Página ${pageIndex + 1}"
        updatePlayState(); updateNotification(); SleepTimerService.stop(this)
    }

    private fun stopAudio() {
        tts?.close(); tts = null; playing = false; audioStatus.text = "Voz M5 · PDF"
        updatePlayState(); stopNotification(); SleepTimerService.stop(this)
    }

    private fun updatePlayState() {
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playButton.contentDescription = if (playing) "Pausar lectura" else "Reproducir lectura"
    }

    private fun updateNotification() {
        if (!playing && tts == null) return
        val total = (renderer?.pageCount ?: 1).coerceAtLeast(1)
        val progress = (((pageIndex + 1f) / total) * 100).toInt()
        val cover = pdfFile.parentFile?.resolve("cover.jpg")?.takeIf { it.isFile }?.absolutePath
        AudioPlaybackService.update(
            this, pdfFile.nameWithoutExtension,
            "Página ${pageIndex + 1} · Voz M5 · ${formatSpeed(preferences.ttsSpeed)}×",
            playing, progress, bookId, pdfFile.absolutePath, cover
        )
    }

    private fun stopNotification() = AudioPlaybackService.stop(this)

    private fun showAudioOptions() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val value = TextView(this).apply { text = "Velocidad · ${formatSpeed(preferences.ttsSpeed)}×"; textSize = 16f }
        val seek = SeekBar(this).apply {
            max = 30; progress = ((preferences.ttsSpeed - .5f) * 20).toInt().coerceIn(0, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val speed = .5f + progress / 20f
                    preferences = preferences.copy(ttsSpeed = speed)
                    value.text = "Velocidad · ${formatSpeed(speed)}×"
                    speedButton.text = "${formatSpeed(speed)}×"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) { lifecycleScope.launch { PreferencesStore(this@PdfReaderActivity).save(preferences) } }
            })
        }
        val timers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(0, 15, 30, 60).forEach { minutes ->
            timers.addView(Button(this).apply {
                text = if (minutes == 0) "Off" else "$minutes min"
                setOnClickListener { if (minutes == 0) SleepTimerService.stop(this@PdfReaderActivity) else SleepTimerService.start(this@PdfReaderActivity, minutes) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        container.addView(value); container.addView(seek); container.addView(timers)
        AlertDialog.Builder(this).setTitle("Audio M5").setView(container).setPositiveButton("Listo", null).show()
    }

    private fun saveProgress(percent: Int) {
        if (bookId.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            DatabaseProvider.get(this@PdfReaderActivity).readingDao().saveProgress(
                ReadingProgress(bookId, JSONObject().put("pdfPage", pageIndex).toString(), percent / 100.0)
            )
        }
    }

    override fun onPause() {
        val total = (renderer?.pageCount ?: 1).coerceAtLeast(1)
        saveProgress((((pageIndex + 1f) / total) * 100).toInt())
        super.onPause()
    }

    private suspend fun restorePage(): Int = withContext(Dispatchers.IO) {
        if (bookId.isBlank()) return@withContext 0
        val raw = DatabaseProvider.get(this@PdfReaderActivity).readingDao().progress(bookId)?.locatorJson ?: return@withContext 0
        runCatching { JSONObject(raw).optInt("pdfPage", 0) }.getOrDefault(0)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 83)
        }
    }

    private fun iconButton(icon: Int, description: String, click: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); contentDescription = description; background = null; setColorFilter(Color.WHITE); setOnClickListener { click() }; setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    private fun rounded(color: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(radius.toInt()).toFloat(); setColor(color); setStroke(dp(1), stroke)
    }
    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun formatSpeed(value: Float) = "%.2f".format(value).trimEnd('0').trimEnd('.')

    override fun onDestroy() {
        runCatching { unregisterReceiver(audioReceiver) }
        runCatching { unregisterReceiver(timerReceiver) }
        val total = (renderer?.pageCount ?: 1).coerceAtLeast(1)
        saveProgress((((pageIndex + 1f) / total) * 100).toInt())
        if (::playButton.isInitialized) stopAudio() else tts?.close()
        renderJob?.cancel()
        if (::pageImage.isInitialized) pageImage.setImageDrawable(null)
        currentBitmap?.takeIf { !it.isRecycled }?.recycle()
        currentBitmap = null
        renderer?.close(); renderer = null
        textDocument?.close(); textDocument = null
        super.onDestroy()
    }
}
