package com.juanmanuel.listavoz

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {

    // Lista ordenada: pendientes primero y alfabético
    @Query("SELECT * FROM items ORDER BY purchased ASC, name ASC")
    fun observeAll(): Flow<List<Item>>

    // Upsert simple (con REPLACE por id autogenerado)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Item)

    @Query("UPDATE items SET purchased = :flag WHERE id = :id")
    suspend fun setPurchased(id: Long, flag: Boolean)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteById(id: Long)

    // === NUEVAS para las features ===

    // Borra todos los marcados como comprados
    @Query("DELETE FROM items WHERE purchased = 1")
    suspend fun deletePurchased()

    // Cambia la cantidad con un delta (asegura mínimo 1)
    @Query("""
    UPDATE items 
    SET qty = CASE WHEN qty + :delta < 1 THEN 1 ELSE qty + :delta END 
    WHERE id = :id
""")
    suspend fun changeQty(id: Long, delta: Int)

    // (Opcional) setear una cantidad específica
    @Query("UPDATE items SET qty = :qty WHERE id = :id")
    suspend fun setQty(id: Long, qty: Int)
    // Renombrar un ítem (para el diálogo de edición)
    @Query("UPDATE items SET name = :newName WHERE id = :id")
    suspend fun rename(id: Long, newName: String)
}
