package ru.ruscrafting.justteams

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ru.arc.core.LifecycleTaskScope
import java.util.logging.Logger

internal class TeamLandReconciler(
    private val teams: JustTeamsGateway,
    private val lands: LandsBridge,
    private val tasks: LifecycleTaskScope,
    private val logger: Logger,
) : Listener {
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

    fun reconcile(team: TeamSnapshot): LandSyncResult = lands.sync(team.landIds, team.memberIds).also { result ->
        if (result.failed > 0) logger.warning(
            "Could not trust ${result.failed} team member(s) across linked Lands for team=${team.id}",
        )
    }

    private fun reconcileAll() {
        teams.allTeams().asSequence().filter { it.landIds.isNotEmpty() }.forEach(::reconcile)
    }
}
