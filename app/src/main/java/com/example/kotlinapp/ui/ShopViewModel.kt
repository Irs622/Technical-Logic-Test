package com.example.kotlinapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kotlinapp.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CatalogState(
    val loading: Boolean = true, val error: String? = null,
    val products: List<Product> = emptyList(), val categories: List<Category> = emptyList(),
    val selected: String? = null, val query: String = "",
)

data class CheckoutState(val submitting: Boolean = false, val error: String? = null)

data class AccountState(
    val balance: Long? = null, val shopOrders: List<Receipt> = emptyList(), val flashOrders: List<FlashOrder> = emptyList(),
    val error: String? = null, val message: String? = null,
)

/** Katalog, keranjang, checkout, akun. State mengalir satu arah (UDF). */
class ShopViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    val repo = ShopRepository(settings, Api(settings))

    private val _catalog = MutableStateFlow(CatalogState())
    val catalog: StateFlow<CatalogState> = _catalog.asStateFlow()

    private val _cart = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val cart: StateFlow<Map<Long, Int>> = _cart.asStateFlow()
    private val known = HashMap<Long, Product>()

    private val _checkout = MutableStateFlow(CheckoutState())
    val checkout: StateFlow<CheckoutState> = _checkout.asStateFlow()

    private val _receipt = MutableStateFlow<Receipt?>(null)
    val receipt: StateFlow<Receipt?> = _receipt.asStateFlow()

    private val _account = MutableStateFlow(AccountState())
    val account: StateFlow<AccountState> = _account.asStateFlow()

    private val _product = MutableStateFlow<Res<Product>?>(null)
    val product: StateFlow<Res<Product>?> = _product.asStateFlow()

    private var searchJob: Job? = null

    init { loadCatalog(); refreshAccount() }

    // ---- Katalog ----
    fun loadCatalog() {
        val s = _catalog.value
        _catalog.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val cats = repo.categories()
            val prods = repo.products(s.selected, s.query)
            when {
                prods is Res.Err -> _catalog.update { it.copy(loading = false, error = prods.message) }
                else -> {
                    val list = (prods as Res.Ok).value
                    list.forEach { known[it.id] = it }
                    _catalog.update { it.copy(loading = false, products = list, categories = (cats as? Res.Ok)?.value ?: it.categories) }
                }
            }
        }
    }

    fun selectCategory(name: String?) { _catalog.update { it.copy(selected = name) }; loadCatalog() }

    fun setQuery(q: String) {
        _catalog.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(300); loadCatalog() }
    }

    fun openProduct(id: Long) {
        _product.value = null
        viewModelScope.launch {
            val r = repo.product(id)
            if (r is Res.Ok) known[id] = r.value
            _product.value = r
        }
    }

    // ---- Keranjang ----
    fun add(p: Product, qty: Int = 1) {
        known[p.id] = p
        _cart.update { it + (p.id to ((it[p.id] ?: 0) + qty).coerceAtMost(minOf(99, p.stock.coerceAtLeast(1)))) }
    }
    fun setQty(id: Long, qty: Int) = _cart.update { if (qty <= 0) it - id else it + (id to qty) }
    fun remove(id: Long) = _cart.update { it - id }
    fun productOf(id: Long): Product? = known[id]
    fun cartCount(c: Map<Long, Int> = _cart.value) = c.values.sum()
    fun subtotal(c: Map<Long, Int> = _cart.value) = c.entries.sumOf { (id, q) -> (known[id]?.price ?: 0L) * q }

    // ---- Checkout ----
    fun pay(shipping: String, onDone: () -> Unit) {
        if (_checkout.value.submitting) return
        _checkout.value = CheckoutState(submitting = true)
        viewModelScope.launch {
            when (val r = repo.checkout(_cart.value, shipping)) {
                is Res.Ok -> {
                    _receipt.value = r.value
                    _cart.value = emptyMap()
                    _checkout.value = CheckoutState()
                    refreshAccount(); loadCatalog()
                    onDone()
                }
                is Res.Err -> _checkout.value = CheckoutState(error = r.message)
            }
        }
    }
    fun clearCheckoutError() { _checkout.value = CheckoutState() }
    fun showReceipt(r: Receipt) { _receipt.value = r }

    // ---- Akun ----
    fun refreshAccount() {
        viewModelScope.launch {
            val b = repo.balance()
            val so = repo.shopOrders()
            val fo = repo.flashOrders()
            _account.update {
                it.copy(
                    balance = (b as? Res.Ok)?.value ?: it.balance,
                    shopOrders = (so as? Res.Ok)?.value ?: it.shopOrders,
                    flashOrders = (fo as? Res.Ok)?.value ?: it.flashOrders,
                    error = (b as? Res.Err)?.message,
                )
            }
        }
    }

    fun topup() {
        viewModelScope.launch {
            when (val r = repo.topup(100_000)) {
                is Res.Ok -> _account.update { it.copy(balance = r.value, message = "Saldo bertambah Rp 100.000.") }
                is Res.Err -> _account.update { it.copy(message = r.message) }
            }
        }
    }

    fun updateServer(url: String, userId: Long) {
        settings.baseUrl = url
        settings.userId = userId
        _account.update { it.copy(message = "Pengaturan disimpan.", balance = null, shopOrders = emptyList(), flashOrders = emptyList()) }
        loadCatalog(); refreshAccount()
    }
}
