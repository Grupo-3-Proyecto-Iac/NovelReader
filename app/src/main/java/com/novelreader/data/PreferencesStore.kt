package com.novelreader.data

import android.content.Context
import com.novelreader.BuildConfig
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.readerDataStore by preferencesDataStore("reader_preferences")

class PreferencesStore(private val context: Context) {
    private val font = stringPreferencesKey("font")
    private val scale = floatPreferencesKey("scale")
    private val text = stringPreferencesKey("text_color")
    private val background = stringPreferencesKey("background_color")
    private val vertical = booleanPreferencesKey("vertical_reading")
    private val lineSpacing = floatPreferencesKey("line_spacing")
    private val paragraphSpacing = floatPreferencesKey("paragraph_spacing")
    private val portrait = booleanPreferencesKey("device_portrait")
    private val ttsSpeed = floatPreferencesKey("tts_speed")
    private val ttsPitch = floatPreferencesKey("tts_pitch")
    private val ttsVoiceId = stringPreferencesKey("tts_voice_id")
    private val remoteTtsUrl = stringPreferencesKey("remote_tts_url")
    val preferences = context.readerDataStore.data.map { p -> ReaderPreferences(
        fontFamily = p[font] ?: "Sans Serif", fontScale = (p[scale] ?: 1f).coerceIn(.75f,1.25f),
        textColor = p[text] ?: "#FF252B27", backgroundColor = p[background] ?: "#FFF5F3EC",
        verticalReading = p[vertical] ?: true,
        lineSpacing = p[lineSpacing] ?: 1.2f,
        paragraphSpacing = p[paragraphSpacing] ?: 1f,
        devicePortrait = p[portrait] ?: true,
        ttsSpeed = (p[ttsSpeed] ?: 1f).coerceIn(.5f, 2f),
        ttsPitch = (p[ttsPitch] ?: 1f).coerceIn(.5f, 2f),
        ttsVoiceId = p[ttsVoiceId] ?: "",
        remoteTtsUrl = p[remoteTtsUrl] ?: BuildConfig.REMOTE_TTS_URL
    ) }
    suspend fun save(value: ReaderPreferences) { context.readerDataStore.edit { p ->
        p[font] = value.fontFamily; p[scale] = value.fontScale.coerceIn(.75f,1.25f); p[text] = value.textColor
        p[background] = value.backgroundColor; p[vertical] = value.verticalReading
        p[lineSpacing] = value.lineSpacing; p[paragraphSpacing] = value.paragraphSpacing; p[portrait] = value.devicePortrait
        p[ttsSpeed] = value.ttsSpeed.coerceIn(.5f, 2f); p[ttsPitch] = value.ttsPitch.coerceIn(.5f, 2f)
        p[ttsVoiceId] = value.ttsVoiceId
        p[remoteTtsUrl] = value.remoteTtsUrl.trim()
    } }
}
