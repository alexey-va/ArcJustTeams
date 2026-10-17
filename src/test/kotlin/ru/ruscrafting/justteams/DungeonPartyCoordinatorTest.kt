package ru.ruscrafting.justteams

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.dungeonparty.DungeonPartyGatherRequest
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.bukkit.entity.Player

class DungeonPartyCoordinatorTest {
    @Test
    fun `delayed member preparation coalesces gathers and rechecks membership before sending`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("PartyPreparationTest")
            val leaderId = paper.addPlayer("Leader").uniqueId
            val leader = mockk<Player> {
                every { uniqueId } returns leaderId
                every { isOnline } returns true
            }
            val departed = UUID.randomUUID()
            val retained = UUID.randomUUID()
            val raw = Any()
            val initial = partyTeam(raw, leaderId, setOf(leaderId, departed, retained))
            val current = initial.copy(memberIds = setOf(leaderId, retained))
            val members = CompletableFuture<List<TeamMemberSnapshot>>()
            val gateway = mockk<JustTeamsGateway> {
                every { membersAsync(raw) } returns members
                every { team(leaderId) } returns current
            }
            val sent = mutableListOf<ByteArray>()
            every { leader.sendPluginMessage(plugin, DungeonPartyGatherRequest.CHANNEL, any()) } answers {
                sent += thirdArg<ByteArray>()
            }
            val scheduler = TestTaskScheduler()
            val tasks = LifecycleTaskScope(scheduler)
            DungeonPartyCoordinator(plugin, gateway, tasks, null, plugin.logger, { _, _ -> }).use { coordinator ->
                try {
                    assertTrue(coordinator.gather(leader, initial))
                    assertTrue(coordinator.gather(leader, initial))
                    assertEquals(emptyList<ByteArray>(), sent)
                    verify(exactly = 1) { gateway.membersAsync(raw) }
                    members.complete(listOf(
                        TeamMemberSnapshot(departed, "Departed", "member", true),
                        TeamMemberSnapshot(retained, "Retained", "member", true),
                    ))
                    scheduler.tick()
                    assertEquals(1, sent.size)
                    assertEquals(listOf(leaderId, retained), DungeonPartyGatherRequest.decode(sent.single()).memberIds)
                } finally { tasks.close() }
            }
        }
    }

    @Test
    fun `closing a pending gather prevents a late native plugin message`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("PartyClosingTest")
            val leaderId = paper.addPlayer("Leader").uniqueId
            val leader = mockk<Player>(relaxUnitFun = true) {
                every { uniqueId } returns leaderId
                every { isOnline } returns true
            }
            val raw = Any()
            val team = partyTeam(raw, leaderId, setOf(leaderId))
            val members = CompletableFuture<List<TeamMemberSnapshot>>()
            val gateway = mockk<JustTeamsGateway> { every { membersAsync(raw) } returns members }
            val scheduler = TestTaskScheduler()
            val tasks = LifecycleTaskScope(scheduler)
            val coordinator = DungeonPartyCoordinator(plugin, gateway, tasks, null, plugin.logger, { _, _ -> })
            try {
                assertTrue(coordinator.gather(leader, team))
                coordinator.close()
                members.complete(emptyList())
                scheduler.tick()
                verify(exactly = 0) { leader.sendPluginMessage(any(), any(), any()) }
            } finally { coordinator.close(); tasks.close() }
        }
    }
}

private fun partyTeam(raw: Any, owner: UUID, members: Set<UUID>) = TeamSnapshot(
    raw, 7, "Party", "P", "", owner, 1, 0L, 0.0, members.size, members.size,
    members, emptySet(), 0L, false, false, false, true, emptyList(), emptyList(), emptyList(), emptySet(),
)
