package ru.ruscrafting.justteams

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.PluginMessageListener
import ru.arc.core.LifecycleTaskScope
import ru.arc.dungeonparty.DungeonPartyGatherArrival
import ru.arc.dungeonparty.DungeonPartyGatherRequest
import java.util.LinkedHashMap
import java.util.UUID
import java.util.logging.Logger

internal data class DungeonPartyResult(
    val readyMemberIds: List<UUID>,
    val skipped: Int,
    val unavailable: Boolean = false,
)

internal class DungeonPartyCoordinator(
    private val plugin: Plugin,
    private val teams: JustTeamsGateway,
    private val tasks: LifecycleTaskScope,
    private val eliteMobs: EliteMobsPartyBridge?,
    private val logger: Logger,
    private val onResult: (Player, DungeonPartyResult) -> Unit,
) : PluginMessageListener, AutoCloseable {
    private val completed = object : LinkedHashMap<UUID, Unit>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Unit>?): Boolean = size > 128
    }

    init {
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, DungeonPartyGatherRequest.CHANNEL)
        plugin.server.messenger.registerIncomingPluginChannel(plugin, DungeonPartyGatherArrival.CHANNEL, this)
    }

    fun gather(leader: Player, team: TeamSnapshot): Boolean {
        if (!leader.isOnline || leader.uniqueId !in team.memberIds) return false
        val candidates = listOf(leader.uniqueId) + teams.members(team.raw)
            .asSequence()
            .map(TeamMemberSnapshot::id)
            .filterNot(leader.uniqueId::equals)
            .take(DungeonPartyGatherRequest.MAX_CANDIDATES - 1)
            .toList()
        val request = DungeonPartyGatherRequest(UUID.randomUUID(), leader.uniqueId, candidates)
        leader.sendPluginMessage(plugin, DungeonPartyGatherRequest.CHANNEL, request.encode())
        return true
    }

    fun travel(leader: Player): Boolean {
        if (!leader.isOnline) return false
        val request = DungeonPartyGatherRequest(UUID.randomUUID(), leader.uniqueId, listOf(leader.uniqueId))
        leader.sendPluginMessage(plugin, DungeonPartyGatherRequest.CHANNEL, request.encode())
        return true
    }

    override fun onPluginMessageReceived(channel: String, carrier: Player, bytes: ByteArray) {
        if (channel != DungeonPartyGatherArrival.CHANNEL) return
        val arrival = runCatching { DungeonPartyGatherArrival.decode(bytes) }
            .getOrElse { failure ->
                logger.warning("Rejected malformed dungeon party arrival: ${failure.message}")
                return
            }
        if (carrier.uniqueId != arrival.leaderId) return
        synchronized(completed) {
            if (completed.put(arrival.operationId, Unit) != null) return
        }
        tasks.runLater(10L) { assemble(arrival) }
    }

    private fun assemble(arrival: DungeonPartyGatherArrival) {
        val leader = Bukkit.getPlayer(arrival.leaderId) ?: return
        val team = teams.team(leader.uniqueId)
        if (team == null || leader.uniqueId !in team.memberIds) {
            onResult(leader, DungeonPartyResult(emptyList(), arrival.memberIds.size, unavailable = true))
            return
        }
        val localMembers = arrival.memberIds.mapNotNull(Bukkit::getPlayer)
            .filter { member -> teams.team(member.uniqueId)?.id == team.id }
        val bridge = eliteMobs
        if (bridge == null) {
            onResult(leader, DungeonPartyResult(emptyList(), localMembers.size, unavailable = true))
            return
        }
        val assembly = runCatching { bridge.assemble(leader, localMembers) }
            .getOrElse { failure ->
                logger.warning("EliteMobs party assembly failed: ${failure.message}")
                onResult(leader, DungeonPartyResult(emptyList(), localMembers.size, unavailable = true))
                return
            }
        val ready = assembly.memberIds.filter(team.memberIds::contains)
        val result = DungeonPartyResult(
            readyMemberIds = ready,
            skipped = (arrival.memberIds.toSet() - ready.toSet()).size,
            unavailable = !assembly.ready,
        )
        ready.mapNotNull(Bukkit::getPlayer).forEach { onResult(it, result) }
        if (leader.uniqueId !in ready) onResult(leader, result)
    }

    override fun close() {
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin, DungeonPartyGatherArrival.CHANNEL, this)
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, DungeonPartyGatherRequest.CHANNEL)
        synchronized(completed) { completed.clear() }
    }
}
