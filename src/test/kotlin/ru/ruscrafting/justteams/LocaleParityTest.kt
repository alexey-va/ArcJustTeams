package ru.ruscrafting.justteams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Path

class LocaleParityTest {
    @Test
    fun `russian and english locale keys stay in parity`() {
        val resources = Path.of("src/main/resources/lang")
        val russian = keys(resources.resolve("ru.yml"))
        val english = keys(resources.resolve("en.yml"))
        assertEquals(russian.toSet(), english.toSet())
        assertTrue(russian.size == russian.toSet().size, "ru.yml contains duplicate keys")
        assertTrue(english.size == english.toSet().size, "en.yml contains duplicate keys")
    }

    private fun keys(path: Path): List<String> {
        val parents = mutableListOf<String>()
        return path.toFile().readLines().mapNotNull { line ->
            if (line.isBlank() || line.trimStart().startsWith('#')) return@mapNotNull null
            val indent = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0) / 2
            val key = line.trimStart().substringBefore(':')
            while (parents.size > indent) parents.removeLast()
            val full = (parents + key).joinToString(".")
            if (line.substringAfter(':').isBlank()) {
                parents += key
                null
            } else full
        }
    }
}
