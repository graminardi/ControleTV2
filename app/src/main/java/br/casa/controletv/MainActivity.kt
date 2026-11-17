package br.casa.controletv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

private val Bg = Color(0xFF0B0B0D)
private val KeyC = Color(0xFF2A2A2E)
private val Ink = Color(0xFFF2F2F4)
private val Muted = Color(0xFFB4B4BA)
private val Blue = Color(0xFF1A63D6)
private val Red = Color(0xFFD13438)
private val Line = Color(0xFF3A3A3E)

private fun vec(d: String) = ImageVector.Builder("i", 24.dp, 24.dp, 24f, 24f).addPath(
    PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.White),
    strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
).build()

private object Ic {
    val power = vec("M12 3v9M6.3 6.7a8 8 0 1 0 11.4 0")
    val refresh = vec("M21 12a9 9 0 1 1-3-6.7M21 4v5h-5")
    val pencil = vec("M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z")
    val up = vec("M7 14l5-5 5 5")
    val down = vec("M7 10l5 5 5-5")
    val left = vec("M14 7l-5 5 5 5")
    val right = vec("M10 7l5 5-5 5")
    val back = vec("M9 14 4 9l5-5M4 9h10a6 6 0 0 1 0 12h-3")
    val home = vec("M3 11 12 3l9 8M5 10v10h14V10")
    val play = vec("M6 5v14l9-7ZM19 5v14")
    val mute = vec("M11 5 6 9H2v6h4l5 4V5ZM23 9l-6 6M17 9l6 6")
    val check = vec("M5 12l5 5 9-10")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize(), color = Bg) { App(applicationContext) } } }
    }
}

/** Botão do controle: vibra ao tocar; com repeat=true repete enquanto segurado. */
@Composable
fun RKey(
    desc: String, modifier: Modifier = Modifier, bg: Color = KeyC, shape: Shape = CircleShape,
    repeat: Boolean = false, enabled: Boolean = true, onTap: () -> Unit, content: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val cur by rememberUpdatedState(onTap)
    fun tap() { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); cur() }
    Box(
        modifier.alpha(if (enabled) 1f else 0.4f).clip(shape).background(bg)
            .semantics { contentDescription = desc; role = Role.Button }
            .pointerInput(enabled, repeat) {
                if (!enabled) return@pointerInput
                if (repeat) detectTapGestures(onPress = {
                    tap()
                    coroutineScope {
                        val job = launch { delay(450); while (true) { tap(); delay(150) } }
                        tryAwaitRelease(); job.cancel()
                    }
                }) else detectTapGestures(onTap = { tap() })
            },
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun Glyph(icon: ImageVector, size: Int = 24, tint: Color = Ink) = Icon(icon, null, tint = tint, modifier = Modifier.size(size.dp))

@Composable
fun YtLogo() = Canvas(Modifier.size(36.dp, 26.dp)) {
    drawRoundRect(Color(0xFFFF0000), cornerRadius = CornerRadius(7.dp.toPx()))
    val p = Path().apply {
        moveTo(size.width * 0.39f, size.height * 0.29f); lineTo(size.width * 0.39f, size.height * 0.71f)
        lineTo(size.width * 0.66f, size.height * 0.5f); close()
    }
    drawPath(p, Color.White)
}

@Composable
fun Rocker(label: String, up: String, down: String, enabled: Boolean, send: (String) -> Unit) {
    Column(
        Modifier.width(92.dp).height(132.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(46.dp)).background(KeyC).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween
    ) {
        RKey("$label mais", Modifier.size(80.dp, 46.dp), Color.Transparent, repeat = true, enabled = enabled, onTap = { send(up) }) {
            Text("+", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(label, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        RKey("$label menos", Modifier.size(80.dp, 46.dp), Color.Transparent, repeat = true, enabled = enabled, onTap = { send(down) }) {
            Text("−", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun DPad(enabled: Boolean, key: (String) -> Unit) {
    Box(Modifier.size(176.dp).clip(CircleShape).background(KeyC)) {
        RKey("Cima", Modifier.align(Alignment.TopCenter).padding(top = 4.dp).size(52.dp), Color.Transparent, repeat = true, enabled = enabled, onTap = { key("UP") }) { Glyph(Ic.up, 26) }
        RKey("Baixo", Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(52.dp), Color.Transparent, repeat = true, enabled = enabled, onTap = { key("DOWN") }) { Glyph(Ic.down, 26) }
        RKey("Esquerda", Modifier.align(Alignment.CenterStart).padding(start = 4.dp).size(52.dp), Color.Transparent, repeat = true, enabled = enabled, onTap = { key("LEFT") }) { Glyph(Ic.left, 26) }
        RKey("Direita", Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).size(52.dp), Color.Transparent, repeat = true, enabled = enabled, onTap = { key("RIGHT") }) { Glyph(Ic.right, 26) }
        RKey("OK", Modifier.align(Alignment.Center).size(78.dp).border(1.dp, Line, CircleShape), Color(0xFF161618), enabled = enabled, onTap = { key("OK") }) {
            Text("OK", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun NumKey(label: String, modifier: Modifier, enabled: Boolean, bg: Color = KeyC, onTap: () -> Unit) =
    RKey(label, modifier.height(52.dp), bg, RoundedCornerShape(18.dp), enabled = enabled, onTap = onTap) {
        Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }

@Composable
fun App(ctx: Context) {
    val scope = rememberCoroutineScope()
    val st = remember { Store(ctx) }
    var tvs by remember { mutableStateOf(st.loadTvs()) }
    var selected by remember { mutableStateOf<String?>(null) }
    val status = remember { mutableStateMapOf<String, Boolean>() }
    var scanning by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf(false) }
    var numMode by remember { mutableStateOf(false) }
    var rev by remember { mutableIntStateOf(0) }

    val tv = tvs.firstOrNull { it.ip == selected } ?: tvs.firstOrNull()
    fun nameOf(t: Tv): String { val r = rev; return st.get("name:${t.ip}")?.takeIf { it.isNotBlank() } ?: t.name }

    fun scan() {
        if (scanning) return
        scope.launch {
            scanning = true; msg = "Procurando TVs…"
            val found = Discovery.scan(ctx, st)
            if (found.isNotEmpty()) {
                tvs = (tvs.filter { o -> found.none { it.ip == o.ip } } + found).sortedBy { it.ip }
                st.saveTvs(tvs)
            }
            scanning = false
            msg = if (tvs.isEmpty()) "Nenhuma TV encontrada. Confira o Wi-Fi e se as TVs estão ligadas." else ""
        }
    }
    fun key(k: String) { val t = tv ?: return; scope.launch { msg = Control.key(t, k, st) } }
    fun app(term: String) { val t = tv ?: return; msg = "Abrindo…"; scope.launch { msg = Control.app(t, term, st) } }
    fun power() {
        val t = tv ?: return
        val turnOn = status[t.ip] == false
        scope.launch {
            msg = "Enviando…"; msg = Control.power(t, turnOn, st)
            delay(3000); status[t.ip] = Control.isOn(t)
            delay(5000); status[t.ip] = Control.isOn(t)
        }
    }
    fun offAll() {
        scope.launch {
            msg = "Desligando todas…"
            coroutineScope { tvs.forEach { t -> launch { Control.power(t, false, st) } } }
            msg = "Sinais de desligar enviados"
        }
    }

    LaunchedEffect(Unit) { scan() }
    LaunchedEffect(tvs) {
        while (true) {
            coroutineScope { tvs.forEach { t -> launch { status[t.ip] = Control.isOn(t) } } }
            delay(6000)
        }
    }

    val on = tv?.let { status[it.ip] }
    val enabled = tv != null && on != false

    Column(
        Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B1B1F)).padding(4.dp)) {
                if (tvs.isEmpty()) Text("Nenhuma TV", color = Muted, modifier = Modifier.align(Alignment.CenterVertically).padding(start = 12.dp))
                tvs.forEach { t ->
                    val sel = t.ip == tv?.ip
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                            .background(if (sel) Blue else Color.Transparent).clickable { selected = t.ip },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(nameOf(t), color = if (sel) Color.White else Muted, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                    }
                }
            }
            RKey("Procurar TVs", Modifier.size(52.dp), shape = RoundedCornerShape(16.dp), onTap = { scan() }) { Glyph(Ic.refresh, 22) }
            Box {
                RKey("Mais opções", Modifier.size(52.dp), shape = RoundedCornerShape(16.dp), onTap = { menu = true }) {
                    Text("⋮", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Procurar TVs") }, onClick = { menu = false; scan() })
                    DropdownMenuItem(text = { Text("Desligar todas", color = Color(0xFFFF9A9A)) }, onClick = { menu = false; offAll() })
                    listOf(30 to "30 min", 60 to "1 h", 120 to "2 h").forEach { (m, l) ->
                        DropdownMenuItem(text = { Text("Desligar em $l") }, onClick = {
                            menu = false
                            tv?.let { OffTimer.schedule(ctx, it.ip, m); msg = "A TV desliga em $l" }
                        })
                    }
                }
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(40.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF242427), Color(0xFF161618))))
                .border(1.dp, Color(0xFF303034), RoundedCornerShape(40.dp))
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tv?.let { nameOf(it) } ?: "—", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        RKey("Configurar TV", Modifier.size(44.dp), Color.Transparent, onTap = { if (tv != null) config = true }) { Glyph(Ic.pencil, 18, Muted) }
                    }
                    val s = when (on) { true -> "Ligada"; false -> "Desligada"; null -> "Verificando…" }
                    Text("${tv?.brand?.label ?: ""} · $s", color = Muted, fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RKey("Fonte", Modifier.height(44.dp).widthIn(min = 72.dp), shape = RoundedCornerShape(22.dp), enabled = enabled, onTap = { key("SOURCE") }) {
                        Text("Fonte", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    RKey("Ligar ou desligar", Modifier.size(56.dp).border(1.dp, if (on == false) Red else Line, CircleShape),
                        if (on == false) Red else KeyC, enabled = tv != null, onTap = { power() }) { Glyph(Ic.power, 26, Color.White) }
                }
            }

            Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(20.dp)).background(Color(0xFF121214)).padding(3.dp)) {
                listOf("Navegação" to false, "Números" to true).forEach { (l, m) ->
                    Box(
                        Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(17.dp))
                            .background(if (numMode == m) Color(0xFF3A3A3F) else Color.Transparent).clickable { numMode = m },
                        contentAlignment = Alignment.Center
                    ) { Text(l, color = if (numMode == m) Color.White else Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }
            }

            if (!numMode) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DPad(enabled) { key(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        RKey("Voltar", Modifier.size(52.dp), enabled = enabled, onTap = { key("BACK") }) { Glyph(Ic.back) }
                        RKey("Início", Modifier.size(52.dp), enabled = enabled, onTap = { key("HOME") }) { Glyph(Ic.home) }
                        RKey("Play e pausa", Modifier.size(52.dp), enabled = enabled, onTap = { key("PLAYPAUSE") }) { Glyph(Ic.play, 22) }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { r.forEach { n -> NumKey(n, Modifier.weight(1f), enabled) { key(n) } } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.weight(1f))
                        NumKey("0", Modifier.weight(1f), enabled) { key("0") }
                        NumKey("OK", Modifier.weight(1f), enabled, Blue) { key("OK") }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Rocker("VOL", "VOLUP", "VOLDOWN", enabled) { key(it) }
                RKey("Mudo", Modifier.size(60.dp), enabled = enabled, onTap = { key("MUTE") }) { Glyph(Ic.mute, 26) }
                Rocker("CH", "CHUP", "CHDOWN", enabled) { key(it) }
            }

            RKey("YouTube", Modifier.fillMaxWidth().height(48.dp), Ink, RoundedCornerShape(24.dp), enabled = enabled, onTap = { app("youtube") }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    YtLogo(); Text("YouTube", color = Color(0xFF0F0F0F), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Triple("Netflix", "netflix", 0xFFE50914), Triple("Prime", "prime", 0xFF00A8E1),
                    Triple("Globo", "globo", 0xFFFF5A7A), Triple("Disney+", "disney", 0xFF4C7DFF)).forEach { (l, term, c) ->
                    RKey(l, Modifier.weight(1f).height(40.dp), shape = RoundedCornerShape(20.dp), enabled = enabled, onTap = { app(term) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(c)))
                            Spacer(Modifier.width(5.dp))
                            Text(l, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }

        val good = msg.startsWith("Comando") || msg.startsWith("Sinal") || msg.startsWith("A TV desliga")
        Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp))
                .background(if (msg.isEmpty()) Color.Transparent else if (good) Color(0xFF12261A) else Color(0xFF2A1A1A))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (msg.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (good) Glyph(Ic.check, 18, Color(0xFF7FE0A0))
                Text(msg, color = if (good) Color(0xFF7FE0A0) else Color(0xFFFF9A9A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            }
        }
    }

    if (config && tv != null) {
        var name by remember(tv.ip) { mutableStateOf(st.get("name:${tv.ip}") ?: tv.name) }
        var mac by remember(tv.ip) { mutableStateOf(st.get("mac:${tv.ip}") ?: "") }
        var psk by remember(tv.ip) { mutableStateOf(st.get("psk:${tv.ip}") ?: "0000") }
        AlertDialog(
            onDismissRequest = { config = false },
            title = { Text("Configurar TV") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                    if (tv.brand == Brand.SAMSUNG || tv.brand == Brand.LG)
                        OutlinedTextField(mac, { mac = it }, label = { Text("MAC da TV (para ligar)") }, singleLine = true)
                    if (tv.brand == Brand.SONY)
                        OutlinedTextField(psk, { psk = it }, label = { Text("PSK (Rede > Controle IP)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton({
                    st.put("name:${tv.ip}", name.trim())
                    if (mac.isNotBlank()) st.put("mac:${tv.ip}", mac.trim())
                    st.put("psk:${tv.ip}", psk.trim())
                    rev++; config = false
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton({ config = false }) { Text("Cancelar") } }
        )
    }
}
