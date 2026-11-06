package ru.pcinventory.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Построение XLSX-отчёта и шаблона для импорта.
 */
object ReportBuilder {

    private val dateTimeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    fun statusText(status: Int): String = when (status) {
        Item.STATUS_FOUND -> "✓ Найдено"
        Item.STATUS_MISSING -> "✗ Отсутствует"
        else -> "— Не проверено"
    }

    /** Двухлистовый отчёт: «Отчёт» (все позиции) + «Статистика» (итоги по категориям). */
    fun buildReport(items: List<Item>): ByteArray {
        val reportRows = ArrayList<XlsxRow>()
        reportRows.add(
            XlsxRow(
                listOf("№", "Категория", "Инв.№", "Название", "Описание", "Статус", "Время проверки")
                    .map { XlsxCell(text = it, bold = true) }
            )
        )
        items.forEachIndexed { i, item ->
            reportRows.add(
                XlsxRow(
                    listOf(
                        XlsxCell(number = (i + 1).toDouble()),
                        XlsxCell(text = item.category),
                        XlsxCell(text = item.inventoryNumber),
                        XlsxCell(text = item.name),
                        XlsxCell(text = item.description),
                        XlsxCell(text = statusText(item.status)),
                        XlsxCell(text = item.scannedAt?.let { dateTimeFormat.format(Date(it)) } ?: "")
                    )
                )
            )
        }

        val total = items.size
        val found = items.count { it.status == Item.STATUS_FOUND }
        val missing = items.count { it.status == Item.STATUS_MISSING }
        val unchecked = items.count { it.status == Item.STATUS_UNCHECKED }
        val readiness = if (total == 0) 0.0 else found.toDouble() / total * 100.0

        val statRows = ArrayList<XlsxRow>()
        statRows.add(XlsxRow(listOf(XlsxCell(text = "Итоги инвентаризации", bold = true))))
        statRows.add(XlsxRow(listOf(XlsxCell(text = "Дата: " + dateTimeFormat.format(Date())))))
        statRows.add(XlsxRow(emptyList()))
        statRows.add(XlsxRow(listOf(XlsxCell(text = "Всего позиций", bold = true), XlsxCell(number = total.toDouble()))))
        statRows.add(XlsxRow(listOf(XlsxCell(text = "Найдено"), XlsxCell(number = found.toDouble()))))
        statRows.add(XlsxRow(listOf(XlsxCell(text = "Отсутствует"), XlsxCell(number = missing.toDouble()))))
        statRows.add(XlsxRow(listOf(XlsxCell(text = "Не проверено"), XlsxCell(number = unchecked.toDouble()))))
        statRows.add(XlsxRow(listOf(XlsxCell(text = "Готовность, %"), XlsxCell(number = kotlin.math.round(readiness).toDouble()))))
        statRows.add(XlsxRow(emptyList()))
        statRows.add(
            XlsxRow(
                listOf("Категория", "Всего", "Найдено", "Отсутствует", "Не проверено")
                    .map { XlsxCell(text = it, bold = true) }
            )
        )
        items.map { it.category }.distinct().forEach { cat ->
            val group = items.filter { it.category == cat }
            statRows.add(
                XlsxRow(
                    listOf(
                        XlsxCell(text = cat),
                        XlsxCell(number = group.size.toDouble()),
                        XlsxCell(number = group.count { it.status == Item.STATUS_FOUND }.toDouble()),
                        XlsxCell(number = group.count { it.status == Item.STATUS_MISSING }.toDouble()),
                        XlsxCell(number = group.count { it.status == Item.STATUS_UNCHECKED }.toDouble())
                    )
                )
            )
        }

        return buildXlsx(
            listOf(
                XlsxSheet(
                    name = "Отчёт",
                    rows = reportRows,
                    colWidths = listOf(6.0, 18.0, 12.0, 26.0, 46.0, 16.0, 18.0)
                ),
                XlsxSheet(
                    name = "Статистика",
                    rows = statRows,
                    colWidths = listOf(24.0, 10.0, 10.0, 12.0, 14.0)
                )
            )
        )
    }

    /** Шаблон для импорта: один лист «Учёт» с заголовком и тремя строками-примерами. */
    fun buildTemplate(): ByteArray {
        val rows = ArrayList<XlsxRow>()
        rows.add(
            XlsxRow(
                listOf("Категория", "Инв.№", "Название", "Описание")
                    .map { XlsxCell(text = it, bold = true) }
            )
        )
        rows.add(XlsxRow(listOf(XlsxCell("Системные блоки"), XlsxCell("PC-0001"), XlsxCell("Системный блок №1"), XlsxCell("i5-12400 / 16GB / SSD 512 / RTX 3060"))))
        rows.add(XlsxRow(listOf(XlsxCell("Мониторы"), XlsxCell("MN-0001"), XlsxCell("Монитор Dell P2422H"), XlsxCell("24\" IPS, 1920x1080"))))
        rows.add(XlsxRow(listOf(XlsxCell("Клавиатуры"), XlsxCell("KB-0001"), XlsxCell("Logitech K120"), XlsxCell("проводная, USB"))))

        return buildXlsx(
            listOf(
                XlsxSheet(
                    name = "Учёт",
                    rows = rows,
                    colWidths = listOf(20.0, 12.0, 26.0, 46.0)
                )
            )
        )
    }
}
