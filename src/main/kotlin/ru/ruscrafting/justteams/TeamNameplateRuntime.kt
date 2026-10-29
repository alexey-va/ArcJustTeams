package ru.ruscrafting.justteams

import org.bukkit.Color
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.nameplate.NameplateUpsertResult
import ru.arc.nameplate.PlayerNameplateRegistry
import ru.arc.paper.api.ArcNameplateClaim
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.nameplate.NativePaperNameplateVisibilityPolicy
import ru.arc.paper.nameplate.PacketPaperNameplateDisplayFactory
import ru.arc.paper.nameplate.PaperNameplateOptions
import ru.arc.paper.nameplate.PaperNameplateViewerContent
import ru.arc.paper.nameplate.PaperNameplateVisibilityPolicy
import ru.arc.paper.nameplate.PaperPlayerNameplates
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Owns ordinary player plates; event claims suppress the complete ordinary plate for that target. */
internal class TeamNameplateRuntime(
    private val plugin: Plugin,
    private val teams: JustTeamsGateway,
    texts: Texts,
    scheduler: TaskScheduler,
    private val nativeWork: BudgetedTaskQueue,
    pvpCheck: (Player, Player) -> TeamPvpState,
) : AutoCloseable, Listener {
    private val tasks = LifecycleTaskScope(scheduler)
    private val clanRegistry = PlayerNameplateRegistry(MAX_TARGETS, 1, 160)
    private val healthRegistry = PlayerNameplateRegistry(MAX_TARGETS, 1, 160)
    private val registries = listOf(clanRegistry, healthRegistry)
    private val rows = TeamNameplateRows(texts)
    private val policy = TeamPvpPolicy(pvpCheck)
    private val packets = PaperPacketDisplays(plugin, "clan-nameplates", nativeWork)
    private val clanOptions = clanNameplateOptions()
    private val healthOptions = healthNameplateOptions()
    private val nativeVisibility = NativePaperNameplateVisibilityPolicy(clanOptions)
    private var clanRenderer: PaperPlayerNameplates? = null
    private var healthRenderer: PaperPlayerNameplates? = null
    private var refresh: CompletableFuture<Unit>? = null
    private var refreshFailureReported = false
    private var closed = false
    var cleanup: CompletableFuture<Void> = CompletableFuture.completedFuture(null)
        private set

    init {
        try {
            val visibility = PaperNameplateVisibilityPolicy { viewer, target ->
                nativeVisibility.canView(viewer, target) && mayShowOrdinaryNameplate(plugin, target)
            }
            // Retained display IDs follow the native nickname's world anchor with one-tick interpolation.
            clanRenderer = PaperPlayerNameplates.openViewerScoped(
                plugin, clanRegistry, clanOptions, scheduler, PacketPaperNameplateDisplayFactory.atNativeNameTag(packets, clanOptions), visibility,
                PaperNameplateViewerContent { _, _, common -> common },
            )
            healthRenderer = PaperPlayerNameplates.openViewerScoped(
                plugin, healthRegistry, healthOptions, scheduler, PacketPaperNameplateDisplayFactory.atNativeNameTag(packets, healthOptions), visibility,
                PaperNameplateViewerContent { viewer, target, common -> rows.forViewer(viewer, common, policy.evaluate(viewer, target)) },
            )
            plugin.server.pluginManager.registerEvents(this, plugin)
            checkNotNull(tasks.runTimer(0L, clanOptions.reconcilePeriodTicks, ::refreshRows))
        } catch (failure: Throwable) {
            runCatching(::close).onFailure(failure::addSuppressed)
            cleanup.whenComplete { _, cleanupFailure ->
                if (cleanupFailure != null) plugin.logger.log(Level.WARNING, "Clan nameplate startup cleanup failed", cleanupFailure)
            }
            throw failure
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        registries.forEach { it.clearTarget(event.player.uniqueId) }
    }

    private fun refreshRows() {
        if (closed || refresh?.isDone == false) return
        // The registry's existing 512-target bound also bounds the native player snapshot.
        val players = plugin.server.onlinePlayers.take(MAX_TARGETS)
        val ids = players.mapTo(hashSetOf()) { it.uniqueId }
        registries.forEach { registry -> (registry.targetIds() - ids).forEach(registry::clearTarget) }
        val pending = players.iterator()
        refresh = nativeWork.submit({ !closed }) {
            if (!pending.hasNext()) return@submit true
            val player = pending.next()
            if (player.isOnline) {
                val current = rows.common(player, teams.nameplateTeam(player.uniqueId))
                if (current.none { it.key == TeamNameplateRows.CLAN }) clanRegistry.remove(player.uniqueId, TeamNameplateRows.CLAN)
                current.forEach { row ->
                    val registry = if (row.key == TeamNameplateRows.CLAN) clanRegistry else healthRegistry
                    val result = registry.upsert(player.uniqueId, row)
                    check(result !is NameplateUpsertResult.Rejected) { "Clan nameplate ${row.key.value} rejected: $result" }
                }
            }
            !pending.hasNext()
        }.whenComplete { _, failure ->
            if (!closed && failure != null && !refreshFailureReported) {
                refreshFailureReported = true
                plugin.logger.log(Level.WARNING, "Clan nameplate capture failed; previous rows will be retried", failure)
            } else if (failure == null) refreshFailureReported = false
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (problem: Throwable) {
                if (failure == null) failure = problem else if (problem !== failure) failure.addSuppressed(problem)
            }
        }
        attempt { refresh?.cancel(false) }
        attempt(tasks::close)
        attempt { HandlerList.unregisterAll(this) }
        attempt { clanRenderer?.close() }
        clanRenderer = null
        attempt { healthRenderer?.close() }
        healthRenderer = null
        registries.forEach { registry -> attempt { registry.clearOwner(TeamNameplateRows.OWNER) } }
        attempt {
            try { cleanup = packets.closeAsync() } catch (problem: Throwable) {
                cleanup = CompletableFuture.failedFuture(problem)
                throw problem
            }
        }
        failure?.let { throw it }
    }

    private companion object {
        const val MAX_TARGETS = 512
    }
}

/** Fail closed while an installed ArcEvents generation has not published its ownership contract. */
internal fun mayShowOrdinaryNameplate(plugin: Plugin, target: Player): Boolean {
    val claims = plugin.server.servicesManager.getRegistrations(ArcNameplateClaim::class.java)
    if (plugin.server.pluginManager.isPluginEnabled("ArcEvents") && claims.none { it.plugin.name == "ArcEvents" }) return false
    return claims.none { it.provider.owns(target.uniqueId) }
}

/** Camera-plane rows around the unchanged native/TAB nickname, anchored at player head + 0.5. */
internal fun clanNameplateOptions(): PaperNameplateOptions = nameplateOptions(0.1F, 0.8F)

internal fun healthNameplateOptions(): PaperNameplateOptions = nameplateOptions(-0.415F, 0.65F)

/**
 * Pinned 1.21.11: native text background is [-9, 1] * 0.025 in camera-up coordinates;
 * one-line TextDisplay background is [0, 10] * 0.025 * scale above its local origin.
 */
private fun nameplateOptions(verticalOffset: Float, scale: Float): PaperNameplateOptions = PaperNameplateOptions(
    reconcilePeriodTicks = 4L,
    maxDistance = 32.0,
    lineWidth = 256,
    viewRange = 0.5F,
    scale = scale,
    verticalOffset = verticalOffset,
    shadowed = true,
    backgroundColor = Color.fromARGB(80, 11, 15, 18),
    hideInvisibleTargets = true,
    hideSpectatorTargets = true,
    requireLineOfSight = true,
    brightness = Display.Brightness(15, 15),
)
