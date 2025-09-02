package com.juanmanuel.listavoz

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY purchased ASC, name ASC")
    fun observeAll(): Flow<List<Item>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Item)

    @Query("UPDATE items SET purchased = :flag WHERE id = :id")
    suspend fun setPurchased(id: Long, flag: Boolean)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteById(id: Long)
}
