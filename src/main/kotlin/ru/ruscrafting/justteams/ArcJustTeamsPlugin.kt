package ru.ruscrafting.justteams

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.paper.menu.PaperDialogRuntime

class ArcJustTeamsPlugin : JavaPlugin(), Listener, CommandExecutor {
    private lateinit var dialogs: TeamDialogs
    private lateinit var dialogRuntime: PaperDialogRuntime

    override fun onEnable() {
        saveDefaultConfig()
        val teams = JustTeamsGateway(config.getString("elite-mobs.quest-id").orEmpty())
        val minimum = config.getInt("elite-mobs.minimum-team-members", 2).coerceAtLeast(2)
        dialogRuntime = PaperDialogRuntime(this)
        dialogs = TeamDialogs(dialogRuntime, Texts(this), teams, LandsBridge(this), minimum)
        server.pluginManager.registerEvents(this, this)
        if (config.getBoolean("elite-mobs.enabled", true) && server.pluginManager.isPluginEnabled("EliteMobs")) {
            server.pluginManager.registerEvents(TeamDungeonTracker(teams, minimum), this)
        }
        getCommand("clans")?.setExecutor(this)
        logger.info("Native team hub enabled; Lands=${server.pluginManager.isPluginEnabled("Lands")}, EliteMobs=${server.pluginManager.isPluginEnabled("EliteMobs")}")
    }

    override fun onDisable() {
        if (::dialogRuntime.isInitialized) dialogRuntime.close()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        if (!player.hasPermission("arcjustteams.use")) return true
        dialogs.open(player)
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun replaceEmptyTeamCommand(event: PlayerCommandPreprocessEvent) {
        if (!config.getBoolean("replace-empty-team-command", true)) return
        val command = event.message.trim().lowercase()
        if (command !in setOf("/team", "/clan", "/guild")) return
        if (!event.player.hasPermission("arcjustteams.use")) return
        event.isCancelled = true
        dialogs.open(event.player)
    }
}
