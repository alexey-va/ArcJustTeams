package ru.ruscrafting.justteams

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.bukkit.Bukkit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin

class ArcJustTeamsLifecycleTest {
    @Test
    fun `maintenance configuration is sequential and failure disables before enable returns`() {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("justTeams")
            var preparations = 0
            mockkStatic(ConfigManager::class)
            every { ConfigManager.of(any(), any()) } answers {
                assertTrue(Bukkit.isPrimaryThread())
                preparations++
                throw IllegalStateException("isolated team locale preparation failure")
            }
            try {
                val plugin = paper.loadPlugin<ArcJustTeamsPlugin>()
                assertFalse(plugin.isEnabled)
                assertEquals(1, preparations)
                plugin.onDisable()
                assertEquals(1, preparations)
            } finally {
                unmockkStatic(ConfigManager::class)
                ConfigManager.clear()
            }
        }
    }
}
