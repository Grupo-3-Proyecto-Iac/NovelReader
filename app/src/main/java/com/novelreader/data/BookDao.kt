package com.novelreader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY importedAt DESC")
    fun observeBooks(): Flow<List<Book>>
    @Query("SELECT b.id, b.title, b.author, b.localPath, b.coverPath, b.sha256, b.importedAt, p.totalProgression FROM books b LEFT JOIN reading_progress p ON p.bookId = b.id ORDER BY b.importedAt DESC")
    fun observeSummaries(): Flow<List<BookSummary>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: Book)
    @Query("SELECT * FROM books WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findByHash(sha256: String): Book?
    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)
}
