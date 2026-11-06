package ru.pcinventory.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Позиция инвентаризации: единица техники или комплектующее.
 *
 * @param status 0 — не проверено, 1 — найдено (в наличии), 2 — отсутствует.
 * @param inventoryNumber содержимое QR-кода (инвентарный номер).
 * @param scannedAt время проверки в миллисекундах (epoch), null — не проверялась.
 * @param sortOrder порядок вставки (для сортировки непроверенных).
 */
@Entity(tableName = "items")
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val inventoryNumber: String,
    val name: String,
    val description: String,
    val status: Int = STATUS_UNCHECKED,
    val scannedAt: Long? = null,
    val sortOrder: Long = 0
) {
    companion object {
        const val STATUS_UNCHECKED = 0
        const val STATUS_FOUND = 1
        const val STATUS_MISSING = 2
    }
}
