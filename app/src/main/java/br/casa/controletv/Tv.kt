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
    fun saveTvs(l: List<Tv>) = put("tvs", JSONArray(l.map {
        JSONObject().put("ip", it.ip).put("name", it.name).put("brand", it.brand.name)
    }).toString())
    fun loadTvs(): List<Tv> = try {
        val a = JSONArray(get("tvs") ?: "[]")
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Tv(o.getString("ip"), o.getString("name"), Brand.valueOf(o.getString("brand")))
        }
    } catch (e: Exception) { emptyList() }
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
                s.soTimeout = 300
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
                            "MAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $target\r\n\r\n").toByteArray()
                        s.send(DatagramPacket(m, m.size, group, 1900))
                    }
                }
                val end = System.currentTimeMillis() + 3500
                val buf = ByteArray(4096)
                var last = 0L
                while (System.currentTimeMillis() < end) {
                    if (last > 0 && System.currentTimeMillis() - last > 1200) break
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        s.receive(pkt)
                        last = System.currentTimeMillis()
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
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
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

    suspend fun action(tv: Tv, act: String, st: Store, arg: String = ""): String = withContext(Dispatchers.IO) {
        try {
            when (tv.brand) {
                Brand.SAMSUNG -> when (act) {
                    "chup" -> samsungKeys(tv.ip, st, listOf("KEY_CHUP"))
                    "chdown" -> samsungKeys(tv.ip, st, listOf("KEY_CHDOWN"))
                    "chnum" -> samsungKeys(tv.ip, st, arg.map { "KEY_$it" } + "KEY_ENTER")
                    else -> post("http://${tv.ip}:8001/api/v2/applications/111299001912", "", null)
                }
                Brand.LG -> when (act) {
                    "chup" -> lgCmd(tv.ip, st, "ssap://tv/channelUp")
                    "chdown" -> lgCmd(tv.ip, st, "ssap://tv/channelDown")
                    "yt" -> lgCmd(tv.ip, st, "ssap://system.launcher/launch", JSONObject().put("id", "youtube.leanback.v4"))
                    else -> "Ação não disponível para LG"
                }
                Brand.ROKU -> when (act) {
                    "chup" -> post("http://${tv.ip}:8060/keypress/ChannelUp", "", null)
                    "chdown" -> post("http://${tv.ip}:8060/keypress/ChannelDown", "", null)
                    "yt" -> post("http://${tv.ip}:8060/launch/837", "", null)
                    else -> "Ação não disponível para Roku"
                }
                else -> "Ação ainda não disponível para esta marca"
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
            repeat(3) {
                for (port in listOf(9, 7))
                    s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName("255.255.255.255"), port))
            }
        }
        return "Sinal de ligar enviado (Wake-on-LAN)"
    }

    private suspend fun samsungOff(ip: String, st: Store) = samsungKeys(ip, st, listOf("KEY_POWER"))

    private suspend fun samsungKeys(ip: String, st: Store, keys: List<String>): String {
        val name = Base64.encodeToString("ControleTV".toByteArray(), Base64.NO_WRAP)
        val token = st.get("token:$ip")?.let { "&token=$it" } ?: ""
        val path = "/api/v2/channels/samsung.remote.control?name=$name$token"
        // 2018+ usa wss:8002; alguns 2016-2017 só respondem em ws:8001
        val all = listOf("wss://$ip:8002", "ws://$ip:8001")
        val saved = st.get("sbase:$ip")
        val bases = if (saved != null && saved in all) listOf(saved) + (all - saved) else all
        for (base in bases) {
            samsungSession(base + path, ip, st, keys)?.let {
                if (it == "Comando enviado") st.put("sbase:$ip", base)
                return it
            }
        }
        return "Não consegui conectar na TV (ligada e no mesmo Wi-Fi?)"
    }

    private suspend fun samsungSession(url: String, ip: String, st: Store, keys: List<String>): String? {
        val ready = CompletableDeferred<String>()
        val ws = tls.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("ms.channel.connect")) {
                    Regex("\"token\"\\s*:\\s*\"(\\d+)\"").find(text)?.let { st.put("token:$ip", it.groupValues[1]) }
                    ready.complete("ok")
                } else if (text.contains("unauthorized") || text.contains("timeOut")) {
                    ready.complete("A TV não autorizou. Aceite o pedido na tela da TV e tente de novo.")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ready.complete("__fail") }
        })
        val r = withTimeoutOrNull(20000) { ready.await() }
        val out: String? = when (r) {
            "__fail" -> null
            null -> "Sem resposta. Aceite o pedido na TV e tente de novo."
            "ok" -> {
                for ((i, k) in keys.withIndex()) {
                    if (i > 0) delay(250)
                    ws.send("""{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$k","Option":"false","TypeOfRemote":"SendRemoteKey"}}""")
                }
                "Comando enviado"
            }
            else -> r
        }
        ws.close(1000, null)
        return out
    }

    private suspend fun lgOff(ip: String, st: Store) = lgCmd(ip, st, "ssap://system/turnOff")

    private suspend fun lgCmd(ip: String, st: Store, uri: String, body: JSONObject? = null): String {
        for (url in listOf("wss://$ip:3001", "ws://$ip:3000")) {
            lgTry(url, ip, st, uri, body)?.let { return it }
        }
        return "Não consegui conectar na TV (ligada e no mesmo Wi-Fi?)"
    }

    private suspend fun lgTry(url: String, ip: String, st: Store, uri: String, body: JSONObject?): String? {
        val done = CompletableDeferred<String>()
        val payload = JSONObject().put("pairingType", "PROMPT")
            .put("manifest", JSONObject().put("manifestVersion", 1)
                .put("permissions", JSONArray(listOf("CONTROL_POWER", "CONTROL_INPUT_TV", "LAUNCH", "CONTROL_TV_SCREEN"))))
        st.get("lgkey2:$ip")?.let { payload.put("client-key", it) }
        val reg = JSONObject().put("type", "register").put("id", "reg").put("payload", payload)
        val cmd = JSONObject().put("id", "cmd").put("type", "request").put("uri", uri)
        if (body != null) cmd.put("payload", body)

        val ws = tls.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(reg.toString()) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = JSONObject(text)
                when {
                    j.optString("type") == "registered" -> {
                        j.optJSONObject("payload")?.optString("client-key")?.takeIf { it.isNotEmpty() }
                            ?.let { st.put("lgkey2:$ip", it) }
                        webSocket.send(cmd.toString())
                    }
                    j.optString("type") == "error" -> done.complete("A TV recusou: ${j.optString("error")}")
                    j.optString("id") == "cmd" -> done.complete("Comando enviado")
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
