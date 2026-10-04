package com.novelreader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading_progress")
data class ReadingProgress(
    @PrimaryKey val bookId: String,
    val locatorJson: String,
    val totalProgression: Double? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
