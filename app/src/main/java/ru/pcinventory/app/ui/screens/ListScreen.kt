package ru.pcinventory.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.pcinventory.app.InventoryViewModel
import ru.pcinventory.app.data.Item
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

private fun groupOf(status: Int): Int = when (status) {
    Item.STATUS_UNCHECKED -> 0
    Item.STATUS_FOUND -> 1
    else -> 2
}

/**
 * Сортировка списка: непроверенные (по порядку вставки), затем найденные
 * (по времени проверки), затем отсутствующие.
 */
private val listComparator = Comparator<Item> { a, b ->
    val ga = groupOf(a.status)
    val gb = groupOf(b.status)
    if (ga != gb) {
        ga - gb
    } else if (ga == 1) {
        (a.scannedAt ?: 0L).compareTo(b.scannedAt ?: 0L)
    } else {
        compareValuesBy(a, b, { it.sortOrder }, { it.id })
    }
}

private val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(vm: InventoryViewModel, onScan: () -> Unit, onStickers: () -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var selectedTab by remember { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<Item?>(null) }
    var deleteCandidate by remember { mutableStateOf<Item?>(null) }
    var imported by remember { mutableStateOf<List<Item>?>(null) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val list = vm.importFrom(uri)
                if (list != null) {
                    imported = list
                } else {
                    snackbar.showSnackbar("Не удалось прочитать файл Excel")
                }
            }
        }
    }

    fun export() {
        scope.launch {
            val file = vm.exportToCache()
            if (file == null) {
                snackbar.showSnackbar("Нет данных для экспорта")
                return@launch
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = XLSX_MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Поделиться отчётом"))
        }
    }

    val total = items.size
    val found = items.count { it.status == Item.STATUS_FOUND }
    val categories = items.map { it.category }.distinct()
    val tabCount = categories.size + 1
    val activeTab = selectedTab.coerceIn(0, tabCount - 1)

    val filtered = remember(items, activeTab) {
        val activeCategory = if (activeTab == 0) null else categories.getOrNull(activeTab - 1)
        items
            .filter { activeCategory == null || it.category == activeCategory }
            .sortedWith(listComparator)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Инвентаризация ПК") },
                actions = {
                    IconButton(onClick = { export() }) {
                        Icon(Icons.Default.Share, contentDescription = "Экспорт в Excel")
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Меню")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Импорт Excel") },
                            onClick = {
                                showMenu = false
                                importPicker.launch(arrayOf(XLSX_MIME))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("QR-наклейки") },
                            onClick = { showMenu = false; onStickers() }
                        )
                        DropdownMenuItem(
                            text = { Text("Новая инвентаризация") },
                            onClick = { showMenu = false; showResetDialog = true }
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(onClick = onScan, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Сканировать QR")
                    }
                    OutlinedButton(onClick = { showAddDialog = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Добавить")
                    }
                }
            }
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            // Статистика
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else found.toFloat() / total },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    "Найдено $found из $total",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Вкладки: «Все» + категории (порядок появления), формат «Категория N/M»
            ScrollableTabRow(selectedTabIndex = activeTab, edgePadding = 16.dp) {
                Tab(selected = activeTab == 0, onClick = { selectedTab = 0 }, text = { Text("Все") })
                categories.forEachIndexed { i, cat ->
                    val group = items.filter { it.category == cat }
                    val ok = group.count { it.status == Item.STATUS_FOUND }
                    Tab(
                        selected = activeTab == i + 1,
                        onClick = { selectedTab = i + 1 },
                        text = {
                            Text("$cat $ok/${group.size}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    )
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { item ->
                    ItemCard(
                        item = item,
                        onToggle = { scope.launch { vm.togglePresent(item) } },
                        onMissing = { vm.markMissing(item) },
                        onEdit = { editItem = item },
                        onDelete = { deleteCandidate = item }
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- диалоги

    if (showAddDialog) {
        ItemDialog(
            item = null,
            presetInv = "",
            allItems = items,
            onSave = { c, i, n, d ->
                showAddDialog = false
                scope.launch { vm.addItem(c, i, n, d) }
            },
            onDismiss = { showAddDialog = false }
        )
    }

    editItem?.let { target ->
        ItemDialog(
            item = target,
            presetInv = "",
            allItems = items,
            onSave = { c, i, n, d ->
                editItem = null
                vm.updateItem(target.copy(category = c, inventoryNumber = i, name = n, description = d))
            },
            onDismiss = { editItem = null }
        )
    }

    deleteCandidate?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Удалить позицию?") },
            text = { Text("${target.inventoryNumber} — ${target.name}") },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    vm.deleteItem(target)
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Отмена") }
            }
        )
    }

    imported?.let { list ->
        AlertDialog(
            onDismissRequest = { imported = null },
            title = { Text("Импорт завершён") },
            text = { Text("Импортировано ${list.size} позиций") },
            confirmButton = {
                TextButton(onClick = {
                    imported = null
                    vm.replaceAll(list)
                }) { Text("Заменить список") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        imported = null
                        vm.appendAll(list)
                    }) { Text("Добавить к списку") }
                    TextButton(onClick = { imported = null }) { Text("Отмена") }
                }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Новая инвентаризация") },
            text = { Text("Начать новую инвентаризацию? Статусы будут сброшены.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    vm.resetAll()
                }) { Text("Начать") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun ItemCard(
    item: Item,
    onToggle: () -> Unit,
    onMissing: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val container = when (item.status) {
        Item.STATUS_FOUND -> Color(0xFFDCFCE7)
        Item.STATUS_MISSING -> Color(0xFFFEE2E2)
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = item.status == Item.STATUS_FOUND,
                onCheckedChange = { onToggle() }
            )
            Column(Modifier.weight(1f)) {
                Text(
                    item.inventoryNumber,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(item.name)
                if (item.description.isNotBlank()) {
                    Text(
                        item.description,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                item.scannedAt?.let {
                    Text(
                        "Проверено: " + timeFormat.format(Date(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (item.status == Item.STATUS_UNCHECKED) {
                IconButton(onClick = onMissing) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Нет на месте",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Изменить")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить")
            }
        }
    }
}

/**
 * Диалог добавления/редактирования позиции. Используется также на экране
 * сканирования (добавление позиции по неопознанному коду).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ItemDialog(
    item: Item?,
    presetInv: String,
    allItems: List<Item>,
    onSave: (category: String, inv: String, name: String, desc: String) -> Unit,
    onDismiss: () -> Unit
) {
    var category by remember(item?.id, presetInv) { mutableStateOf(item?.category ?: "") }
    var inv by remember(item?.id, presetInv) { mutableStateOf(item?.inventoryNumber ?: presetInv) }
    var name by remember(item?.id, presetInv) { mutableStateOf(item?.name ?: "") }
    var desc by remember(item?.id, presetInv) { mutableStateOf(item?.description ?: "") }
    var catExpanded by remember { mutableStateOf(false) }

    val categories = (
        allItems.map { it.category }.filter { it.isNotBlank() } +
            listOf("Системные блоки", "Мониторы", "Клавиатуры", "Ноутбуки", "Прочее")
        ).distinct()

    val invTrim = inv.trim()
    val nameTrim = name.trim()
    val invEmpty = invTrim.isEmpty()
    val nameEmpty = nameTrim.isEmpty()
    val duplicate = allItems.any { it.inventoryNumber == invTrim && it.id != (item?.id ?: 0L) }
    val valid = !invEmpty && !nameEmpty && !duplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Новая позиция" else "Редактирование") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                ExposedDropdownMenuBox(
                    expanded = catExpanded,
                    onExpandedChange = { catExpanded = it }
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Категория") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    ExposedDropdownMenu(
                        expanded = catExpanded,
                        onDismissRequest = { catExpanded = false }
                    ) {
                        categories.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c) },
                                onClick = { category = c; catExpanded = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = inv,
                    onValueChange = { inv = it },
                    label = { Text("Инв. №") },
                    singleLine = true,
                    isError = invEmpty || duplicate,
                    supportingText = {
                        when {
                            duplicate -> Text(
                                "Позиция с таким инв. № уже есть",
                                color = MaterialTheme.colorScheme.error
                            )
                            invEmpty -> Text(
                                "Укажите инвентарный номер",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    isError = nameEmpty,
                    supportingText = {
                        if (nameEmpty) Text(
                            "Укажите название",
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Описание") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(category.trim(), invTrim, nameTrim, desc.trim()) }
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
