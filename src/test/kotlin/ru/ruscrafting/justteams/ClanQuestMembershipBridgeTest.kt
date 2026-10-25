package ru.ruscrafting.justteams

import org.bukkit.plugin.ServicePriority
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.api.ClanQuestMenuApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClanQuestMembershipBridgeTest {
    @Test fun `clan menu delegates to the typed provider and reports its real availability`() {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("ClanQuestReader")
            assertFalse(ClanQuestMembershipBridge.openQuests(player))
            val ranks = paper.createSimplePlugin("ArcRanks")
            assertFalse(ClanQuestMembershipBridge.openQuests(player))
            var opened = false
            paper.server.servicesManager.register(ClanQuestMenuApi::class.java, ClanQuestMenuApi { id ->
                assertEquals(player.uniqueId, id)
                opened = true
                true
            }, ranks, ServicePriority.Normal)
            assertTrue(ClanQuestMembershipBridge.openQuests(player))
            assertTrue(opened)
            paper.server.servicesManager.unregisterAll(ranks)
            assertFalse(ClanQuestMembershipBridge.openQuests(player))
        }
    }
}
