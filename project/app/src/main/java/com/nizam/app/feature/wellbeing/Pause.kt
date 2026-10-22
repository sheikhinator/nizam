package com.nizam.app.feature.wellbeing

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.ui.theme.NizamTheme
import java.time.LocalDate

/**
 * "Pause before scrolling" — one slow breath before a distracting app opens, then a real choice.
 * Modelled on one sec, whose peer-reviewed study measured a 57% drop in social media use.
 */
object Pause {
    private fun p(c: Context) = c.getSharedPreferences("pause", 0)
    fun apps(c: Context): Set<String> = p(c).getStringSet("apps", emptySet()) ?: emptySet()
    fun setApps(c: Context, s: Set<String>) = p(c).edit().putStringSet("apps", s).apply()
    fun allowedUntil(c: Context, pkg: String) = p(c).getLong("ok_$pkg", 0)
    fun allow(c: Context, pkg: String) = p(c).edit().putLong("ok_$pkg", System.currentTimeMillis() + 15 * 60_000L).apply()
    private fun bump(c: Context, key: String) {
        val k = "${LocalDate.now()}_$key"; p(c).edit().putInt(k, p(c).getInt(k, 0) + 1).apply()
    }
    fun opened(c: Context) = bump(c, "opened")
    fun closed(c: Context) = bump(c, "closed")
    fun today(c: Context) = p(c).getInt("${LocalDate.now()}_opened", 0) to p(c).getInt("${LocalDate.now()}_closed", 0)
}

class PauseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { Pause.closed(this@PauseActivity); goHome() }
        })
        val pkg = intent.getStringExtra("pkg").orEmpty()
        val label = intent.getStringExtra("label").orEmpty().ifBlank { "this app" }
        Pause.opened(this)
        setContent {
            NizamTheme(darkTheme = true) {
                var ready by remember { mutableStateOf(false) }
                val scale = remember { Animatable(0.35f) }
                LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
                    scale.animateTo(1f, tween(4000, easing = LinearEasing))       // breathe in
                    scale.animateTo(0.35f, tween(4000, easing = LinearEasing))    // and out
                    ready = true
                } }
                Column(
                    Modifier.fillMaxSize().background(Color(0xFF0B0F1A)).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Text(if (ready) "Still want to open $label?" else if (scale.value < 0.99f && !ready) "Breathe in…" else "…and out",
                        color = Color(0xFFEDEFF5), fontSize = 24.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(40.dp))
                    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawCircle(Color(0xFF4FC3A1).copy(alpha = 0.18f), radius = size.minDimension / 2 * scale.value)
                            drawCircle(Color(0xFF4FC3A1), radius = size.minDimension / 2 * scale.value * 0.6f)
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                    if (ready) {
                        val (opened, closed) = Pause.today(this@PauseActivity)
                        Button(onClick = { Pause.closed(this@PauseActivity); goHome() }, modifier = Modifier.fillMaxWidth()) {
                            Text("No — take me back")
                        }
                        Spacer(Modifier.height(10.dp))
                        // Earn-your-scroll: when on, the 15 minutes are paid for with time earned today
                        val earnMode = com.nizam.app.feature.jarvis.Earn.on(this@PauseActivity)
                        var balance by remember { mutableStateOf(-1) }
                        LaunchedEffect(earnMode) { com.nizam.app.ui.design.guard {
                            if (earnMode) balance = com.nizam.app.feature.jarvis.Earn.balance(com.nizam.app.feature.jarvis.Background.container(this@PauseActivity))
                        } }
                        val affordable = !earnMode || balance >= 15
                        OutlinedButton(enabled = affordable, onClick = {
                            if (earnMode) com.nizam.app.feature.jarvis.Earn.spend(this@PauseActivity, 15)
                            Pause.allow(this@PauseActivity, pkg)
                            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            finish()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (!earnMode) "Yes, open $label for 15 minutes"
                                else if (affordable) "Spend 15 of your $balance earned minutes"
                                else "Earn ${15 - balance.coerceAtLeast(0)} more minutes first")
                        }
                        if (earnMode && !affordable) Text("Finish a task (+5), pray on time (+5) or complete your adhkar (+10).",
                            color = Color(0xFF8C96AC), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp))
                        Spacer(Modifier.height(18.dp))
                        Text("Today: paused $opened times · chose not to $closed times", color = Color(0xFF8C96AC),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish()
    }
    // back means no: it leaves to the home screen (works with predictive back too)
}
