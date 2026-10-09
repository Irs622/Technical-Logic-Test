package shop

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Saluran alert untuk on-call (docs/backend.md A.5). */
interface Alerter {
    fun alert(title: String, details: List<String>)
}

class LogAlerter : Alerter {
    private val log = LoggerFactory.getLogger("alert")
    override fun alert(title: String, details: List<String>) = log.error("ALERT {} {}", title, details)
}

/** POST JSON ke webhook (Slack/PagerDuty/Alertmanager-compatible). Diaktifkan lewat env ALERT_WEBHOOK_URL. */
class WebhookAlerter(private val url: String) : Alerter {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val log = LoggerFactory.getLogger("alert")

    override fun alert(title: String, details: List<String>) {
        val body = obj("text" to "[toko-simulasi] $title", "details" to details).toString()
        val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        try {
            val r = client.send(req, HttpResponse.BodyHandlers.discarding())
            if (r.statusCode() >= 300) log.warn("webhook alert HTTP {}", r.statusCode())
        } catch (e: Exception) {
            log.warn("webhook alert gagal: {}", e.message)
        }
    }
}

class CompositeAlerter(private val all: List<Alerter>) : Alerter {
    override fun alert(title: String, details: List<String>) = all.forEach { runCatching { it.alert(title, details) } }
}
