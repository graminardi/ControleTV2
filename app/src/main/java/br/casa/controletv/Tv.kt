package br.casa.controletv

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

enum class Brand(val label: String) {
    SAMSUNG("Samsung"), LG("LG"), ROKU("Roku/TCL"), SONY("Sony"), OUTRA("Marca não suportada")
}

data class Tv(val ip: String, val name: String, val brand: Brand)

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)
    fun get(k: String): String? = p.getString(k, null)
    fun put(k: String, v: String) = p.edit().putString(k, v).apply()
}

object Discovery {
    private val quick = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()

    private fun brandOf(t: String) = when {
        "roku" in t -> Brand.ROKU
        "samsung" in t -> Brand.SAMSUNG
        "webos" in t || "lg electronics" in t || "lge" in t -> Brand.LG
        "sony" in t || "bravia" in t -> Brand.SONY
        else -> Brand.OUTRA
    }

    private fun httpGet(url: String): String? = try {
        quick.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() }
    } catch (e: Exception) { null }

    private fun tag(xml: String, t: String) =
        Regex("<$t>(.*?)</$t>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    private fun arpMac(ip: String): String? = try {
        File("/proc/net/arp").readLines().drop(1).map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size > 3 && it[0] == ip && it[3] != "00:00:00:00:00:00" }?.get(3)
    } catch (e: Exception) { null }

    suspend fun scan(ctx: Context, st: Store): List<Tv> = withContext(Dispatchers.IO) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wm.createMulticastLock("ssdp").apply { setReferenceCounted(false); acquire() }
        val text = LinkedHashMap<String, StringBuilder>()
        val loc = HashMap<String, String>()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 800
                s.broadcast = true
                val group = InetAddress.getByName("239.255.255.250")
                val targets = listOf(
                    "ssdp:all", "roku:ecp",
                    "urn:dial-multiscreen-org:service:dial:1",
                    "urn:schemas-upnp-org:device:MediaRenderer:1"
                )
                repeat(2) {
                    for (target in targets) {
                        val m = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n").toByteArray()
                        s.send(DatagramPacket(m, m.size, group, 1900))
                    }
                }
                val end = System.currentTimeMillis() + 5000
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        s.receive(pkt)
                        val t = String(pkt.data, 0, pkt.length)
                        val ip = pkt.address.hostAddress ?: continue
                        text.getOrPut(ip) { StringBuilder() }.append(t).append('\n')
                        Regex("(?im)^location:\\s*(\\S+)").find(t)?.let { loc.putIfAbsent(ip, it.groupValues[1]) }
                    } catch (e: SocketTimeoutException) { /* segue ouvindo */ }
                }
            }
        } finally { lock.release() }

        coroutineScope {
            text.keys.map { ip ->
                async {
                    val xml = loc[ip]?.let { httpGet(it) } ?: ""
                    val all = (text[ip].toString() + xml).lowercase()
                    val brand = brandOf(all)
                    if (brand == Brand.OUTRA && "mediarenderer" !in all && "dial-multiscreen" !in all) return@async null
                    if (st.get("mac:$ip") == null) arpMac(ip)?.let { st.put("mac:$ip", it) }
                    if (brand == Brand.SAMSUNG && st.get("mac:$ip") == null) {
                        httpGet("http://$ip:8001/api/v2/")?.let { j ->
                            Regex("\"wifiMac\"\\s*:\\s*\"([0-9A-Fa-f:]{17})\"").find(j)
                                ?.let { st.put("mac:$ip", it.groupValues[1]) }
                        }
                    }
                    Tv(ip, tag(xml, "friendlyName") ?: "${brand.label} ($ip)", brand)
                }
            }.awaitAll().filterNotNull().sortedBy { it.ip }
        }
    }
}

object Control {
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    // Cliente que aceita certificado autoassinado (as TVs usam), só para a rede local.
    private val tls: OkHttpClient by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
        OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, tm).hostnameVerifier { _, _ -> true }
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    suspend fun power(tv: Tv, on: Boolean, st: Store): String = withContext(Dispatchers.IO) {
        try {
            when (tv.brand) {
                Brand.ROKU -> post("http://${tv.ip}:8060/keypress/${if (on) "PowerOn" else "PowerOff"}", "", null)
                Brand.SONY -> post(
                    "http://${tv.ip}/sony/system",
                    """{"method":"setPowerStatus","id":55,"params":[{"status":$on}],"version":"1.0"}""",
                    st.get("psk:${tv.ip}") ?: "0000"
                )
                Brand.SAMSUNG -> if (on) wake(tv, st) else samsungOff(tv.ip, st)
                Brand.LG -> if (on) wake(tv, st) else lgOff(tv.ip, st)
                Brand.OUTRA -> "Marca ainda não suportada"
            }
        } catch (e: Exception) { "Erro: ${e.message}" }
    }

    private fun post(url: String, body: String, psk: String?): String {
        val rb = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType()))
        if (psk != null) rb.header("X-Auth-PSK", psk)
        http.newCall(rb.build()).execute().use {
            return if (it.isSuccessful) "Comando enviado" else "A TV respondeu ${it.code}"
        }
    }

    private fun wake(tv: Tv, st: Store): String {
        val mac = st.get("mac:${tv.ip}")?.trim().orEmpty()
        val b = mac.split(":", "-").mapNotNull { it.toIntOrNull(16)?.toByte() }
        if (b.size != 6) return "Informe o MAC da TV no campo acima para poder ligá-la"
        val pkt = ByteArray(6) { 0xFF.toByte() } + ByteArray(96) { b[it % 6] }
        DatagramSocket().use { s ->
            s.broadcast = true
            for (port in listOf(9, 7))
                s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName("255.255.255.255"), port))
        }
        return "Sinal de ligar enviado (Wake-on-LAN)"
    }

    private suspend fun samsungOff(ip: String, st: Store): String {
        val name = Base64.encodeToString("ControleTV".toByteArray(), Base64.NO_WRAP)
        val token = st.get("token:$ip")?.let { "&token=$it" } ?: ""
        val path = "/api/v2/channels/samsung.remote.control?name=$name$token"
        // 2018+ usa wss:8002; alguns 2016-2017 só respondem em ws:8001
        for (base in listOf("wss://$ip:8002", "ws://$ip:8001")) {
            samsungTry(base + path, ip, st)?.let { return it }
        }
        return "Não consegui conectar na TV (ligada e no mesmo Wi-Fi?)"
    }

    private suspend fun samsungTry(url: String, ip: String, st: Store): String? {
        val done = CompletableDeferred<String>()
        val ws = tls.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("ms.channel.connect")) {
                    Regex("\"token\"\\s*:\\s*\"(\\d+)\"").find(text)?.let { st.put("token:$ip", it.groupValues[1]) }
                    webSocket.send("""{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"KEY_POWER","Option":"false","TypeOfRemote":"SendRemoteKey"}}""")
                    done.complete("Comando enviado")
                } else if (text.contains("unauthorized") || text.contains("timeOut")) {
                    done.complete("A TV não autorizou. Aceite o pedido na tela da TV e tente de novo.")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.complete("__fail") }
        })
        val r = withTimeoutOrNull(20000) { done.await() }
        delay(500); ws.close(1000, null)
        return when (r) {
            "__fail" -> null
            null -> "Sem resposta. Aceite o pedido na TV e tente de novo."
            else -> r
        }
    }

    private suspend fun lgOff(ip: String, st: Store): String {
        for (url in listOf("wss://$ip:3001", "ws://$ip:3000")) {
            lgTry(url, ip, st)?.let { return it }
        }
        return "Não consegui conectar na TV (ligada e no mesmo Wi-Fi?)"
    }

    private suspend fun lgTry(url: String, ip: String, st: Store): String? {
        val done = CompletableDeferred<String>()
        val payload = JSONObject().put("pairingType", "PROMPT")
            .put("manifest", JSONObject().put("manifestVersion", 1)
                .put("permissions", JSONArray(listOf("CONTROL_POWER"))))
        st.get("lgkey:$ip")?.let { payload.put("client-key", it) }
        val reg = JSONObject().put("type", "register").put("id", "reg").put("payload", payload)

        val ws = tls.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(reg.toString()) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = JSONObject(text)
                when {
                    j.optString("type") == "registered" -> {
                        j.optJSONObject("payload")?.optString("client-key")?.takeIf { it.isNotEmpty() }
                            ?.let { st.put("lgkey:$ip", it) }
                        webSocket.send("""{"id":"off","type":"request","uri":"ssap://system/turnOff"}""")
                    }
                    j.optString("id") == "off" -> done.complete("Comando enviado")
                    j.optString("type") == "error" -> done.complete("A TV recusou: ${j.optString("error")}")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.complete("__fail") }
        })
        val r = withTimeoutOrNull(25000) { done.await() }
        ws.close(1000, null)
        return when (r) {
            "__fail" -> null
            null -> "Sem resposta. Aceite o pedido de pareamento na TV e tente de novo."
            else -> r
        }
    }
}
