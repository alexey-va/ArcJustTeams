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
import ru.arc.paper.runtime.PaperPluginHotload
import java.util.concurrent.CompletableFuture
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler

open class ArcJustTeamsPlugin : JavaPlugin(), Listener, CommandExecutor {
    private lateinit var dialogs: TeamDialogs
    private var pluginRuntime: PaperPluginRuntime? = null
    private var storage: BoundedAsyncExecutor? = null
    private var nativeWork: BudgetedTaskQueue? = null
    private var nameplates: TeamNameplateRuntime? = null
    private var teamsRuntime: JustTeamsGateway? = null
    private var ready = false
    private val hotload = PaperPluginHotload(this, ::stopRuntime)

    override fun onEnable() {
        hotload.beginGeneration()
        hotload.register()
        ready = false
        val lifecycle = PaperPluginRuntime(this, "arc-just-teams", BukkitTaskScheduler(this)).also {
            pluginRuntime = it
            it.start("version" to pluginMeta.version)
        }
        try {
            PaperArcRuntime.installScheduling(this)
            ArcLogging.install(PaperLoggingPlatform("ArcJustTeams", name), LoggingConfigSource { EmptyConfig })
            val worker = BoundedAsyncExecutor("arc-just-teams").also { storage = it }
            saveDefaultConfig()
            reloadConfig()
            val texts = Texts(this)
            val tasks = lifecycle.own(LifecycleTaskScope(BukkitTaskScheduler(this)))
            finishEnable(lifecycle, worker, tasks, texts)
        } catch (failure: Throwable) { failStartup(failure) }
    }

    private fun failStartup(failure: Throwable) {
        logger.log(java.util.logging.Level.SEVERE, "Could not prepare ArcJustTeams configuration", failure)
        server.pluginManager.disablePlugin(this)
    }

    private fun finishEnable(lifecycle: PaperPluginRuntime, worker: BoundedAsyncExecutor, tasks: LifecycleTaskScope, texts: Texts) {
        val nativeWork = lifecycle.own(BudgetedTaskQueue(tasks)).also { this.nativeWork = it }
        val teams = lifecycle.own(JustTeamsGateway(nativeWork, worker,
            ru.arc.paper.player.PaperOfflinePlayerData.capture(server))).also { teamsRuntime = it }
        if (server.pluginManager.isPluginEnabled("ArcRanks")) ClanQuestMembershipBridge.install(this, teams)
        val minimum = config.getInt("elite-mobs.minimum-team-members", 2).coerceAtLeast(2)
        val dialogRuntime = lifecycle.own(PaperDialogRuntime(this))
        val lands = if (server.pluginManager.isPluginEnabled("Lands")) LandsBridge(this, nativeWork) else null
        val landReconciler = lands?.let { TeamLandReconciler(teams, it, tasks, logger) }
        nameplates = lifecycle.own(TeamNameplateRuntime(this, teams, texts, BukkitTaskScheduler(this), nativeWork,
            arcPvpNameplateCheck(this)))
        lateinit var activeDialogs: TeamDialogs
        val eliteMobsParty = EliteMobsPartyBridge.load()
        val dungeonParties = lifecycle.own(DungeonPartyCoordinator(
            plugin = this,
            teams = teams,
            tasks = tasks,
            eliteMobs = eliteMobsParty,
            logger = logger,
            onResult = { player, result -> activeDialogs.showDungeonPartyResult(player, result) },
        ))
        activeDialogs = TeamDialogs(dialogRuntime, texts, teams, lands, landReconciler, tasks, minimum, dungeonParties, nativeWork, worker)
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
        ready = true
        lifecycle.reportHealthEvery(20L * 60L * 5L)
        logger.info("Native team hub enabled; Lands=${server.pluginManager.isPluginEnabled("Lands")}, EliteMobs=${server.pluginManager.isPluginEnabled("EliteMobs")}")
    }

    override fun onDisable() {
        hotload.prepare()
        hotload.close()
    }

    private fun stopRuntime(): Map<String, CompletableFuture<*>> {
        ready = false
        val queue = nativeWork.also { nativeWork = null }
        val runtime = pluginRuntime.also { pluginRuntime = null }
        val worker = storage.also { storage = null }
        val plates = nameplates.also { nameplates = null }
        val teams = teamsRuntime.also { teamsRuntime = null }
        var failure: Throwable? = null
        fun attempt(close: () -> Unit) {
            try { close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        attempt { queue?.close() }
        attempt { runtime?.close() }
        // Accepted justTeams reads/name/model work drains; native continuations are fenced and never awaited by workers.
        attempt { worker?.closeAsync()?.join() }
        failure?.let { logger.log(java.util.logging.Level.SEVERE, "Could not close ArcJustTeams", it) }
        attempt { Tasks.reset() }
        attempt { hotload.retireGameplayRegistrations() }
        attempt { ru.arc.logging.ArcLogging.close() }
        return mapOf("runtime" to (failure?.let { CompletableFuture.failedFuture<Unit>(it) }
            ?: CompletableFuture.completedFuture(Unit)), "nameplates" to
            (plates?.cleanup ?: CompletableFuture.completedFuture<Void>(null)), "buffs" to
            (teams?.cleanup ?: CompletableFuture.completedFuture<Void>(null)))
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        if (!ready || !::dialogs.isInitialized) return true
        if (!player.hasPermission("arcjustteams.use")) return true
        dialogs.begin(player)
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = emptyList()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun replaceEmptyTeamCommand(event: PlayerCommandPreprocessEvent) {
        if (!ready) return
        val command = event.message.trim().lowercase()
        val parts = command.split(Regex("\\s+"))
        if (parts.size == 2 && parts[0] in setOf("/team", "/clan", "/guild", "/teams", "/justteams:team") &&
            parts[1] in setOf("quests", "quest")) {
            if (!event.player.hasPermission("arcjustteams.use")) return
            event.isCancelled = true
            dialogs.openQuests(event.player)
            return
        }
        if (!config.getBoolean("replace-empty-team-command", true)) return
        if (command !in setOf("/team", "/clan", "/guild")) return
        if (!event.player.hasPermission("arcjustteams.use")) return
        if (!::dialogs.isInitialized) return
        event.isCancelled = true
        dialogs.begin(event.player)
    }
}
