package com.nizam.app

import android.app.Application
import com.nizam.app.feature.brain2.Calc
import com.nizam.app.feature.nx.AiSpec
import com.nizam.app.feature.nx.CalcSpec
import com.nizam.app.feature.nx.ChatSpec
import com.nizam.app.feature.nx.F
import com.nizam.app.feature.nx.K
import com.nizam.app.feature.nx.LogSpec
import com.nizam.app.feature.nx.Nx
import com.nizam.app.feature.nx.num
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Runs the logic of every tool with its default values: prompts, calculators, tracker lines and summaries. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SpecLogicAudit {
    private fun defaults(fields: List<F>): Map<String, String> = fields.associate { f ->
        f.key to when {
            f.default.isNotBlank() -> f.default
            f.kind == K.DATE -> java.time.LocalDate.now().toString()
            f.kind == K.NUMBER -> "10"
            f.kind == K.CHOICE -> f.options.firstOrNull().orEmpty()
            else -> "sample"
        }
    }

    @Test fun everyTool() {
        val problems = ArrayList<String>()
        val ids = HashMap<String, Int>()
        Nx.all.forEach { s ->
            ids.merge(s.id, 1, Int::plus)
            try {
                when (s) {
                    is AiSpec -> {
                        val p = s.prompt(defaults(s.fields))
                        if (p.isBlank()) problems += "${s.id}: the prompt is empty"
                        if (p.contains("null")) problems += "${s.id}: the prompt contains the word null (a missing value)"
                    }
                    is ChatSpec -> if (s.persona(defaults(s.setup)).isBlank()) problems += "${s.id}: the persona is empty"
                    is CalcSpec -> {
                        val v = defaults(s.inputs).mapKeys { it.key.lowercase() }.mapValues { (k, x) ->
                            val f = s.inputs.first { it.key.lowercase() == k }
                            if (f.kind == K.CHOICE) f.options.indexOf(x).coerceAtLeast(0).toDouble() else num(x)
                        }
                        s.outputs.forEach { (label, expr) ->
                            runCatching { Calc.eval(expr, v) }.onFailure { problems += "${s.id}: \"$label\" cannot be worked out (${it.message})" }
                        }
                    }
                    is LogSpec -> {
                        val d = defaults(s.fields)
                        val o = JSONObject(); d.entries.forEachIndexed { i, (k, x) -> o.put(k, x); if (i == 0) o.put("_0", x) }; o.put("t", System.currentTimeMillis()).put("id", "1")
                        val (a, _) = s.line(o)
                        if (a.isBlank()) problems += "${s.id}: the entry line has no title"
                        s.summary(listOf(o, o, o))
                    }
                    else -> {}
                }
            } catch (t: Throwable) { problems += "${s.id}: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}" }
        }
        ids.filter { it.value > 1 }.forEach { problems += "${it.key}: the id is used ${it.value} times" }
        val dir = File(System.getProperty("user.dir"), "build/audit").apply { mkdirs() }
        File(dir, "tools.txt").writeText("Checked ${Nx.all.size} tools, ${problems.size} problems.\n" + problems.joinToString("\n"))
    }
}
