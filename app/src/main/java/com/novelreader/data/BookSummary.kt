package com.novelreader.data

data class BookSummary(
    val id: String,
    val title: String,
    val author: String,
    val localPath: String,
    val coverPath: String?,
    val sha256: String,
    val importedAt: Long,
    val totalProgression: Double?
)
