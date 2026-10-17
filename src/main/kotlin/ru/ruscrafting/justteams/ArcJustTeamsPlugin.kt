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
import ru.arc.core.whenCompleteSync
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import java.util.concurrent.CompletableFuture

open class ArcJustTeamsPlugin : JavaPlugin(), Listener, CommandExecutor {
    private lateinit var dialogs: TeamDialogs
    private var pluginRuntime: PaperPluginRuntime? = null
    private class StorageGeneration(val worker: BoundedAsyncExecutor) {
        val drain = CompletableFuture<Unit>()
        var nativeWork: BudgetedTaskQueue? = null
    }
    private var generation: StorageGeneration? = null
    private var predecessor = CompletableFuture.completedFuture(Unit)
    private var ready = false
    internal fun drained(): CompletableFuture<Unit> = (generation?.drain ?: predecessor).copy()

    override fun onEnable() {
        ready = false
        val lifecycle = PaperPluginRuntime(this, "arc-just-teams", BukkitTaskScheduler(this)).also {
            pluginRuntime = it
            it.start("version" to pluginMeta.version)
        }
        predecessor.whenCompleteSync(lifecycle.tasks) { _, failure ->
            if (pluginRuntime !== lifecycle) return@whenCompleteSync
            if (failure != null) failStartup(failure) else beginGeneration(lifecycle)
        }
    }

    private fun beginGeneration(lifecycle: PaperPluginRuntime) {
        try {
            PaperArcRuntime.installScheduling(this)
            ArcLogging.install(PaperLoggingPlatform("ArcJustTeams", name), LoggingConfigSource { EmptyConfig })
            val owner = StorageGeneration(BoundedAsyncExecutor("arc-just-teams"))
            generation = owner
            val tasks = lifecycle.own(LifecycleTaskScope(BukkitTaskScheduler(this)))
            owner.worker.submit {
                saveDefaultConfig()
                reloadConfig()
                Texts(this)
            }.whenCompleteSync(tasks) { texts, failure ->
                if (generation !== owner || pluginRuntime !== lifecycle) return@whenCompleteSync
                if (failure != null || texts == null) failStartup(failure ?: IllegalStateException("Missing team configuration"))
                else runCatching { finishEnable(lifecycle, owner, tasks, texts) }.onFailure(::failStartup)
            }
        } catch (failure: Throwable) { failStartup(failure) }
    }

    private fun failStartup(failure: Throwable) {
        logger.log(java.util.logging.Level.SEVERE, "Could not prepare ArcJustTeams configuration", failure)
        server.pluginManager.disablePlugin(this)
    }

    private fun finishEnable(lifecycle: PaperPluginRuntime, owner: StorageGeneration, tasks: LifecycleTaskScope, texts: Texts) {
        val worker = owner.worker
        val nativeWork = lifecycle.own(BudgetedTaskQueue(tasks)).also { owner.nativeWork = it }
        val teams = JustTeamsGateway(config.getString("elite-mobs.quest-id").orEmpty(), nativeWork, worker,
            ru.arc.paper.player.PaperOfflinePlayerData.capture(server))
        val minimum = config.getInt("elite-mobs.minimum-team-members", 2).coerceAtLeast(2)
        val dialogRuntime = lifecycle.own(PaperDialogRuntime(this))
        val lands = if (server.pluginManager.isPluginEnabled("Lands")) LandsBridge(this, nativeWork) else null
        val landReconciler = lands?.let { TeamLandReconciler(teams, it, tasks, logger, nativeWork) }
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
        ready = false
        val owner = generation
        generation = null
        if (owner != null) predecessor = owner.drain
        val lifecycle = pluginRuntime
        pluginRuntime = null
        var failure = runCatching { owner?.nativeWork?.close() }.exceptionOrNull()
        try { lifecycle?.close() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
        owner?.let {
            val nativeFailure = failure
            it.worker.closeAsync { nativeFailure?.let { error -> throw error } }.whenComplete { _, error ->
                if (error == null) it.drain.complete(Unit) else {
                    it.drain.completeExceptionally(error)
                    logger.log(java.util.logging.Level.SEVERE, "Could not close ArcJustTeams generation", error)
                }
            }
        }
        Tasks.reset()
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
        if (!config.getBoolean("replace-empty-team-command", true)) return
        val command = event.message.trim().lowercase()
        if (command !in setOf("/team", "/clan", "/guild")) return
        if (!event.player.hasPermission("arcjustteams.use")) return
        if (!::dialogs.isInitialized) return
        event.isCancelled = true
        dialogs.begin(event.player)
    }
}
