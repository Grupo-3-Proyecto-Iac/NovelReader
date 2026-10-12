package com.novelreader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val locatorJson: String,
    val label: String? = null,
    val kind: String = KIND_READING,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val KIND_READING = "READING"
        const val KIND_AUDIO = "AUDIO"
        const val KIND_AUTO_AUDIO = "AUTO_AUDIO"
    }
}
