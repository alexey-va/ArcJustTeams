package ru.ruscrafting.justteams

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class NativeFlowArchitectureTest {
    @Test
    fun `team dialog flow has no command or chest gui handoff`() {
        val sourceRoot = Path.of("src/main/kotlin/ru/ruscrafting/justteams")
        val source = Files.walk(sourceRoot).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }.map(Files::readString).toList().joinToString("\n")
        }

        assertFalse("performCommand(" in source)
        assertFalse("openNative(" in source)
        assertFalse("org.bukkit.inventory" in source)
        assertFalse(Regex("Class\\.forName\\(\"ru\\.arc").containsMatchIn(source))
    }
}
