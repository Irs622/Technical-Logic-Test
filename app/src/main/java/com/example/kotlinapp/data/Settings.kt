package com.example.kotlinapp.data

import android.content.Context
import java.util.UUID

/** Pengaturan ringan + kunci idempotensi yang belum selesai (padanan tabel pending_purchase). */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("toko", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", DEFAULT_URL)!!
        set(v) = sp.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    var userId: Long
        get() = sp.getLong("user_id", 1L)
        set(v) = sp.edit().putLong("user_id", v).apply()

    /** Ambil key yang tersimpan atau buat baru: SATU key per niat beli sampai hasil final diterima. */
    fun purchaseKey(campaignId: Long): String {
        val name = "pk_${userId}_$campaignId"
        return sp.getString(name, null) ?: UUID.randomUUID().toString().also { sp.edit().putString(name, it).apply() }
    }

    fun clearPurchaseKey(campaignId: Long) = sp.edit().remove("pk_${userId}_$campaignId").apply()

    fun checkoutKey(): String = sp.getString("ck_$userId", null) ?: UUID.randomUUID().toString().also { sp.edit().putString("ck_$userId", it).apply() }
    fun clearCheckoutKey() = sp.edit().remove("ck_$userId").apply()

    companion object { const val DEFAULT_URL = "http://10.0.2.2:8080" }
}
