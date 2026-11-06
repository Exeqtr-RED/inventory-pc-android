package ru.pcinventory.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.pcinventory.app.ui.screens.ListScreen
import ru.pcinventory.app.ui.screens.ScanScreen
import ru.pcinventory.app.ui.screens.StickersScreen
import ru.pcinventory.app.ui.theme.InventoryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            InventoryTheme {
                val vm: InventoryViewModel = viewModel()
                AppRoot(vm)
            }
        }
    }
}

@Composable
fun AppRoot(vm: InventoryViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "list") {
        composable("list") {
            ListScreen(
                vm = vm,
                onScan = { nav.navigate("scan") },
                onStickers = { nav.navigate("stickers") }
            )
        }
        composable("scan") { ScanScreen(vm = vm, onBack = { nav.popBackStack() }) }
        composable("stickers") { StickersScreen(vm = vm, onBack = { nav.popBackStack() }) }
    }
}
