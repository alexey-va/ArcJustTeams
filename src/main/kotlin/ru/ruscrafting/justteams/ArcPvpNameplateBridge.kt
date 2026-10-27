package ru.ruscrafting.justteams

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.dungeons.api.ArcPvpService
import java.util.logging.Level

/** Isolated optional API boundary. ARC owns settlement and clan rules; this query stays silent. */
internal class ArcPvpNameplateBridge(private val plugin: Plugin) {
    private val serviceType = ArcPvpService::class.java
    private var failureReported = false

    fun check(attacker: Player, target: Player): TeamPvpState = try {
        val service = plugin.server.servicesManager.load(serviceType)
        when (service?.check(attacker, target, attacker)) {
            ArcPvpService.Result.SETTLEMENT -> TeamPvpState.LANDS
            ArcPvpService.Result.TEAM, ArcPvpService.Result.TEAM_PROJECTILE,
            ArcPvpService.Result.TEAM_TNT -> TeamPvpState.CLAN
            ArcPvpService.Result.ALLY -> TeamPvpState.ALLY
            // ALLOWED covers only ARC's settlement/clan policy, not CMI, regions, scripts or minigames.
            ArcPvpService.Result.ALLOWED, ArcPvpService.Result.UNAVAILABLE, null -> TeamPvpState.UNKNOWN
        }
    } catch (failure: RuntimeException) {
        unavailable(failure)
    } catch (failure: LinkageError) {
        unavailable(failure)
    }

    private fun unavailable(failure: Throwable): TeamPvpState {
        if (!failureReported) {
            failureReported = true
            plugin.logger.log(Level.WARNING, "Clan nameplate PvP query is unavailable", failure)
        }
        return TeamPvpState.UNKNOWN
    }
}
