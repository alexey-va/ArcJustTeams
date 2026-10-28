package ru.ruscrafting.justteams

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.paper.testing.MockBukkitTestRuntime
import kotlin.test.Test
import kotlin.test.assertEquals

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
            val commonRows = rows.common(target, team)
            val common = commonRows.single { it.key == TeamNameplateRows.HEALTH }.content

            assertEquals("[<red>TAG]", plain(commonRows.single { it.key == TeamNameplateRows.CLAN }.content),
                "A team tag must remain literal text in its own upper row")
            assertEquals("19/20 ❤", plain(common))
            val pvpIcons = mapOf(
                TeamPvpState.ALLOWED to "⚔",
                TeamPvpState.WORLD to "⛨",
                TeamPvpState.CLAN to "⛨",
                TeamPvpState.ALLY to "⛨",
                TeamPvpState.PROTECTED to "⛨",
                TeamPvpState.LANDS to "⛨",
                TeamPvpState.UNKNOWN to "?",
            )
            for (locale in listOf("ru", "en")) {
                plugin.config.set("locale.default", locale)
                for ((state, icon) in pvpIcons) {
                    val content = rows.forViewer(viewer, common, state)
                    assertEquals("19/20 ❤ · $icon", plain(content),
                        "$locale/$state must append one icon without PvP prose or an extra row")
                    val color = when (state) {
                        TeamPvpState.ALLOWED -> 0xff6b61
                        TeamPvpState.UNKNOWN -> 0xe8dfd2
                        else -> 0x9bd48d
                    }
                    assertEquals(TextColor.color(color), content.children().last().color(), "$locale/$state icon color")
                }
            }
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
