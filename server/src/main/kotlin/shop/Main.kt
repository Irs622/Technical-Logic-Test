package shop

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main() {
    val app = App()
    val alerter = System.getenv("ALERT_WEBHOOK_URL")?.takeIf { it.isNotBlank() }
        ?.let { CompositeAlerter(listOf(LogAlerter(), WebhookAlerter(it))) } ?: LogAlerter()
    Workers(app.inv, app.db, CoroutineScope(SupervisorJob() + Dispatchers.Default), alerter).start()
    embeddedServer(Netty, port = System.getenv("PORT")?.toInt() ?: 8080, host = "0.0.0.0") { module(app) }
        .start(wait = true)
}
