package ru.ruscrafting.justteams

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.nameplate.PlayerNameplateRegistry
import ru.arc.paper.testing.MockBukkitTestRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamNameplateRowsTest {
    @Test
    fun `clan and health stay on separate rows and PvP is composed for each viewer`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcJustTeamsRows")
            plugin.saveDefaultConfig()
            val target = paper.addPlayer("Target")
            val viewer = paper.addPlayer("Viewer")
            target.health = 18.25
            val rows = TeamNameplateRows(Texts(plugin))
            val team = TeamNameplateTeam(1, "<red>TAG", false, emptySet())
            val registry = PlayerNameplateRegistry()
            rows.common(target, team).forEach { registry.upsert(target.uniqueId, it) }
            val common = requireNotNull(registry.snapshot(target.uniqueId)).content

            assertEquals("[<red>TAG]\n19/20 ❤", plain(common), "A team tag must remain literal text")
            val friend = plain(rows.forViewer(viewer, common, TeamPvpState.CLAN))
            val stranger = plain(rows.forViewer(viewer, common, TeamPvpState.UNKNOWN))
            assertTrue(friend.startsWith("[<red>TAG]\n19/20 ❤ · "))
            assertFalse(friend == stranger)
            assertEquals(1, friend.count { it == '\n' }, "No duplicate native nickname or accidental third extra line")
            assertEquals(listOf(TeamNameplateRows.HEALTH), rows.common(target, null).map { it.key })
        }
    }

    @Test
    fun `absorption is distinguished from ordinary health and control characters cannot add rows`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcJustTeamsAbsorption")
            plugin.saveDefaultConfig()
            val target = paper.addPlayer("Target")
            target.absorptionAmount = 3.5
            val rows = TeamNameplateRows(Texts(plugin)).common(target, TeamNameplateTeam(1, "A\nB", false, emptySet()))
            assertEquals("[AB]", plain(rows.first().content))
            assertEquals("20/20 ❤ +4", plain(rows.last().content))
        }
    }

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
}
