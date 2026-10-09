package shop

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Waiting room (admission control). Hanya sebagian kecil pembeli (default 5x alokasi) yang diizinkan
 * menyentuh MySQL; sisanya mendapat 202 QUEUED dan polling. Setiap reservasi yang dilepas (kedaluwarsa/batal)
 * membuka [factor] slot tambahan. Ini mengurangi tekanan pada hot row; BUKAN penjaga oversell
 * (penjaganya tetap UPDATE bersyarat + CHECK).
 */
class Admission(private val factor: Int = 5) {
    private class State(allocation: Int, factor: Int) {
        val limit = AtomicInteger(allocation * factor)
        val next = AtomicInteger()
        val ranks = ConcurrentHashMap<Long, Int>()
    }

    private val states = ConcurrentHashMap<Long, State>()

    private fun state(campaignId: Long, allocation: Int) = states.computeIfAbsent(campaignId) { State(allocation, factor) }

    fun rank(campaignId: Long, allocation: Int, userId: Long): Int {
        val s = state(campaignId, allocation)
        return s.ranks.computeIfAbsent(userId) { s.next.getAndIncrement() }
    }

    fun isAdmitted(campaignId: Long, allocation: Int, userId: Long): Boolean {
        val s = state(campaignId, allocation)
        return rank(campaignId, allocation, userId) < s.limit.get()
    }

    /** Perkiraan posisi antrean (1 = berikutnya). 0 bila sudah diizinkan. */
    fun position(campaignId: Long, allocation: Int, userId: Long): Int {
        val s = state(campaignId, allocation)
        return maxOf(0, rank(campaignId, allocation, userId) - s.limit.get() + 1)
    }

    fun onRelease(campaignId: Long) { states[campaignId]?.limit?.addAndGet(factor) }
    fun reset() = states.clear()
}
