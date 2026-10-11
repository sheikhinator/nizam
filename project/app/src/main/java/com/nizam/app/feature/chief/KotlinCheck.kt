package com.nizam.app.feature.chief

import java.io.File

/**
 * A free pre-check that catches the commonest mistakes of weaker AI models before a ten-minute build is wasted:
 * mismatched brackets and imports of things that do not exist in the app. It never blocks a change, it only tells the AI what to fix.
 */
object KotlinCheck {
    private val DECL = Regex("""^\s*(?:(?:public|internal|private|data|sealed|enum|abstract|open|inline|value|annotation|const|suspend|operator|infix|tailrec|external|expect|actual|lateinit)\s+)*(?:class|object|interface|typealias|fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w<>?,. ]+\.)?(\w+)""", RegexOption.MULTILINE)
    private val PKG = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
    private val IMPORT = Regex("""^import\s+(com\.nizam\.app\.[\w.]+?)(?:\s+as\s+\w+)?\s*$""", RegexOption.MULTILINE)

    private var indexRoot: String? = null
    private var index: Map<String, Set<String>> = emptyMap()

    private fun index(root: File): Map<String, Set<String>> {
        if (indexRoot == root.path && index.isNotEmpty()) return index
        val m = HashMap<String, MutableSet<String>>()
        File(root, "app/src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
            val pkg = PKG.find(text)?.groupValues?.get(1) ?: return@forEach
            val set = m.getOrPut(pkg) { HashSet() }
            DECL.findAll(text).forEach { set += it.groupValues[1] }
        }
        index = m; indexRoot = root.path
        return m
    }

    private fun balance(s: String): IntArray {
        var b = 0; var p = 0; var k = 0
        for (ch in s) when (ch) { '{' -> b++; '}' -> b--; '(' -> p++; ')' -> p--; '[' -> k++; ']' -> k-- }
        return intArrayOf(b, p, k)
    }

    /** Problems introduced by turning [before] into [after] in a Kotlin file. */
    fun problems(root: File, path: String, before: String?, after: String): List<String> {
        if (!path.endsWith(".kt")) return emptyList()
        val out = ArrayList<String>()
        val a = balance(after); val b = if (before == null) intArrayOf(0, 0, 0) else balance(before)
        if (a[0] != b[0]) out += "$path: curly brackets are unbalanced (${if (a[0] > b[0]) "a { is missing its }" else "an extra }"}). Check the lines you changed."
        if (a[1] != b[1]) out += "$path: round brackets are unbalanced. Check the lines you changed."
        if (a[2] != b[2]) out += "$path: square brackets are unbalanced. Check the lines you changed."
        val idx = index(root)
        val oldImports = if (before == null) emptySet() else IMPORT.findAll(before).map { it.groupValues[1] }.toSet()
        IMPORT.findAll(after).map { it.groupValues[1] }.filter { it !in oldImports }.forEach { imp ->
            val segs = imp.split('.')
            val firstCap = segs.indexOfFirst { it.firstOrNull()?.isUpperCase() == true }
            val (pkg, name) = if (firstCap > 0) segs.take(firstCap).joinToString(".") to segs[firstCap] else segs.dropLast(1).joinToString(".") to segs.last()
            if (name == "*") return@forEach
            if (idx[pkg]?.contains(name) != true) out += "$path: the import $imp does not exist in this app. Search for the real name and package."
        }
        return out
    }

    /** Clear the cached index after the code is re-downloaded. */
    fun reset() { index = emptyMap(); indexRoot = null }
}
