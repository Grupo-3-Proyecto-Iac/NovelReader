package com.novelreader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: String,
    val title: String,
    val author: String = "",
    val localPath: String,
    val coverPath: String? = null,
    val sha256: String,
    val importedAt: Long = System.currentTimeMillis()
)
