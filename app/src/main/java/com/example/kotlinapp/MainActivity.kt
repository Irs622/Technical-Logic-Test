package com.example.kotlinapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.kotlinapp.ui.FlashViewModel
import com.example.kotlinapp.ui.ShopViewModel
import com.example.kotlinapp.ui.screens.*
import com.example.kotlinapp.ui.theme.KotlinAppTheme
import com.example.kotlinapp.ui.theme.Toko

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KotlinAppTheme { TokoApp() } }
    }
}

private fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo("home") { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun TokoApp() {
    val nav = rememberNavController()
    val vm: ShopViewModel = viewModel()
    val flash: FlashViewModel = viewModel()
    val cart by vm.cart.collectAsStateWithLifecycle()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val isTab = Tab.entries.any { it.route == route }

    Scaffold(
        containerColor = Toko.colors.paper,
        bottomBar = { if (isTab) TokoBottomNav(route, vm.cartCount(cart)) { nav.goTab(it.route) } },
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize().padding(pad)) {
            composable("home") { HomeScreen(vm, flash, { nav.navigate("product/$it") }, { nav.navigate("flash") }) }
            composable("categories") { CategoriesScreen(vm) { nav.goTab("home") } }
            composable("cart") { CartScreen(vm, { nav.navigate("product/$it") }, { nav.navigate("checkout") }, { nav.goTab("home") }) }
            composable("account") {
                AccountScreen(vm, { nav.navigate("flash") }, { vm.showReceipt(it); nav.navigate("receipt") })
            }
            composable("product/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                ProductDetailScreen(it.arguments!!.getLong("id"), vm, { nav.popBackStack() }, { nav.goTab("cart") })
            }
            composable("checkout") {
                CheckoutScreen(vm, { nav.popBackStack() }, {
                    nav.navigate("receipt") { popUpTo("cart") { inclusive = false } }
                }, { nav.goTab("account") })
            }
            composable("receipt") { ReceiptScreen(vm, { nav.goTab("account") }, { nav.goTab("home") }) }
            composable("flash") { FlashScreen(flash) { nav.popBackStack() } }
        }
    }
}
