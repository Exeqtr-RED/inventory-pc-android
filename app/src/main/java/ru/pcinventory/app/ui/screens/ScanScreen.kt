package ru.pcinventory.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import ru.pcinventory.app.InventoryViewModel
import java.util.concurrent.Executors

/** Результат последнего сканирования для карточки на экране. */
private data class ScanUi(val kind: InventoryViewModel.ScanResult, val code: String, val name: String)

@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    ExperimentalGetImage::class
)
@Composable
fun ScanScreen(vm: InventoryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val items by vm.items.collectAsStateWithLifecycle()

    // ----------------------------------------------------------- разрешение
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    DisposableEffect(Unit) {
        if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA)
        onDispose { }
    }

    // ------------------------------------------------- анализатор и состояние
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }

    var lastCode by remember { mutableStateOf("") }
    var lastTime by remember { mutableStateOf(0L) }
    var result by remember { mutableStateOf<ScanUi?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var manual by remember { mutableStateOf("") }
    var addDialogCode by remember { mutableStateOf<String?>(null) }

    fun onCode(code: String) {
        val now = System.currentTimeMillis()
        if (code == lastCode && now - lastTime < 1500) return // дедупликация 1.5 c
        lastCode = code
        lastTime = now
        scope.launch {
            val res = vm.scan(code)
            val known = items.firstOrNull { it.inventoryNumber == code.trim() }
            result = when (res) {
                InventoryViewModel.ScanResult.FOUND -> {
                    vibrate(context)
                    ScanUi(res, code, known?.name ?: "")
                }
                InventoryViewModel.ScanResult.ALREADY -> ScanUi(res, code, known?.name ?: "")
                InventoryViewModel.ScanResult.NOT_FOUND -> ScanUi(res, code, "")
            }
        }
    }

    // ------------------------------------------------------------ разрешение нет
    if (!hasPermission) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Text(
                "Для сканирования QR-кодов нужен доступ к камере. " +
                    "Разрешите использование камеры в настройках.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.padding(top = 16.dp))
            Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Дать разрешение")
            }
            Spacer(Modifier.padding(top = 8.dp))
            androidx.compose.material3.TextButton(onClick = onBack) { Text("Назад") }
        }
        return
    }

    // -------------------------------------------------------------- камера
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    LaunchedEffect(previewView, lifecycleOwner) {
        runCatching {
            val provider = ProcessCameraProvider.getInstance(context).get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executor) { proxy ->
                        processFrame(proxy, scanner) { code -> onCode(code) }
                    }
                }
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Верхняя панель поверх превью
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x66000000))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = Color.White
                )
            }
            Text("Сканирование", color = Color.White)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
            }) {
                Icon(
                    imageVector = if (torchOn) Icons.Default.FlashOff else Icons.Default.FlashOn,
                    contentDescription = "Фонарик",
                    tint = Color.White
                )
            }
        }

        // Карточка результата
        result?.let { r ->
            val (bg, text) = when (r.kind) {
                InventoryViewModel.ScanResult.FOUND ->
                    Color(0xFFDCFCE7) to "✓ Найдено: ${r.name} (${r.code})"
                InventoryViewModel.ScanResult.ALREADY ->
                    Color(0xFFE5E7EB) to "Уже отмечено: ${r.name}"
                InventoryViewModel.ScanResult.NOT_FOUND ->
                    Color(0xFFFEE2E2) to "Код не найден: ${r.code}"
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = bg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (r.kind == InventoryViewModel.ScanResult.NOT_FOUND) {
                        Spacer(Modifier.padding(top = 8.dp))
                        Button(onClick = { addDialogCode = r.code }) {
                            Text("Добавить позицию")
                        }
                    }
                }
            }
        }

        // Ручной ввод инвентарного номера
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(bottom = if (result != null) 110.dp else 16.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                label = { Text("Инв. № вручную") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { if (manual.isNotBlank()) onCode(manual.trim()) }) {
                Icon(Icons.Default.Search, contentDescription = "Найти")
            }
        }
    }

    // Диалог добавления позиции по неопознанному коду
    addDialogCode?.let { code ->
        ItemDialog(
            item = null,
            presetInv = code,
            allItems = items,
            onSave = { c, i, n, d ->
                addDialogCode = null
                scope.launch {
                    if (vm.addItem(c, i, n, d)) {
                        result = ScanUi(
                            InventoryViewModel.ScanResult.FOUND,
                            i,
                            n
                        )
                    }
                }
            },
            onDismiss = { addDialogCode = null }
        )
    }
}

/** Один кадр → ML Kit. @OptIn нужен для доступа к proxy.image. */
@OptIn(ExperimentalGetImage::class)
private fun processFrame(proxy: ImageProxy, scanner: BarcodeScanner, onCode: (String) -> Unit) {
    val mediaImage = proxy.image
    if (mediaImage == null) {
        proxy.close()
        return
    }
    val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull()?.rawValue?.let(onCode)
        }
        .addOnCompleteListener { proxy.close() }
}

private fun vibrate(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
}
