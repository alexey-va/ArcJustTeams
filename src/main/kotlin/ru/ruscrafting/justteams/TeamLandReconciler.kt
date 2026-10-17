package ru.ruscrafting.justteams

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ru.arc.core.LifecycleTaskScope
import java.util.logging.Logger
import java.util.concurrent.CompletableFuture
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.whenCompleteSync

internal class TeamLandReconciler(
    private val teams: JustTeamsGateway,
    private val lands: LandsBridge,
    private val tasks: LifecycleTaskScope,
    private val logger: Logger,
    private val nativeWork: BudgetedTaskQueue,
) : Listener {
    private val pending = mutableMapOf<Int, CompletableFuture<LandSyncResult>>()
    fun start() {
        checkNotNull(tasks.runTimer(100L, 1_200L, ::reconcileAll)) {
            "Could not schedule team settlement reconciliation"
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        tasks.runLater(40L) {
            teams.team(event.player.uniqueId)?.takeIf { it.landIds.isNotEmpty() }?.let(::reconcile)
        }
    }

    fun reconcile(team: TeamSnapshot): CompletableFuture<LandSyncResult> = reconcileRaw(team.raw)

    private fun reconcileRaw(team: Any): CompletableFuture<LandSyncResult> {
        val id = teams.teamId(team)
        pending[id]?.let { return it }
        val future = teams.landMembershipAsync(team).thenCompose { membership ->
            lands.sync(membership.landIds, membership.memberIds) { land, member -> teams.mayTrust(membership, land, member) }
        }
        pending[id] = future
        future.whenCompleteSync(tasks) { result, failure ->
            pending.remove(id, future)
            if (failure != null) logger.log(java.util.logging.Level.WARNING, "Could not reconcile team=$id", failure)
            else if (result != null && result.failed > 0) logger.warning(
                "Could not trust ${result.failed} team member(s) across linked Lands for team=$id",
            )
        }
        return future
    }

    private fun reconcileAll() {
        val iterator = teams.rawTeams().iterator()
        nativeWork.submit({ true }) {
            if (iterator.hasNext()) reconcileRaw(iterator.next())
            !iterator.hasNext()
        }.whenCompleteSync(tasks) { _, failure ->
            if (failure != null) logger.log(java.util.logging.Level.WARNING, "Could not capture team settlement reconciliation", failure)
        }
    }
}
