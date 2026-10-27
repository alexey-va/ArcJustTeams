package ru.ruscrafting.justteams

import org.bukkit.GameMode
import org.bukkit.GameRules
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** A denial is authoritative; lack of a known denial is not permission from every combat plugin. */
internal enum class TeamPvpState(val localeKey: String) {
    WORLD("nameplate.pvp-world"),
    CLAN("nameplate.pvp-clan"),
    ALLY("nameplate.pvp-ally"),
    PROTECTED("nameplate.pvp-protected"),
    LANDS("nameplate.pvp-lands"),
    UNKNOWN("nameplate.pvp-unknown"),
}

/** Read-only main-thread assessment. Never dispatches a synthetic damage event or sets combat tags. */
internal class TeamPvpPolicy(
    private val pvpCheck: (Player, Player) -> TeamPvpState,
) {
    fun evaluate(attacker: Player, target: Player): TeamPvpState {
        if (attacker.world.uid != target.world.uid || target.world.getGameRuleValue(GameRules.PVP) == false) {
            return TeamPvpState.WORLD
        }
        if (!attacker.isOnline || !target.isOnline || attacker.isDead || target.isDead ||
            attacker.gameMode == GameMode.SPECTATOR ||
            target.gameMode == GameMode.CREATIVE || target.gameMode == GameMode.SPECTATOR || target.isInvulnerable
        ) return TeamPvpState.PROTECTED

        return pvpCheck(attacker, target)
    }
}

/** Older ARC versions do not export this optional API. Never load its typed adapter in that case. */
internal fun arcPvpNameplateCheck(plugin: Plugin): (Player, Player) -> TeamPvpState {
    try {
        Class.forName("ru.arc.dungeons.api.ArcPvpService", false, TeamPvpPolicy::class.java.classLoader)
    } catch (_: ClassNotFoundException) {
        return { _, _ -> TeamPvpState.UNKNOWN }
    } catch (_: LinkageError) {
        return { _, _ -> TeamPvpState.UNKNOWN }
    }
    return ArcPvpNameplateBridge(plugin)::check
}
