package ru.ruscrafting.justteams

import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.ranks.api.ClanMembershipProvider
import ru.ruscrafting.ranks.api.ClanQuestMenuApi
import org.bukkit.entity.Player
import org.bukkit.Bukkit

/** ArcRanks owns quest state; this addon alone reads the native team's cached membership. */
internal object ClanQuestMembershipBridge {
    fun openQuests(player: Player): Boolean {
        if (!Bukkit.getPluginManager().isPluginEnabled("ArcRanks")) return false
        return Bukkit.getServicesManager().load(ClanQuestMenuApi::class.java)?.open(player.uniqueId) == true
    }

    fun install(plugin: JavaPlugin, teams: JustTeamsGateway) {
        plugin.server.servicesManager.register(
            ClanMembershipProvider::class.java,
            ClanMembershipProvider(teams::clanMembership),
            plugin,
            ServicePriority.Normal,
        )
    }
}
