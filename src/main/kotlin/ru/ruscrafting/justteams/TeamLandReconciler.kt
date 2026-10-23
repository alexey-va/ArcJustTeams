package ru.ruscrafting.justteams

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ru.arc.core.LifecycleTaskScope
import java.util.logging.Logger
import java.util.concurrent.CompletableFuture
import ru.arc.core.whenCompleteSync

internal class TeamLandReconciler(
    private val teams: JustTeamsGateway,
    private val lands: LandsBridge,
    private val tasks: LifecycleTaskScope,
    private val logger: Logger,
) : Listener {
    private val pending = mutableMapOf<Int, CompletableFuture<LandSyncResult>>()
    private var sweep: CompletableFuture<Unit>? = null
    fun start() {
        checkNotNull(tasks.runTimer(100L, 1_200L, ::reconcileAll)) {
            "Could not schedule team settlement reconciliation"
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        tasks.runLater(40L) {
            teams.teamFor(event.player.uniqueId)?.let(::reconcileRaw)
        }
    }

    fun reconcile(team: TeamSnapshot): CompletableFuture<LandSyncResult> = reconcileRaw(team.raw)

    private fun reconcileRaw(team: Any): CompletableFuture<LandSyncResult> {
        val id = teams.teamId(team)
        pending[id]?.let { return it }
        val token = tasks.token()
        val future = teams.landMembershipAsync(team).thenCompose { membership ->
            tasks.callSync(token) {
                lands.sync(membership.landIds, membership.memberIds,
                    prepareTrust = { land -> teams.landStillLinkedAsync(membership, land) },
                    mayTrust = { land, member -> teams.mayTrust(membership, land, member) })
            }.thenCompose { it }
        }
        pending[id] = future
        future.whenCompleteSync(tasks, token) { result, failure ->
            pending.remove(id, future)
            if (failure != null) logger.log(java.util.logging.Level.WARNING, "Could not reconcile team=$id", failure)
            else if (result != null && result.failed > 0) logger.warning(
                "Could not trust ${result.failed} team member(s) across linked Lands for team=$id",
            )
        }
        return future
    }

    private fun reconcileAll() {
        if (sweep != null) return
        val token = tasks.token()
        val future = teams.rawTeamsAsync().thenCompose { loaded ->
            val iterator = loaded.iterator()
            // Chain whole teams: a slow store must not turn one sweep into a burst of worker admissions.
            fun next(): CompletableFuture<Unit> = tasks.callSync(token) {
                if (iterator.hasNext()) reconcileRaw(iterator.next()) else null
            }.thenCompose { team ->
                if (team == null) CompletableFuture.completedFuture(Unit)
                else team.handle { _, _ -> Unit }.thenCompose { next() }
            }
            next()
        }
        sweep = future
        future.whenCompleteSync(tasks, token) { _, failure ->
            if (sweep === future) sweep = null
            if (failure != null) logger.log(java.util.logging.Level.WARNING, "Could not capture team settlement reconciliation", failure)
        }
    }
}
