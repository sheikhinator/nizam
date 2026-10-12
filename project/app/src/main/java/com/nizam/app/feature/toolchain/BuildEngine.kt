package com.nizam.app.feature.toolchain

import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import org.eclipse.jdt.internal.compiler.batch.Main as Ecj

/**
 * Compiles a single Java source file and turns it into a real Android .dex entirely with two
 * JVM libraries (ECJ, D8) running in-process — the same libraries Gradle itself uses for this.
 * Nothing here is a downloaded binary and nothing is exec'd, so Android's restriction on running
 * code that wasn't packaged into the APK at install time never applies to this path.
 */
object BuildEngine {

    data class Step(val name: String, val ok: Boolean, val detail: String)
    data class Result(val steps: List<Step>, val dex: ByteArray?)

    fun compile(workDir: File, className: String, source: String): Result = try {
        compileUnsafe(workDir, className, source)
    } catch (t: Throwable) {
        Result(listOf(Step("Compile (ECJ)", false, t.message ?: t.javaClass.simpleName)), null)
    }

    private fun compileUnsafe(workDir: File, className: String, source: String): Result {
        workDir.deleteRecursively()
        workDir.mkdirs()
        val srcFile = File(workDir, "$className.java").apply { writeText(source) }
        val outDir = File(workDir, "out").apply { mkdirs() }
        val dexDir = File(workDir, "dex").apply { mkdirs() }
        val steps = mutableListOf<Step>()

        val log = StringWriter()
        val compiled = Ecj(PrintWriter(log), PrintWriter(log), false, null, null).compile(
            arrayOf(srcFile.path, "-d", outDir.path, "-source", "17", "-target", "17", "-nowarn", "-proc:none")
        )
        steps += Step("Compile (ECJ)", compiled, log.toString().ifBlank { "No errors." })
        if (!compiled) return Result(steps, null)

        val classFile = File(outDir, "$className.class")
        if (!classFile.exists()) {
            steps += Step("Find class file", false, "Compiler reported success but produced no .class file")
            return Result(steps, null)
        }

        return try {
            D8.run(
                D8Command.builder()
                    .addProgramFiles(classFile.toPath())
                    .setOutput(dexDir.toPath(), OutputMode.DexIndexed)
                    .build()
            )
            val dexFile = File(dexDir, "classes.dex")
            if (dexFile.exists()) {
                steps += Step("Dex (D8)", true, "${dexFile.length()} bytes")
                Result(steps, dexFile.readBytes())
            } else {
                steps += Step("Dex (D8)", false, "No classes.dex produced")
                Result(steps, null)
            }
        } catch (t: Throwable) {
            steps += Step("Dex (D8)", false, t.message ?: t.javaClass.simpleName)
            Result(steps, null)
        }
    }

    /** Loads the dex in this process and runs its main(String[]), capturing stdout. Android-only API. */
    fun runDex(dex: ByteArray, className: String): String {
        val loader = dalvik.system.InMemoryDexClassLoader(java.nio.ByteBuffer.wrap(dex), BuildEngine::class.java.classLoader)
        val method = loader.loadClass(className).getMethod("main", Array<String>::class.java)
        val old = System.out
        val buf = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buf))
        return try {
            method.invoke(null, arrayOf<String>())
            buf.toString()
        } finally {
            System.out.flush()
            System.setOut(old)
        }
    }
}
