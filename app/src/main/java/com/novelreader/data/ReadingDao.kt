package com.novelreader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Update
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun progress(bookId: String): ReadingProgress?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: ReadingProgress)
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun bookmarks(bookId: String): Flow<List<Bookmark>>
    @Insert
    suspend fun addBookmark(bookmark: Bookmark)
    @Update
    suspend fun updateBookmark(bookmark: Bookmark)
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND kind = :kind LIMIT 1")
    suspend fun findBookmarkByKind(bookId: String, kind: String): Bookmark?
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND locatorJson = :locatorJson AND kind = :kind LIMIT 1")
    suspend fun findBookmark(bookId: String, locatorJson: String, kind: String): Bookmark?
    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)
}
