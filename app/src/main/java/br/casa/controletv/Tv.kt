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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val probe = OkHttpClient.Builder().callTimeout(1500, TimeUnit.MILLISECONDS).build()
    private fun peek(url: String): String? = try {
        probe.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() }
    } catch (e: Exception) { null }
    private fun tcp(ip: String, port: Int) = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 700); true }
    } catch (e: Exception) { false }

    suspend fun isOn(tv: Tv): Boolean = withContext(Dispatchers.IO) {
        when (tv.brand) {
            Brand.SAMSUNG -> peek("http://${tv.ip}:8001/api/v2/")
                ?.let { !Regex("\"PowerState\"\\s*:\\s*\"standby\"").containsMatchIn(it) } ?: false
            Brand.ROKU -> peek("http://${tv.ip}:8060/query/device-info")
                ?.let { !Regex("<power-mode>(PowerOff|Ready|DisplayOff)</power-mode>").containsMatchIn(it) } ?: false
            Brand.LG -> tcp(tv.ip, 3001) || tcp(tv.ip, 3000)
            else -> tcp(tv.ip, 80)
        }
    }

    private fun samsungName(k: String) = when (k) {
        "OK" -> "KEY_ENTER"; "BACK" -> "KEY_RETURN"; "PLAYPAUSE" -> "KEY_PLAY"
        "UP" -> "KEY_UP"; "DOWN" -> "KEY_DOWN"; "LEFT" -> "KEY_LEFT"; "RIGHT" -> "KEY_RIGHT"
        "VOLUP" -> "KEY_VOLUP"; "VOLDOWN" -> "KEY_VOLDOWN"; "MUTE" -> "KEY_MUTE"
        "CHUP" -> "KEY_CHUP"; "CHDOWN" -> "KEY_CHDOWN"; "SOURCE" -> "KEY_SOURCE"; "HOME" -> "KEY_HOME"
        else -> "KEY_$k"
    }

    private fun rokuName(k: String) = when (k) {
        "OK" -> "Select"; "BACK" -> "Back"; "HOME" -> "Home"; "PLAYPAUSE" -> "Play"
        "UP" -> "Up"; "DOWN" -> "Down"; "LEFT" -> "Left"; "RIGHT" -> "Right"
        "VOLUP" -> "VolumeUp"; "VOLDOWN" -> "VolumeDown"; "MUTE" -> "VolumeMute"
        "CHUP" -> "ChannelUp"; "CHDOWN" -> "ChannelDown"; "SOURCE" -> "InputHDMI1"
        else -> "Lit_$k"
    }

    /** Tecla lógica (UP, OK, VOLUP, 5...) traduzida para cada marca. */
    suspend fun key(tv: Tv, k: String, st: Store): String = withContext(Dispatchers.IO) {
        try {
            when (tv.brand) {
                Brand.SAMSUNG -> samsungKey(tv.ip, st, samsungName(k))
                Brand.ROKU -> post("http://${tv.ip}:8060/keypress/${rokuName(k)}", "", null)
                Brand.LG -> when (k) {
                    "VOLUP" -> lgCmd(tv.ip, st, "ssap://audio/volumeUp")
                    "VOLDOWN" -> lgCmd(tv.ip, st, "ssap://audio/volumeDown")
                    "CHUP" -> lgCmd(tv.ip, st, "ssap://tv/channelUp")
                    "CHDOWN" -> lgCmd(tv.ip, st, "ssap://tv/channelDown")
                    else -> "Esta tecla ainda não funciona em TV LG"
                }
                else -> "Marca ainda não suportada"
            }
        } catch (e: Exception) { "Erro: ${e.message}" }
    }

    private val samsungFallback = mapOf("netflix" to "11101200001", "prime" to "3201910019365", "disney" to "3201901017640")
    private val rokuApps = mapOf("youtube" to "837", "netflix" to "12", "prime" to "13", "disney" to "291097")
    private val lgApps = mapOf("youtube" to "youtube.leanback.v4", "netflix" to "netflix", "prime" to "amazon", "disney" to "com.disney.disneyplus-prod")

    /** term: youtube, netflix, prime, globo, disney */
    suspend fun app(tv: Tv, term: String, st: Store): String = withContext(Dispatchers.IO) {
        try {
            when (tv.brand) {
                Brand.SAMSUNG -> {
                    val id = if (term == "youtube") "111299001912" else samsungAppId(tv.ip, st, term)
                        ?: return@withContext "App não encontrado nesta TV"
                    post("http://${tv.ip}:8001/api/v2/applications/$id", "", null)
                }
                Brand.ROKU -> rokuApps[term]?.let { post("http://${tv.ip}:8060/launch/$it", "", null) }
                    ?: "App ainda não disponível em Roku"
                Brand.LG -> lgApps[term]?.let { lgCmd(tv.ip, st, "ssap://system.launcher/launch", JSONObject().put("id", it)) }
                    ?: "App ainda não disponível em LG"
                else -> "Marca ainda não suportada"
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

    // Conexão Samsung mantida aberta: cada tecla sai na hora, sem refazer o handshake.
    private val links = ConcurrentHashMap<String, WebSocket>()
    private val connectLock = Mutex()
    @Volatile private var appsWaiter: CompletableDeferred<String>? = null
    private class Conn(val ws: WebSocket?, val msg: String)

    private suspend fun samsungOff(ip: String, st: Store) = samsungKey(ip, st, "KEY_POWER")

    private suspend fun samsungKey(ip: String, st: Store, key: String): String {
        val json = """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$key","Option":"false","TypeOfRemote":"SendRemoteKey"}}"""
        links[ip]?.let { if (it.send(json)) return "Comando enviado" else links.remove(ip) }
        val c = samsungSocket(ip, st)
        val ws = c.ws ?: return c.msg
        return if (ws.send(json)) "Comando enviado" else { links.remove(ip); "Conexão perdida. Tente de novo." }
    }

    private suspend fun samsungSocket(ip: String, st: Store): Conn = connectLock.withLock {
        links[ip]?.let { return@withLock Conn(it, "ok") }
        samsungOpen(ip, st)
    }

    private suspend fun samsungOpen(ip: String, st: Store): Conn {
        val name = Base64.encodeToString("ControleTV".toByteArray(), Base64.NO_WRAP)
        val token = st.get("token:$ip")?.let { "&token=$it" } ?: ""
        val path = "/api/v2/channels/samsung.remote.control?name=$name$token"
        // 2018+ usa wss:8002; alguns 2016-2017 só respondem em ws:8001
        val all = listOf("wss://$ip:8002", "ws://$ip:8001")
        val saved = st.get("sbase:$ip")
        val bases = if (saved != null && saved in all) listOf(saved) + (all - saved) else all
        for (base in bases) {
            val ready = CompletableDeferred<String>()
            val ws = tls.newWebSocket(Request.Builder().url(base + path).build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when {
                        text.contains("ms.channel.connect") -> {
                            Regex("\"token\"\\s*:\\s*\"(\\d+)\"").find(text)?.let { st.put("token:$ip", it.groupValues[1]) }
                            ready.complete("ok")
                        }
                        text.contains("ed.installedApp.get") -> appsWaiter?.complete(text)
                        text.contains("unauthorized") || text.contains("timeOut") ->
                            ready.complete("A TV não autorizou. Aceite o pedido na tela da TV e tente de novo.")
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    links.remove(ip, webSocket); ready.complete("__fail")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { links.remove(ip, webSocket) }
            })
            when (val r = withTimeoutOrNull(20000) { ready.await() }) {
                "ok" -> { st.put("sbase:$ip", base); links[ip] = ws; return Conn(ws, "ok") }
                "__fail" -> {}
                null -> { ws.close(1000, null); return Conn(null, "Sem resposta. Aceite o pedido na TV e tente de novo.") }
                else -> { ws.close(1000, null); return Conn(null, r) }
            }
        }
        return Conn(null, "Não consegui conectar na TV (ligada e no mesmo Wi-Fi?)")
    }

    private suspend fun samsungAppId(ip: String, st: Store, term: String): String? {
        st.get("app:$ip:$term")?.let { return it }
        val ws = samsungSocket(ip, st).ws
        if (ws != null) {
            val w = CompletableDeferred<String>()
            appsWaiter = w
            ws.send("""{"method":"ms.channel.emit","params":{"event":"ed.installedApp.get","to":"host"}}""")
            val txt = withTimeoutOrNull(3000) { w.await() }
            appsWaiter = null
            try {
                val arr = JSONObject(txt ?: "").optJSONObject("data")?.optJSONArray("data")
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("name").contains(term, true)) {
                        val id = o.optString("appId")
                        if (id.isNotEmpty()) { st.put("app:$ip:$term", id); return id }
                    }
                }
            } catch (e: Exception) { }
        }
        return samsungFallback[term]
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
