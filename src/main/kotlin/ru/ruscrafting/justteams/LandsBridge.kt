package ru.ruscrafting.justteams

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.land.Land
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

internal data class TeamLand(
    val id: String,
    val name: String,
    val members: Int,
)

internal data class LandSyncResult(val added: Int, val failed: Int)

internal class LandsBridge(plugin: JavaPlugin) {
    private val integration = LandsIntegration.of(plugin)

    fun ownedLands(player: Player): List<TeamLand> {
        val lands = integration.getLandPlayer(player.uniqueId)?.lands ?: return emptyList()
        return lands.filterIsInstance<Land>().filter { it.exists() && it.ownerUID == player.uniqueId }
            .map(::snapshot).sortedBy { it.name.lowercase() }
    }

    fun linked(ids: Set<String>): List<TeamLand> = ids.mapNotNull(::land).map(::snapshot).sortedBy { it.name.lowercase() }

    fun ownedBy(id: String, playerId: UUID): Boolean = land(id)?.let { it.exists() && it.ownerUID == playerId } == true

    fun sync(landIds: Set<String>, memberIds: Set<UUID>): LandSyncResult {
        var added = 0
        var failed = 0
        linked(landIds).forEach { linked ->
            val land = land(linked.id) ?: return@forEach
            TeamLandPolicy.missingMembers(land.ownerUID, land.trustedPlayers.filterIsInstance<UUID>().toSet(), memberIds).forEach { playerId ->
                if (land.trustPlayer(playerId)) added++ else failed++
            }
        }
        return LandSyncResult(added, failed)
    }

    private fun land(id: String): Land? = integration.lands.firstOrNull { it.exists() && it.ulid.toString() == id }

    private fun snapshot(land: Land) = TeamLand(
        id = land.ulid.toString(),
        name = land.name,
        members = land.membersAmount,
    )
}
