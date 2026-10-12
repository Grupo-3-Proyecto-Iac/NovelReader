package com.novelreader.data

data class ReaderPreferences(
    val fontFamily: String = "Times New Roman",
    /** Readium usa un multiplicador respecto a 16 sp. */
    val fontScale: Float = 18f / 16f,
    val textColor: String = "#FFF1EFE8",
    val backgroundColor: String = "#FF14181A",
    val lineSpacing: Float = 1.75f,
    val paragraphSpacing: Float = 1.1f,
    val justifiedText: Boolean = false,
    val verticalReading: Boolean = true,
    val devicePortrait: Boolean = true,
    val ttsSpeed: Float = 1.4f,
    val ttsPitch: Float = 1f,
    val ttsVoiceId: String = "",
    /** Dirección WebSocket del servidor TTS. Se puede cambiar desde Ajustes. */
    val remoteTtsUrl: String = ""
)
