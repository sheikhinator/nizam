package com.nizam.app.toolchainpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Proves the real compile-to-dex pipeline works, on the build server, before it ever touches a
 * phone — not a mock. Only the final dex-loading step (dalvik.system.InMemoryDexClassLoader) is
 * Android-only and skipped here; everything up to and including a valid classes.dex is exercised.
 */
class BuildEngineTest {

    private val source = """
        public class AtlasDemo {
            public static void main(String[] args) {
                System.out.println("ok");
            }
        }
    """.trimIndent()

    @Test
    fun `compiles real Java source and produces a valid dex`() {
        val result = BuildEngine.compile(File(System.getProperty("java.io.tmpdir"), "atlas-build-test"), "AtlasDemo", source)

        assertTrue("ECJ should report success: ${result.steps}", result.steps.first { it.name.startsWith("Compile") }.ok)
        assertNotNull("D8 should produce dex bytes", result.dex)

        val dex = result.dex!!
        // "dex\n" magic header every real .dex file starts with
        assertEquals("dex\n".toByteArray().toList(), dex.take(4))
        assertTrue("dex file should have real content", dex.size > 100)
    }

    @Test
    fun `reports a compile error instead of crashing on bad source`() {
        val result = BuildEngine.compile(File(System.getProperty("java.io.tmpdir"), "atlas-build-test-bad"), "Broken", "this is not java")

        assertTrue("should fail to compile", result.steps.first { it.name.startsWith("Compile") }.ok.not())
        assertNotNull(result.steps.first().detail)
    }
}
