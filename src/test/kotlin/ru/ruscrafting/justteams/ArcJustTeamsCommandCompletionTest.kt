package ru.ruscrafting.justteams

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.arc.paper.testing.MockBukkitTestRuntime

class ArcJustTeamsCommandCompletionTest {
    @Test
    fun `clans has no parameter completions for any sender`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.server.pluginManager.loadPlugin(ArcJustTeamsPlugin::class.java) as ArcJustTeamsPlugin
            val command = checkNotNull(plugin.getCommand("clans"))
            val player = paper.server.addPlayer("Target")

            assertEquals(emptyList(), plugin.onTabComplete(player, command, "clans", arrayOf("")))
            assertEquals(emptyList(), plugin.onTabComplete(player, command, "clans", arrayOf("Tar")))
            assertEquals(emptyList(), plugin.onTabComplete(paper.server.consoleSender, command, "clans", arrayOf("")))
        }
    }
}
