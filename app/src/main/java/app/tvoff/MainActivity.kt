package app.tvoff

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.*

data class Tv(val ip: String, val type: String, val port: Int)

/**
 * Без UI и без фоновых сервисов: запустился -> нашёл ТВ -> отправил команду -> закрылся.
 */
class MainActivity : Activity() {

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // ТВ используют самоподписанные сертификаты, поэтому проверка отключена (только для команд в локальной сети)
    private val client: OkHttpClient by lazy {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val msg = try { work() } catch (e: Exception) { "Ошибка: ${e.message}" }
            runOnUiThread {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    private fun work(): String {
        val p = getSharedPreferences("tv", MODE_PRIVATE)

        // 1. Сначала пробуем запомненный IP (быстро)
        val ip = p.getString("ip", null)
        if (ip != null) {
            val cached = Tv(ip, p.getString("type", "")!!, p.getInt("port", 0))
            if (isOpen(cached.ip, cached.port) && send(cached, p)) return "ТВ выключается"
        }

        // 2. IP сменился или ТВ не отвечает: ищем заново по сети
        val found = scan() ?: return "ТВ не найден в Wi-Fi (он должен быть включён)"
        p.edit().putString("ip", found.ip).putString("type", found.type).putInt("port", found.port).apply()
        return if (send(found, p)) "ТВ выключается (${found.ip})"
        else "Не удалось. Подтвердите запрос на экране ТВ и повторите"
    }

    // ---------- Поиск ТВ ----------

    private val ports = listOf(8002 to "samsung", 8001 to "samsung", 3001 to "lg", 3000 to "lg", 8060 to "roku")

    private fun localIp(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress

    private fun isOpen(ip: String, port: Int, timeoutMs: Int = 400): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs); true }
    } catch (e: Exception) { false }

    private fun scan(): Tv? {
        val base = (localIp() ?: return null).substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(128)
        try {
            val tasks = (1..254).flatMap { i ->
                ports.map { (port, type) ->
                    Callable { if (isOpen("$base.$i", port, 300)) Tv("$base.$i", type, port) else null }
                }
            }
            return pool.invokeAll(tasks).mapNotNull { runCatching { it.get() }.getOrNull() }.firstOrNull()
        } finally { pool.shutdown() }
    }

    // ---------- Команды выключения ----------

    private fun send(tv: Tv, p: SharedPreferences): Boolean = when (tv.type) {
        "samsung" -> samsung(tv, p)
        "lg" -> lg(tv, p)
        "roku" -> roku(tv)
        else -> false
    }

    private fun roku(tv: Tv): Boolean {
        val req = Request.Builder().url("http://${tv.ip}:8060/keypress/PowerOff")
            .post(ByteArray(0).toRequestBody()).build()
        return client.newCall(req).execute().use { it.isSuccessful }
    }

    private fun samsung(tv: Tv, p: SharedPreferences): Boolean {
        val name = Base64.encodeToString("TVOff".toByteArray(), Base64.NO_WRAP)
        val scheme = if (tv.port == 8002) "wss" else "ws"
        var url = "$scheme://${tv.ip}:${tv.port}/api/v2/channels/samsung.remote.control?name=$name"
        val token = p.getString("samsung_token", "")
        if (!token.isNullOrEmpty()) url += "&token=$token"

        val key = """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"KEY_POWER","Option":"false","TypeOfRemote":"SendRemoteKey"}}"""
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = JSONObject(text)
                if (j.optString("event") == "ms.channel.connect") {
                    j.optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() }
                        ?.let { p.edit().putString("samsung_token", it).apply() }
                    webSocket.send(key)
                    ok.set(true)
                    webSocket.close(1000, null)
                    latch.countDown()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { latch.countDown() }
        })
        latch.await(20, TimeUnit.SECONDS) // при первом запуске ждём подтверждения на ТВ
        if (!ok.get()) ws.cancel()
        return ok.get()
    }

    private fun lg(tv: Tv, p: SharedPreferences): Boolean {
        val url = if (tv.port == 3001) "wss://${tv.ip}:3001" else "ws://${tv.ip}:3000"
        val clientKey = p.getString("lg_key", "") ?: ""
        val register = """{"type":"register","id":"reg","payload":{"forcePairing":false,"pairingType":"PROMPT","client-key":"$clientKey","manifest":{"manifestVersion":1,"permissions":["CONTROL_POWER"]}}}"""
        val off = """{"type":"request","id":"off","uri":"ssap://system/turnOff"}"""
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(register) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = JSONObject(text)
                when {
                    j.optString("type") == "registered" -> {
                        j.optJSONObject("payload")?.optString("client-key")?.takeIf { it.isNotEmpty() }
                            ?.let { p.edit().putString("lg_key", it).apply() }
                        webSocket.send(off)
                    }
                    j.optString("id") == "off" -> {
                        ok.set(j.optString("type") == "response")
                        webSocket.close(1000, null)
                        latch.countDown()
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { latch.countDown() }
        })
        latch.await(20, TimeUnit.SECONDS)
        if (!ok.get()) ws.cancel()
        return ok.get()
    }
}
