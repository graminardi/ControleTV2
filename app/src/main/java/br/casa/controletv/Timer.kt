package br.casa.controletv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object OffTimer {
    fun schedule(ctx: Context, ip: String, minutes: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val i = Intent(ctx, OffReceiver::class.java).putExtra("ip", ip)
        val pi = PendingIntent.getBroadcast(
            ctx, ip.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + minutes * 60_000L, pi)
    }
}

class OffReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val ip = i.getStringExtra("ip") ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val st = Store(c.applicationContext)
                st.loadTvs().firstOrNull { it.ip == ip }?.let { Control.power(it, false, st) }
            } finally { pending.finish() }
        }
    }
}
