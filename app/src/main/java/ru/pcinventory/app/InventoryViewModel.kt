package ru.pcinventory.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.pcinventory.app.data.InventoryDatabase
import ru.pcinventory.app.data.Item
import ru.pcinventory.app.data.ReportBuilder
import ru.pcinventory.app.data.readFirstSheet
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Результат сканирования/ручного поиска инвентарного номера. */
enum class ScanResult { FOUND, ALREADY, NOT_FOUND }

class InventoryViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = InventoryDatabase.get(app).itemDao()

    /** Живой список позиций. */
    val items: StateFlow<List<Item>> =
        dao.getAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ------------------------------------------------------------- сканирование

    /** Отметить позицию найденной по содержимому QR-кода. suspend — вызывается из корутины UI. */
    suspend fun scan(code: String): ScanResult {
        val clean = code.trim()
        val item = dao.getByInventoryNumber(clean) ?: return ScanResult.NOT_FOUND
        if (item.status == Item.STATUS_FOUND) return ScanResult.ALREADY
        dao.update(item.copy(status = Item.STATUS_FOUND, scannedAt = System.currentTimeMillis()))
        return ScanResult.FOUND
    }

    /** Пометить «нет на месте». */
    fun markMissing(item: Item) = viewModelScope.launch {
        dao.update(item.copy(status = Item.STATUS_MISSING, scannedAt = System.currentTimeMillis()))
    }

    /** Переключить флажок «найдено/не проверено». */
    fun togglePresent(item: Item) = viewModelScope.launch {
        if (item.status == Item.STATUS_FOUND) {
            dao.update(item.copy(status = Item.STATUS_UNCHECKED, scannedAt = null))
        } else {
            dao.update(item.copy(status = Item.STATUS_FOUND, scannedAt = System.currentTimeMillis()))
        }
    }

    // ------------------------------------------------------------ CRUD позиций

    /** Добавить позицию. Возвращает false, если позиция с таким инв. № уже есть. */
    suspend fun addItem(category: String, inv: String, name: String, desc: String): Boolean {
        val code = inv.trim()
        if (dao.getByInventoryNumber(code) != null) return false
        dao.insert(
            Item(
                category = category.ifBlank { "Прочее" }.trim(),
                inventoryNumber = code,
                name = name.trim(),
                description = desc.trim(),
                sortOrder = (dao.maxSortOrder() ?: -1L) + 1
            )
        )
        return true
    }

    fun updateItem(item: Item) = viewModelScope.launch { dao.update(item) }

    fun deleteItem(item: Item) = viewModelScope.launch { dao.delete(item) }

    /** Сбросить все статусы (новая инвентаризация того же списка). */
    fun resetAll() = viewModelScope.launch { dao.resetStatuses() }

    /** Заменить весь список импортированными позициями. */
    fun replaceAll(newItems: List<Item>) = viewModelScope.launch {
        dao.deleteAll()
        dao.insertAll(
            newItems.mapIndexed { i, it ->
                it.copy(id = 0, sortOrder = i.toLong(), status = Item.STATUS_UNCHECKED, scannedAt = null)
            }
        )
    }

    /** Добавить импортированные позиции к существующим. */
    fun appendAll(list: List<Item>) = viewModelScope.launch {
        val base = (dao.maxSortOrder() ?: -1L) + 1
        dao.insertAll(
            list.mapIndexed { i, it ->
                it.copy(id = 0, sortOrder = base + i, status = Item.STATUS_UNCHECKED, scannedAt = null)
            }
        )
    }

    // ---------------------------------------------------------------- импорт

    /**
     * Импорт из XLSX. Определяет колонки по заголовку (первая непустая строка):
     * «категор*» → категория; «инв*»/«*номер*» → инв. №; «назван*»/«наименован*»/«модель*» → название;
     * «описан*»/«комплект*»/«конфиг*» → описание. Без узнаваемых заголовков — индексы 0..3.
     * Возвращает null при ошибке парсинга.
     */
    suspend fun importFrom(uri: Uri): List<Item>? = withContext(Dispatchers.IO) {
        try {
            val rows = getApplication<Application>().contentResolver
                .openInputStream(uri)?.use { readFirstSheet(it) }
                ?: return@withContext null
            if (rows.isEmpty()) return@withContext null

            val headerIdx = rows.indexOfFirst { row -> row.any { it.isNotBlank() } }
            if (headerIdx < 0) return@withContext null
            val header = rows[headerIdx]

            var cat = -1; var inv = -1; var nm = -1; var ds = -1
            header.forEachIndexed { i, h ->
                when {
                    h.lowercase().contains("категор") -> cat = i
                    h.lowercase().contains("инв") || h.lowercase().contains("номер") -> inv = i
                    h.lowercase().contains("назван") || h.lowercase().contains("наименован") ||
                        h.lowercase().contains("модель") -> nm = i
                    h.lowercase().contains("описан") || h.lowercase().contains("комплект") ||
                        h.lowercase().contains("конфиг") -> ds = i
                }
            }
            val headerFound = cat >= 0 || inv >= 0 || nm >= 0 || ds >= 0
            if (!headerFound) { cat = 0; inv = 1; nm = 2; ds = 3 }

            fun cell(row: List<String>, i: Int): String =
                if (i in row.indices) row[i].trim() else ""

            val result = ArrayList<Item>()
            val start = if (headerFound) headerIdx + 1 else headerIdx
            for (r in start until rows.size) {
                val row = rows[r]
                val name = cell(row, nm)
                val code = cell(row, inv)
                if (name.isBlank() && code.isBlank()) continue
                result.add(
                    Item(
                        category = cell(row, cat).ifBlank { "Прочее" },
                        inventoryNumber = code,
                        name = name,
                        description = cell(row, ds),
                        sortOrder = result.size.toLong()
                    )
                )
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- экспорт

    /** Готовит XLSX-отчёт в кэше. null — нет данных. */
    suspend fun exportToCache(): File? = withContext(Dispatchers.IO) {
        val all = dao.getAllOnce()
        if (all.isEmpty()) return@withContext null
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
        val f = File(getApplication<Application>().cacheDir, "otchet_$stamp.xlsx")
        f.writeBytes(ReportBuilder.buildReport(all))
        f
    }

    /**
     * PDF с QR-наклейками: A4, сетка 3×7 (21 позиция на страницу).
     * null — список пуст.
     */
    suspend fun buildStickersPdf(list: List<Item>): File? = withContext(Dispatchers.IO) {
        if (list.isEmpty()) return@withContext null
        val app = getApplication<Application>()
        val pageW = 595; val pageH = 842; val margin = 30
        val cols = 3; val rowsCount = 7; val perPage = cols * rowsCount
        val cellW = (pageW - 2 * margin).toFloat() / cols
        val cellH = (pageH - 2 * margin).toFloat() / rowsCount

        val doc = PdfDocument()
        try {
            var pageNo = 0
            var idx = 0
            while (idx < list.size) {
                pageNo++
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
                val canvas = page.canvas
                for (k in 0 until perPage) {
                    if (idx >= list.size) break
                    val item = list[idx]; idx++
                    val cx = margin + (k % cols) * cellW
                    val cy = margin + (k / cols) * cellH
                    val centerX = cx + cellW / 2f

                    // QR ~120pt, но не выше ячейки (иначе 21 наклейка не влезает)
                    val side = minOf(120f, cellH - 40f)
                    val qr = qrBitmap(item.inventoryNumber)
                    canvas.drawBitmap(
                        qr, null,
                        RectF(cx + (cellW - side) / 2f, cy, cx + (cellW + side) / 2f, cy + side),
                        null
                    )
                    qr.recycle()

                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
                    var ty = cy + side + 12f
                    paint.isFakeBoldText = true; paint.textSize = 11f; paint.color = Color.BLACK
                    canvas.drawText(ellipsize(item.inventoryNumber, paint, cellW - 8f), centerX, ty, paint)
                    ty += 12f
                    paint.isFakeBoldText = false; paint.textSize = 9f
                    canvas.drawText(ellipsize(item.name, paint, cellW - 8f), centerX, ty, paint)
                    ty += 10f
                    paint.textSize = 8f; paint.color = Color.GRAY
                    canvas.drawText(ellipsize(item.category, paint, cellW - 8f), centerX, ty, paint)
                }
                doc.finishPage(page)
            }
            val f = File(app.cacheDir, "naklejki.pdf")
            f.outputStream().use { doc.writeTo(it) }
            f
        } finally {
            doc.close()
        }
    }

    // ------------------------------------------------------------- утилиты

    /** QR-код как Bitmap: белый фон, чёрные модули. */
    private fun qrBitmap(text: String): Bitmap {
        val size = 300
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 1)
        )
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        for (x in 0 until size) {
            for (y in 0 until size) {
                if (matrix.get(x, y)) bmp.setPixel(x, y, Color.BLACK)
            }
        }
        return bmp
    }

    private fun ellipsize(s: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(s) <= maxWidth) return s
        var t = s
        while (t.isNotEmpty() && paint.measureText("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }
}
