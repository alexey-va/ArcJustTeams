package ru.ruscrafting.justteams

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

internal data class TeamLand(val id: String, val name: String, val ownerId: UUID, val chunks: Int, val maxChunks: Int)

internal class LandsBridge(plugin: JavaPlugin) {
    private val integration: Any? = runCatching {
        val type = Class.forName("me.angeschossen.lands.api.LandsIntegration")
        type.getMethod("of", Plugin::class.java).invoke(null, plugin)
    }.getOrNull()

    fun ownedLands(player: Player): List<TeamLand> {
        val api = integration ?: return emptyList()
        val landPlayer = api.javaClass.getMethod("getLandPlayer", UUID::class.java).invoke(api, player.uniqueId) ?: return emptyList()
        val lands = landPlayer.javaClass.getMethod("getLands").invoke(landPlayer) as? Collection<*> ?: return emptyList()
        return lands.filterNotNull().mapNotNull { land ->
            runCatching {
                val owner = land.javaClass.getMethod("getOwnerUID").invoke(land) as UUID
                if (owner != player.uniqueId) return@runCatching null
                TeamLand(
                    id = land.javaClass.getMethod("getULID").invoke(land).toString(),
                    name = land.javaClass.getMethod("getName").invoke(land) as String,
                    ownerId = owner,
                    chunks = land.javaClass.getMethod("getChunksAmount").invoke(land) as Int,
                    maxChunks = land.javaClass.getMethod("getMaxChunks").invoke(land) as Int,
                )
            }.getOrNull()
        }.sortedBy { it.name.lowercase() }
    }

    fun name(id: String?): String? {
        val api = integration ?: return null
        val target = id ?: return null
        val lands = api.javaClass.getMethod("getLands").invoke(api) as? Collection<*> ?: return null
        val land = lands.filterNotNull().firstOrNull { it.javaClass.getMethod("getULID").invoke(it).toString() == target } ?: return null
        return land.javaClass.getMethod("getName").invoke(land) as? String
    }

    fun available(): Boolean = integration != null
}
