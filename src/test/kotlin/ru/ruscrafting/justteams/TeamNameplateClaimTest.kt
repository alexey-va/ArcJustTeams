package ru.ruscrafting.justteams

import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcNameplateClaim
import ru.arc.paper.testing.MockBukkitTestRuntime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamNameplateClaimTest {
    @Test
    fun `event ownership suppresses ordinary plates including provider startup and shutdown gaps`() {
        MockBukkitTestRuntime.open().use { paper ->
            val ordinary = paper.createSimplePlugin("ArcJustTeams")
            val target = paper.addPlayer("Target")
            assertTrue(mayShowOrdinaryNameplate(ordinary, target))

            val events = paper.createSimplePlugin("ArcEvents")
            assertFalse(mayShowOrdinaryNameplate(ordinary, target), "Enabled event generation has no provider yet")
            var participates = false
            val claim = ArcNameplateClaim { participates && it == target.uniqueId }
            paper.server.servicesManager.register(ArcNameplateClaim::class.java, claim, events, ServicePriority.Normal)
            assertTrue(mayShowOrdinaryNameplate(ordinary, target))

            participates = true
            assertFalse(mayShowOrdinaryNameplate(ordinary, target))
            participates = false
            assertTrue(mayShowOrdinaryNameplate(ordinary, target))

            paper.server.servicesManager.unregister(ArcNameplateClaim::class.java, claim)
            assertFalse(mayShowOrdinaryNameplate(ordinary, target), "A retiring event generation still owns the gap")
            paper.server.pluginManager.disablePlugin(events)
            assertTrue(mayShowOrdinaryNameplate(ordinary, target))
        }
    }

    @Test
    fun `any other registered exclusive mode can hide a target`() {
        MockBukkitTestRuntime.open().use { paper ->
            val ordinary = paper.createSimplePlugin("ArcJustTeams")
            val mode = paper.createSimplePlugin("DuelTest")
            val claimed = paper.addPlayer("Claimed")
            val unrelated = paper.addPlayer("Unrelated")
            val claim = ArcNameplateClaim { it == claimed.uniqueId }
            paper.server.servicesManager.register(ArcNameplateClaim::class.java, claim, mode, ServicePriority.Normal)
            assertFalse(mayShowOrdinaryNameplate(ordinary, claimed))
            assertTrue(mayShowOrdinaryNameplate(ordinary, unrelated))
        }
    }
}
