package com.novelreader.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Book::class, ReadingProgress::class, Bookmark::class], version = 3, exportSchema = false)
abstract class NovelReaderDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingDao(): ReadingDao
}
