package ru.ruscrafting.justteams

import kotlin.test.Test
import kotlin.test.assertEquals
import java.nio.file.Path

class LocaleParityTest {
    @Test
    fun `russian and english locale keys stay in parity`() {
        val resources = Path.of("src/main/resources/lang")
        assertEquals(keys(resources.resolve("ru.yml")), keys(resources.resolve("en.yml")))
    }

    private fun keys(path: Path): Set<String> {
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
        }.toSet()
    }
}
