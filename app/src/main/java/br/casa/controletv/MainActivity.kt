package br.casa.controletv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App(applicationContext) } } }
    }
}

@Composable
fun App(ctx: Context) {
    val scope = rememberCoroutineScope()
    val st = remember { Store(ctx) }
    var tvs by remember { mutableStateOf(st.loadTvs()) }
    var scanning by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    fun scan() {
        if (scanning) return
        scope.launch {
            scanning = true; msg = ""
            val found = Discovery.scan(ctx, st)
            if (found.isNotEmpty()) {
                tvs = (tvs.filter { o -> found.none { it.ip == o.ip } } + found).sortedBy { it.ip }
                st.saveTvs(tvs)
            }
            scanning = false
            if (tvs.isEmpty()) msg = "Nenhuma TV encontrada. Confira se o celular e as TVs estão no mesmo Wi-Fi e se as TVs estão ligadas."
        }
    }
    LaunchedEffect(Unit) { scan() }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        Text("Controle TV", style = MaterialTheme.typography.headlineMedium)
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { scan() }, enabled = !scanning) { Text(if (scanning) "Procurando…" else "Procurar TVs") }
            if (scanning) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(24.dp))
        }
        if (msg.isNotEmpty()) Text(msg)
        LazyColumn { items(tvs, key = { it.ip }) { TvCard(it, st) } }
    }
}

@Composable
fun TvCard(tv: Tv, st: Store) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf(st.get("mac:${tv.ip}") ?: "") }
    var psk by remember { mutableStateOf(st.get("psk:${tv.ip}") ?: "0000") }
    var customName by remember { mutableStateOf(st.get("name:${tv.ip}")?.takeIf { it.isNotBlank() }) }
    var renaming by remember { mutableStateOf(false) }
    var chNum by remember { mutableStateOf("") }
    val canRemote = tv.brand == Brand.SAMSUNG || tv.brand == Brand.LG || tv.brand == Brand.ROKU

    fun act(a: String, arg: String = "") {
        scope.launch { busy = true; status = "Enviando…"; status = Control.action(tv, a, st, arg); busy = false }
    }
    val ok = tv.brand != Brand.OUTRA

    fun run(on: Boolean) {
        scope.launch { busy = true; status = "Enviando…"; status = Control.power(tv, on, st); busy = false }
    }

    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(customName ?: tv.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { renaming = true }) { Text("Renomear") }
            }
            Text("${tv.brand.label} · ${tv.ip}")
            if (tv.brand == Brand.SONY) OutlinedTextField(
                value = psk, onValueChange = { psk = it; st.put("psk:${tv.ip}", it) },
                label = { Text("PSK (Rede > Controle IP)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            if (tv.brand == Brand.SAMSUNG || tv.brand == Brand.LG) OutlinedTextField(
                value = mac, onValueChange = { mac = it; st.put("mac:${tv.ip}", it) },
                label = { Text("MAC da TV (para ligar)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.padding(top = 8.dp)) {
                Button(onClick = { run(true) }, enabled = ok && !busy) { Text("Ligar") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { run(false) }, enabled = ok && !busy) { Text("Desligar") }
            }
            if (canRemote) {
                Row(Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { act("chdown") }, enabled = !busy, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)) { Text("CH −", maxLines = 1) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { act("chup") }, enabled = !busy, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)) { Text("CH +", maxLines = 1) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { act("yt") }, enabled = !busy, modifier = Modifier.weight(1.4f),
                        contentPadding = PaddingValues(horizontal = 4.dp)) { Text("YouTube", maxLines = 1) }
                }
            }
            if (tv.brand == Brand.SAMSUNG) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = chNum, onValueChange = { chNum = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("Ir para o canal") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { act("chnum", chNum) }, enabled = !busy && chNum.isNotEmpty()) { Text("Ir") }
                }
            }
            if (status.isNotEmpty()) Text(status, Modifier.padding(top = 8.dp))
            if (renaming) {
                var draft by remember { mutableStateOf(customName ?: tv.name) }
                AlertDialog(
                    onDismissRequest = { renaming = false },
                    title = { Text("Nome da TV") },
                    text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true) },
                    confirmButton = {
                        TextButton(onClick = {
                            val n = draft.trim()
                            st.put("name:${tv.ip}", n)
                            customName = n.ifEmpty { null }
                            renaming = false
                        }) { Text("Salvar") }
                    },
                    dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancelar") } }
                )
            }
        }
    }
}
