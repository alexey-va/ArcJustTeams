package ru.ruscrafting.justteams

import com.magmaguy.elitemobs.api.DungeonCompleteEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

internal class TeamDungeonTracker(
    private val teams: JustTeamsGateway,
    private val minimumMembers: Int,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun completed(event: DungeonCompleteEvent) {
        val teamById = linkedMapOf<Int, Any>()
        val participants = event.dungeonInstance.participants.mapNotNull { player ->
            val team = teams.teamFor(player.uniqueId) ?: return@mapNotNull null
            val teamId = teams.teamId(team)
            teamById.putIfAbsent(teamId, team)
            TeamParticipation(player.uniqueId, teamId)
        }
        qualifyingTeamIds(participants, minimumMembers).forEach { teamId ->
            teamById[teamId]?.let(teams::incrementDungeonRuns)
        }
    }
}

internal data class TeamParticipation(val playerId: java.util.UUID, val teamId: Int)

internal fun qualifyingTeamIds(participants: Collection<TeamParticipation>, minimumMembers: Int): Set<Int> =
    participants.distinctBy { it.playerId }.groupingBy { it.teamId }.eachCount()
        .filterValues { it >= minimumMembers.coerceAtLeast(2) }.keys
