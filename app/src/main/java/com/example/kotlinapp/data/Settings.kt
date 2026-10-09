package com.example.kotlinapp.data

import android.content.Context
import java.util.UUID

/** Pengaturan + kunci idempotensi yang belum selesai (padanan tabel pending_purchase). Diabstraksi agar bisa diuji di JVM. */
interface AppPrefs {
    var baseUrl: String
    var userId: Long
    /** Satu key per niat beli sampai hasil final diterima. */
    fun purchaseKey(campaignId: Long): String
    fun clearPurchaseKey(campaignId: Long)
    fun checkoutKey(): String
    fun clearCheckoutKey()
}

class Settings(context: Context) : AppPrefs {
    private val sp = context.getSharedPreferences("toko", Context.MODE_PRIVATE)

    override var baseUrl: String
        get() = sp.getString("base_url", DEFAULT_URL)!!
        set(v) = sp.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    override var userId: Long
        get() = sp.getLong("user_id", 1L)
        set(v) = sp.edit().putLong("user_id", v).apply()

    /** Ambil key yang tersimpan atau buat baru: SATU key per niat beli sampai hasil final diterima. */
    override fun purchaseKey(campaignId: Long): String {
        val name = "pk_${userId}_$campaignId"
        return sp.getString(name, null) ?: UUID.randomUUID().toString().also { sp.edit().putString(name, it).apply() }
    }

    override fun clearPurchaseKey(campaignId: Long) = sp.edit().remove("pk_${userId}_$campaignId").apply()

    override fun checkoutKey(): String = sp.getString("ck_$userId", null) ?: UUID.randomUUID().toString().also { sp.edit().putString("ck_$userId", it).apply() }
    override fun clearCheckoutKey() = sp.edit().remove("ck_$userId").apply()

    companion object { const val DEFAULT_URL = "http://10.0.2.2:8080" }
}
