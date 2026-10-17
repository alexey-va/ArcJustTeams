package ru.ruscrafting.justteams

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.land.Land
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import me.angeschossen.lands.api.applicationframework.util.ULID
import ru.arc.core.BudgetedTaskQueue
import java.util.concurrent.CompletableFuture

internal data class TeamLand(
    val id: String,
    val name: String,
    val members: Int,
)

internal data class LandSyncResult(val added: Int, val failed: Int)

internal class LandsBridge(plugin: JavaPlugin, private val nativeWork: BudgetedTaskQueue) {
    private val integration = LandsIntegration.of(plugin)

    fun ownedLands(player: Player): List<TeamLand> {
        val lands = integration.getLandPlayer(player.uniqueId)?.lands ?: return emptyList()
        return lands.filterIsInstance<Land>().filter { it.exists() && it.ownerUID == player.uniqueId }
            .map(::snapshot).sortedBy { it.name.lowercase() }
    }

    fun linked(ids: Set<String>): List<TeamLand> = ids.mapNotNull(::land).map(::snapshot).sortedBy { it.name.lowercase() }

    fun ownedBy(id: String, playerId: UUID): Boolean = land(id)?.let { it.exists() && it.ownerUID == playerId } == true

    fun sync(landIds: Set<String>, memberIds: Set<UUID>, mayTrust: (String, UUID) -> Boolean = { _, _ -> true }): CompletableFuture<LandSyncResult> {
        var added = 0
        var failed = 0
        val lands = landIds.iterator()
        var active: Land? = null
        var activeId: String? = null
        var members = emptyList<UUID>().iterator()
        return nativeWork.submit({ true }) {
            if (!members.hasNext() && lands.hasNext()) {
                activeId = lands.next()
                active = land(checkNotNull(activeId))
                members = if (active == null) emptyList<UUID>().iterator() else memberIds.iterator()
            }
            if (members.hasNext()) {
                val playerId = members.next()
                active?.takeIf { it.exists() && mayTrust(checkNotNull(activeId), playerId) && it.ownerUID != playerId && !it.isTrusted(playerId) }?.let {
                    if (it.trustPlayer(playerId)) added++ else failed++
                }
            }
            !members.hasNext() && !lands.hasNext()
        }.thenApply { LandSyncResult(added, failed) }
    }

    private fun land(id: String): Land? = runCatching { integration.getLandByULID(ULID.fromString(id)) }
        .getOrNull()?.takeIf { it.exists() }

    private fun snapshot(land: Land) = TeamLand(
        id = land.ulid.toString(),
        name = land.name,
        members = land.membersAmount,
    )
}
