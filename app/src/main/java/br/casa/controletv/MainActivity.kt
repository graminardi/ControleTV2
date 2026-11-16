package br.casa.controletv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var tvs by remember { mutableStateOf(listOf<Tv>()) }
    var scanning by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    fun scan() {
        if (scanning) return
        scope.launch {
            scanning = true; msg = ""
            tvs = Discovery.scan(ctx, st)
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
    val ok = tv.brand != Brand.OUTRA

    fun run(on: Boolean) {
        scope.launch { busy = true; status = "Enviando…"; status = Control.power(tv, on, st); busy = false }
    }

    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(tv.name, style = MaterialTheme.typography.titleMedium)
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
            if (status.isNotEmpty()) Text(status, Modifier.padding(top = 8.dp))
        }
    }
}
