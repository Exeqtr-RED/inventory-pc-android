package ru.pcinventory.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {

    /** Живой поток всего списка (порядок вставки). */
    @Query("SELECT * FROM items ORDER BY sortOrder ASC, id ASC")
    fun getAll(): Flow<List<Item>>

    /** Одноразовый снимок списка (для экспорта и PDF). */
    @Query("SELECT * FROM items ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllOnce(): List<Item>

    /** Поиск по содержимому QR-кода (инвентарному номеру). */
    @Query("SELECT * FROM items WHERE inventoryNumber = :code LIMIT 1")
    suspend fun getByInventoryNumber(code: String): Item?

    @Query("SELECT COUNT(*) FROM items")
    suspend fun count(): Int

    /** Максимальный sortOrder; null, если таблица пуста. */
    @Query("SELECT MAX(sortOrder) FROM items")
    suspend fun maxSortOrder(): Long?

    @Insert
    suspend fun insert(item: Item): Long

    @Insert
    suspend fun insertAll(items: List<Item>)

    @Update
    suspend fun update(item: Item)

    @Delete
    suspend fun delete(item: Item)

    @Query("DELETE FROM items")
    suspend fun deleteAll()

    /** Сброс отметок инвентаризации: статус -> не проверено, время -> null. */
    @Query("UPDATE items SET status = 0, scannedAt = NULL")
    suspend fun resetStatuses()
}
