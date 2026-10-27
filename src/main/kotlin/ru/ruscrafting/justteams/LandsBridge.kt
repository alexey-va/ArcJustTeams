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

internal class LandsBridge(private val plugin: JavaPlugin, private val nativeWork: BudgetedTaskQueue) {
    private val integration = LandsIntegration.of(plugin)

    fun ownedLands(player: Player): List<TeamLand> {
        val lands = integration.getLandPlayer(player.uniqueId)?.lands ?: return emptyList()
        return lands.filterIsInstance<Land>().filter { it.exists() && it.ownerUID == player.uniqueId }
            .map(::snapshot).sortedBy { it.name.lowercase() }
    }

    fun linked(ids: Set<String>): List<TeamLand> = ids.mapNotNull(::land).map(::snapshot).sortedBy { it.name.lowercase() }

    fun ownedBy(id: String, playerId: UUID): Boolean = land(id)?.let { it.exists() && it.ownerUID == playerId } == true

    /** Delegates to Lands as the player, preserving its combat gate, fees, warmup and safe destination checks.
     * True means the command was accepted; Lands reports the eventual teleport or refusal itself.
     */
    fun travel(player: Player, landId: String): Boolean {
        check(plugin.server.isPrimaryThread) { "Settlement travel must start on the gameplay thread" }
        if (!player.isOnline) return false
        val target = land(landId) ?: return false
        if (target.spawnPosition == null) return false
        return plugin.server.dispatchCommand(player, "lands:lands spawn ${target.name}")
    }

    /** Main-thread cursor; storage preparation suspends it and completion includes every native trust. */
    fun sync(
        landIds: Set<String>,
        memberIds: Set<UUID>,
        prepareTrust: ((String) -> CompletableFuture<Boolean>)? = null,
        mayTrust: (String, UUID) -> Boolean = { _, _ -> true },
    ): CompletableFuture<LandSyncResult> {
        var added = 0
        var failed = 0
        val lands = landIds.iterator()
        var active: Land? = null
        var activeId: String? = null
        var members = emptyList<UUID>().iterator()
        val result = CompletableFuture<LandSyncResult>()
        fun eligible(land: Land, id: String, member: UUID): Boolean =
            land.exists() && land.ownerUID != member && !land.isTrusted(member) && mayTrust(id, member)
        fun trust(land: Land, member: UUID) {
            if (land.trustPlayer(member)) added++ else failed++
        }
        fun resume() {
            var preparation: CompletableFuture<Boolean>? = null
            var applyPrepared: (() -> Unit)? = null
            nativeWork.submit({ !result.isDone }) {
                if (!members.hasNext() && lands.hasNext()) {
                    activeId = lands.next()
                    active = land(checkNotNull(activeId))
                    members = if (active == null) emptyList<UUID>().iterator() else memberIds.iterator()
                }
                if (members.hasNext()) {
                    val playerId = members.next()
                    val id = checkNotNull(activeId)
                    active?.takeIf { eligible(it, id, playerId) }?.let { target ->
                        if (prepareTrust == null) trust(target, playerId)
                        else {
                            preparation = prepareTrust(id)
                            applyPrepared = { if (eligible(target, id, playerId)) trust(target, playerId) }
                        }
                    }
                }
                preparation != null || (!members.hasNext() && !lands.hasNext())
            }.whenComplete { _, captureFailure ->
                val prepared = preparation
                when {
                    captureFailure != null -> result.completeExceptionally(captureFailure)
                    prepared == null -> result.complete(LandSyncResult(added, failed))
                    else -> prepared.thenCompose { allowed ->
                        nativeWork.call({ !result.isDone }) {
                            if (allowed) checkNotNull(applyPrepared).invoke()
                            resume()
                        }
                    }.whenComplete { _, applyFailure ->
                        if (applyFailure != null) result.completeExceptionally(applyFailure)
                    }
                }
            }
        }
        resume()
        return result
    }

    private fun land(id: String): Land? = runCatching { integration.getLandByULID(ULID.fromString(id)) }
        .getOrNull()?.takeIf { it.exists() }

    private fun snapshot(land: Land) = TeamLand(
        id = land.ulid.toString(),
        name = land.name,
        members = land.membersAmount,
    )
}
