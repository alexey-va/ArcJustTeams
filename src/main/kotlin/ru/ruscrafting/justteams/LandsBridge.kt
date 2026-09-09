package ru.ruscrafting.justteams

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.land.Land
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

internal data class TeamLand(val id: String, val name: String, val chunks: Int, val maxChunks: Int)

internal class LandsBridge(plugin: JavaPlugin) {
    private val integration = LandsIntegration.of(plugin)

    fun ownedLands(player: Player): List<TeamLand> {
        val lands = integration.getLandPlayer(player.uniqueId)?.lands ?: return emptyList()
        return lands.filterIsInstance<Land>().filter { it.exists() && it.ownerUID == player.uniqueId }.map { land ->
            TeamLand(land.ulid.toString(), land.name, land.chunksAmount, land.maxChunks)
        }.sortedBy { it.name.lowercase() }
    }

    fun name(id: String?): String? = id?.let { target -> integration.lands.firstOrNull { it.exists() && it.ulid.toString() == target }?.name }
}
