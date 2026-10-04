package com.novelreader.data

import android.content.Context
import androidx.room.Room

object DatabaseProvider {
    @Volatile private var instance: NovelReaderDatabase? = null

    fun get(context: Context): NovelReaderDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, NovelReaderDatabase::class.java, "novelreader.db")
            .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE books ADD COLUMN coverPath TEXT") }
            })
            .addMigrations(object : androidx.room.migration.Migration(2, 3) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS reading_progress (bookId TEXT NOT NULL PRIMARY KEY, locatorJson TEXT NOT NULL, totalProgression REAL, updatedAt INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId TEXT NOT NULL, locatorJson TEXT NOT NULL, label TEXT, createdAt INTEGER NOT NULL)")
                }
            })
            .build().also { instance = it }
    }
}
