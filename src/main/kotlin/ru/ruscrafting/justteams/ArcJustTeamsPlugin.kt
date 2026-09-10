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
import ru.arc.config.EmptyConfig
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.runtime.PaperPluginRuntime

class ArcJustTeamsPlugin : JavaPlugin(), Listener, CommandExecutor {
    private lateinit var dialogs: TeamDialogs
    private var pluginRuntime: PaperPluginRuntime? = null

    override fun onEnable() {
        saveDefaultConfig()
        PaperArcRuntime.installScheduling(this)
        ArcLogging.install(PaperLoggingPlatform("ArcJustTeams", name), LoggingConfigSource { EmptyConfig })
        val lifecycle = PaperPluginRuntime(this, "arc-just-teams").also {
            pluginRuntime = it
            it.start("version" to pluginMeta.version)
        }
        val teams = JustTeamsGateway(config.getString("elite-mobs.quest-id").orEmpty())
        val minimum = config.getInt("elite-mobs.minimum-team-members", 2).coerceAtLeast(2)
        val dialogRuntime = lifecycle.own(PaperDialogRuntime(this))
        val lands = if (server.pluginManager.isPluginEnabled("Lands")) LandsBridge(this) else null
        val tasks = lifecycle.own(LifecycleTaskScope())
        val landReconciler = lands?.let { TeamLandReconciler(teams, it, tasks, logger) }
        lateinit var activeDialogs: TeamDialogs
        val eliteMobsParty = EliteMobsPartyBridge.load()
        if (server.pluginManager.isPluginEnabled("EliteMobs") && eliteMobsParty == null) {
            logger.warning("EliteMobs party API is unavailable on this backend; dungeon party arrivals will be rejected")
        }
        val dungeonParties = lifecycle.own(DungeonPartyCoordinator(
            plugin = this,
            teams = teams,
            tasks = tasks,
            eliteMobs = eliteMobsParty,
            logger = logger,
            onResult = { player, result -> activeDialogs.showDungeonPartyResult(player, result) },
        ))
        activeDialogs = TeamDialogs(dialogRuntime, Texts(this), teams, lands, landReconciler, tasks, minimum, dungeonParties)
        dialogs = activeDialogs
        server.pluginManager.registerEvents(this, this)
        landReconciler?.let {
            server.pluginManager.registerEvents(it, this)
            it.start()
        }
        if (config.getBoolean("elite-mobs.enabled", true) && server.pluginManager.isPluginEnabled("EliteMobs")) {
            server.pluginManager.registerEvents(TeamDungeonTracker(teams, minimum), this)
        }
        getCommand("clans")?.setExecutor(this)
        lifecycle.registerHealth("integrations") {
            RuntimeHealthContribution(
                state = RuntimeHealthState.UP,
                dependencies = mapOf(
                    "justteams" to server.pluginManager.isPluginEnabled("justTeams"),
                    "lands" to server.pluginManager.isPluginEnabled("Lands"),
                    "elitemobs" to server.pluginManager.isPluginEnabled("EliteMobs"),
                ),
            )
        }
        lifecycle.ready("lands" to (lands != null))
        lifecycle.reportHealthEvery(20L * 60L * 5L)
        logger.info("Native team hub enabled; Lands=${server.pluginManager.isPluginEnabled("Lands")}, EliteMobs=${server.pluginManager.isPluginEnabled("EliteMobs")}")
    }

    override fun onDisable() {
        runCatching { pluginRuntime?.close() }
        pluginRuntime = null
        Tasks.reset()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        if (!player.hasPermission("arcjustteams.use")) return true
        dialogs.begin(player)
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun replaceEmptyTeamCommand(event: PlayerCommandPreprocessEvent) {
        if (!config.getBoolean("replace-empty-team-command", true)) return
        val command = event.message.trim().lowercase()
        if (command !in setOf("/team", "/clan", "/guild")) return
        if (!event.player.hasPermission("arcjustteams.use")) return
        event.isCancelled = true
        dialogs.begin(event.player)
    }
}
