package com.novelreader.reader

/**
 * Crea unidades de voz cortas y naturales. Los saltos entre párrafos se
 * conservan como límites, pero no se generan unidades diminutas solo porque
 * una línea del EPUB sea corta.
 */
object SpeechChunker {
    private const val MIN_CHARS = 40
    private const val TARGET_CHARS = 180
    private const val MAX_CHARS = 300
    private val sentenceBoundary = Regex("(?<=[.!?…。！？])\\s+")

    fun split(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isBlank()) return emptyList()
        return normalized
            .split(Regex("\\n\\s*\\n+"))
            .flatMap(::splitParagraph)
            .filter { it.isNotBlank() }
    }

    private fun splitParagraph(raw: String): List<String> {
        val paragraph = raw.replace(Regex("[ \\t\\n]+"), " ").trim()
        if (paragraph.isBlank()) return emptyList()
        if (paragraph.length <= MAX_CHARS) return listOf(paragraph)

        val sentences = paragraph.split(sentenceBoundary).filter { it.isNotBlank() }
        val result = mutableListOf<String>()
        var current = ""
        for (sentence in sentences) {
            val parts = if (sentence.length > MAX_CHARS) splitWords(sentence) else listOf(sentence.trim())
            for (part in parts) {
                if (current.isBlank()) {
                    current = part
                } else if (current.length + part.length + 1 <= TARGET_CHARS) {
                    current += " $part"
                } else {
                    result += current.trim()
                    current = part
                }
                if (current.length >= MAX_CHARS) {
                    result += current.trim()
                    current = ""
                }
            }
        }
        if (current.isNotBlank()) result += current.trim()
        return mergeTinyUnits(result)
    }

    private fun mergeTinyUnits(units: List<String>): List<String> {
        if (units.size < 2) return units
        val result = mutableListOf<String>()
        for (unit in units) {
            if (result.isNotEmpty() && unit.length < MIN_CHARS && result.last().length + unit.length + 1 <= MAX_CHARS) {
                result[result.lastIndex] = "${result.last()} $unit"
            } else {
                result += unit
            }
        }
        return result
    }

    private fun splitWords(text: String): List<String> {
        val result = mutableListOf<String>()
        var current = ""
        for (word in text.split(Regex("\\s+"))) {
            if (word.isBlank()) continue
            if (current.isBlank()) current = word
            else if (current.length + word.length + 1 <= MAX_CHARS) current += " $word"
            else {
                result += current
                current = word
            }
        }
        if (current.isNotBlank()) result += current
        return result
    }
}
