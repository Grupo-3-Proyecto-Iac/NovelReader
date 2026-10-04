package com.novelreader.data

data class ReaderPreferences(
    val fontFamily: String = "Sans Serif",
    val fontScale: Float = 1f,
    val textColor: String = "#FF252B27",
    val backgroundColor: String = "#FFF5F3EC",
    val lineSpacing: Float = 1.2f,
    val paragraphSpacing: Float = 1f,
    val verticalReading: Boolean = true,
    val devicePortrait: Boolean = true,
    val ttsSpeed: Float = 1f,
    val ttsPitch: Float = 1f,
    val ttsVoiceId: String = "",
    /** Dirección WebSocket del servidor TTS. Se puede cambiar desde Ajustes. */
    val remoteTtsUrl: String = ""
)
