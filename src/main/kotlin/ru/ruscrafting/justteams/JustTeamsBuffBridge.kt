package ru.ruscrafting.justteams

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import ru.arc.core.BudgetedTaskQueue
import java.util.logging.Level

/**
 * Exact justTeams 2.8.1 boundary. A headless native menu runs the existing action;
 * its null viewer makes ClickContext.refresh return without opening an inventory.
 * A fixed-pool epoch fences native task return before the next addon mutation.
 * Completion observes selection after that fence, not SQL success or durability.
 */
internal class JustTeamsBuffBridge private constructor(
    private val plugin: Any,
    private val nativeWork: BudgetedTaskQueue,
    private val fenceTimeoutMillis: Long,
    type: (String) -> Class<*>,
) : AutoCloseable {
    private val pluginType = type("eu.kotori.justTeams.JustTeams")
    private val teamType = type("eu.kotori.justTeams.team.Team")
    private val buffType = type("eu.kotori.justTeams.managers.TeamBuffManager\$BuffDef")
    private val menuType = type("eu.kotori.justTeams.gui.sub.TeamBuffMenu")
    private val menuBaseType = type("eu.kotori.justapi.gui.Menu")
    private val contextType = type("eu.kotori.justapi.gui.ClickContext")
    private val teamManagerGetter = pluginType.getMethod("getTeamManager")
    private val taskRunnerGetter = pluginType.getMethod("getTaskRunner")
    private val nativeExecutor = taskRunnerGetter.returnType.getDeclaredField("asyncExecutor").also {
        require(it.type == ExecutorService::class.java)
        it.isAccessible = true
    }
    private val taskRunner = taskRunnerGetter.invoke(plugin)
    private val executor = nativeExecutor.get(taskRunner) as ThreadPoolExecutor
    private val parties = executor.corePoolSize
    private val buffManagerGetter = pluginType.getMethod("getTeamBuffManager")
    private val teamManagerType = teamManagerGetter.returnType
    private val buffManagerType = buffManagerGetter.returnType
    private val cacheLock = teamManagerType.getDeclaredField("cacheLock").also {
        require(it.type == Any::class.java)
        it.isAccessible = true
    }
    private val teamNameCache = teamManagerType.getDeclaredField("teamNameCache").also {
        require(it.type == Map::class.java)
        it.isAccessible = true
    }
    private val cachedTeam = method(teamManagerType, "getPlayerTeamCached", teamType, UUID::class.java)
    private val teamId = method(teamType, "getId", Int::class.javaPrimitiveType!!)
    private val member = method(teamType, "isMember", Boolean::class.javaPrimitiveType!!, UUID::class.java)
    private val elevated = method(teamType, "hasElevatedPermissions", Boolean::class.javaPrimitiveType!!, UUID::class.java)
    private val selected = method(teamType, "getActiveBuffs", Set::class.java)
    private val points = method(teamType, "getPoints", Long::class.javaPrimitiveType!!)
    private val enabled = method(buffManagerType, "isEnabled", Boolean::class.javaPrimitiveType!!)
    private val pool = method(buffManagerType, "getPool", Map::class.java)
    private val maximum = method(buffManagerType, "getMaxActive", Int::class.javaPrimitiveType!!)
    private val key = buffType.getField("key").also { require(it.type == String::class.java) }
    private val cost = buffType.getField("pointsCost").also { require(it.type == Long::class.javaPrimitiveType) }
    private val menuConstructor = menuType.getConstructor(pluginType, Player::class.java, teamType)
    private val contextConstructor = contextType.getConstructor(Player::class.java, Int::class.javaPrimitiveType!!,
        ClickType::class.java, menuBaseType, ItemStack::class.java)
    private val toggle = menuType.getDeclaredMethod("toggle", contextType, buffType).also {
        require(it.returnType == Void.TYPE)
        it.isAccessible = true
    }

    private val logger = Bukkit.getLogger()
    private val gate = Any()
    private var closed = false
    private var disabled = false
    // ponytail: one global mutation; per-clan admission needs a native result/transaction API.
    private var pending: Pending? = Pending()
    val cleanup = CompletableFuture<Void>()

    init {
        require(pluginType.isInstance(plugin))
        require(menuBaseType.isAssignableFrom(menuType))
        require(executor.javaClass == ThreadPoolExecutor::class.java)
        require(parties in 1..32 && executor.maximumPoolSize == parties && !executor.allowsCoreThreadTimeOut())
        require(executor.queue.javaClass == LinkedBlockingQueue::class.java)
        require(executor.rejectedExecutionHandler.javaClass == ThreadPoolExecutor.AbortPolicy::class.java)
        require(fenceTimeoutMillis > 0)
        startFence(checkNotNull(pending))
    }

    fun available(): Boolean = synchronized(gate) { !closed && !disabled }

    /** Called by the gateway on Main. Pending/recovery clicks never replay the native action. */
    fun setBuff(player: Player, expectedTeamId: Int, buffId: String, desired: Boolean,
        expectedPointsCost: Long): CompletableFuture<BuffAttempt> {
        check(Bukkit.isPrimaryThread()) { "Native buff action must run on the gameplay thread" }
        var retry: Pending? = null
        val operation = synchronized(gate) {
            if (closed) return CompletableFuture.completedFuture(BuffAttempt.UNAVAILABLE)
            val outstanding = pending
            if (outstanding != null) {
                retry = outstanding
                null
            } else {
                if (disabled) return CompletableFuture.completedFuture(BuffAttempt.UNAVAILABLE)
                Pending(player.uniqueId, expectedTeamId, buffId, desired, CompletableFuture()).also { pending = it }
            }
        }
        if (operation == null) {
            startFence(checkNotNull(retry))
            return CompletableFuture.completedFuture(BuffAttempt.PENDING)
        }
        try {
            val result = if (!player.isOnline) BuffAttempt.NOT_ALLOWED else {
                val manager = teamManagerGetter.invoke(plugin)
                synchronized(cacheLock.get(manager)) {
                    setBuffCached(manager, player, expectedTeamId, buffId, desired, expectedPointsCost, operation)
                }
            }
            if (operation.invoked) startFence(operation)
            else {
                synchronized(gate) { if (pending === operation) pending = null }
                checkNotNull(operation.result).complete(result)
            }
        } catch (failure: Throwable) {
            operation.nativeFailure = failure.cause ?: failure
            logger.log(Level.WARNING,
                "ArcJustTeams native buff action failed: team=$expectedTeamId buff=$buffId enabled=$desired player=${player.uniqueId}",
                operation.nativeFailure)
            if (operation.invoked) startFence(operation)
            else {
                synchronized(gate) { disabled = true; if (pending === operation) pending = null }
                checkNotNull(operation.result).complete(BuffAttempt.FAILED)
            }
        }
        return checkNotNull(operation.result)
    }

    private fun setBuffCached(manager: Any, player: Player, expectedTeamId: Int, buffId: String, desired: Boolean,
        expectedPointsCost: Long, operation: Pending): BuffAttempt {
        val team = cachedTeam.invoke(manager, player.uniqueId) ?: return BuffAttempt.CHANGED
        if (teamId.invoke(team) != expectedTeamId) return BuffAttempt.CHANGED
        // Native getTeamPoints uses getTeamById, whose miss falls back to synchronous storage.
        // Hold its own short cache monitor through toggle so that lookup is guaranteed to hit.
        if ((teamNameCache.get(manager) as Map<*, *>).values.none { it === team }) return BuffAttempt.UNAVAILABLE
        if (member.invoke(team, player.uniqueId) != true || elevated.invoke(team, player.uniqueId) != true) {
            return BuffAttempt.NOT_ALLOWED
        }
        val buffs = buffManagerGetter.invoke(plugin) ?: return BuffAttempt.UNAVAILABLE
        if (enabled.invoke(buffs) != true) return BuffAttempt.UNAVAILABLE
        val buff = (pool.invoke(buffs) as Map<*, *>)[buffId] ?: return BuffAttempt.CHANGED
        if (!buffType.isInstance(buff) || key.get(buff) != buffId || expectedPointsCost < 0 ||
            cost.getLong(buff) != expectedPointsCost) return BuffAttempt.CHANGED
        val active = selected.invoke(team) as Set<*>
        if ((buffId in active) == desired) return BuffAttempt.CHANGED
        if (desired) {
            if (active.size >= maximum.invoke(buffs) as Int) return BuffAttempt.MAXIMUM
            if (expectedPointsCost > 0 && (points.invoke(team) as Long) < expectedPointsCost) return BuffAttempt.NOT_ENOUGH_POINTS
        }

        check(nativeExecutor.get(taskRunner) === executor && taskRunnerGetter.invoke(plugin) === taskRunner)
        check(!executor.isShutdown) { "Native buff executor is shut down" }
        // Native constructor/title formatting accept null; refresh's open guard returns before scheduling.
        val menu = menuConstructor.newInstance(plugin, null, team)
        val context = contextConstructor.newInstance(player, -1, ClickType.LEFT, menu, null)
        synchronized(gate) {
            if (closed) return BuffAttempt.UNAVAILABLE
            operation.team = team
            operation.invoked = true
            toggle.invoke(menu, context, buff)
        }
        return if (((selected.invoke(team) as Set<*>).contains(buffId)) != desired) BuffAttempt.FAILED
        else if (desired) BuffAttempt.ENABLED else BuffAttempt.DISABLED
    }

    private fun startFence(operation: Pending) {
        val epoch = synchronized(gate) {
            if (closed || pending !== operation || operation.epoch != null) return
            Epoch(parties, fenceTimeoutMillis).also {
                operation.fenceOutcomeHandled = false
                operation.epoch = it
            }
        }
        fun scheduleReadback() {
            if (synchronized(gate) { closed }) {
                if (epoch.returned.isDone) cleanup.complete(null)
            } else {
                nativeWork.call({ synchronized(gate) { !closed } }) {
                    finishFence(operation, epoch)
                }.whenComplete { _, schedulingFailure ->
                    if (schedulingFailure != null) operation.result?.completeExceptionally(schedulingFailure)
                }
            }
        }
        epoch.outcome.whenComplete { _, _ -> scheduleReadback() }
        epoch.returned.whenComplete { _, _ -> scheduleReadback() }
        epoch.start(executor)
    }

    private fun finishFence(operation: Pending, epoch: Epoch) {
        check(Bukkit.isPrimaryThread())
        synchronized(gate) {
            if (closed || pending !== operation || operation.epoch !== epoch || !epoch.outcome.isDone) return
        }
        val failure = epoch.outcome.getNow(null)
        if (failure != null) {
            if (!operation.fenceOutcomeHandled) {
                operation.fenceOutcomeHandled = true
                logger.log(Level.WARNING,
                    "ArcJustTeams buff fence unresolved; only fence retry is allowed: team=${operation.teamId} buff=${operation.buffId}", failure)
                operation.result?.complete(BuffAttempt.PENDING)
            }
            if (epoch.returned.isDone) synchronized(gate) {
                if (pending === operation && operation.epoch === epoch) operation.epoch = null
            }
            return
        }
        if (!epoch.returned.isDone) return
        val result = try {
            if (operation.nativeFailure != null) BuffAttempt.FAILED
            else if (operation.team == null) BuffAttempt.PENDING // Startup fence has no player action.
            else {
                val manager = teamManagerGetter.invoke(plugin)
                val current = cachedTeam.invoke(manager, operation.playerId)
                if (current !== operation.team || teamId.invoke(current) != operation.teamId) BuffAttempt.CHANGED
                else if (((selected.invoke(current) as Set<*>).contains(operation.buffId)) != operation.desired) BuffAttempt.FAILED
                else if (operation.desired) BuffAttempt.ENABLED else BuffAttempt.DISABLED
            }
        } catch (readFailure: Throwable) {
            operation.nativeFailure = readFailure.cause ?: readFailure
            logger.log(Level.WARNING, "ArcJustTeams buff readback failed after native task return", operation.nativeFailure)
            BuffAttempt.FAILED
        }
        synchronized(gate) {
            if (closed || pending !== operation) return
            if (operation.nativeFailure != null) disabled = true
            pending = null
        }
        operation.result?.complete(result)
    }

    /** Stops only our markers. The borrowed justTeams executor is never replaced or shut down. */
    override fun close() {
        val outstanding = synchronized(gate) {
            if (closed) return
            closed = true
            pending
        }
        outstanding?.result?.complete(BuffAttempt.UNAVAILABLE)
        val epoch = outstanding?.epoch
        if (epoch == null) cleanup.complete(null)
        else {
            epoch.abort(CancellationException("ArcJustTeams buff bridge closed"), executor)
            epoch.returned.whenComplete { _, _ -> cleanup.complete(null) }
        }
    }

    private class Pending(
        val playerId: UUID? = null,
        val teamId: Int? = null,
        val buffId: String? = null,
        val desired: Boolean = false,
        val result: CompletableFuture<BuffAttempt>? = null,
    ) {
        var team: Any? = null
        var invoked = false
        var nativeFailure: Throwable? = null
        var epoch: Epoch? = null
        var fenceOutcomeHandled = false
    }

    /** All N FIFO workers must rendezvous; one early marker cannot overtake an earlier slow task. */
    private class Epoch(private val parties: Int, private val timeoutMillis: Long) {
        private val barrier = CyclicBarrier(parties)
        private val remaining = AtomicInteger(parties)
        private val failure = AtomicReference<Throwable?>()
        private val markers = List(parties) { Marker() }
        val outcome = CompletableFuture<Throwable?>()
        val returned = CompletableFuture<Void>()

        fun start(executor: ThreadPoolExecutor) {
            // The overall deadline also covers markers still queued behind busy native workers.
            val deadline = CompletableFuture<Unit>()
            deadline.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenComplete { _, timeout ->
                if (timeout != null) abort(timeout)
            }
            outcome.whenComplete { _, _ -> deadline.complete(Unit) }
            markers.forEachIndexed { index, marker ->
                if (failure.get() != null) marker.cancelUnstarted()
                else try {
                    executor.execute(marker)
                } catch (admissionFailure: Throwable) {
                    abort(admissionFailure)
                    markers.drop(index).forEach(Marker::cancelUnstarted)
                    return
                }
            }
        }

        fun abort(reason: Throwable, removeFrom: ThreadPoolExecutor? = null) {
            if (failure.compareAndSet(null, reason)) {
                barrier.reset()
                outcome.complete(reason)
            }
            // Only our own queued markers may be removed; native jobs remain untouched.
            if (removeFrom != null) markers.forEach {
                // Native shutdownNow may already have drained our queued marker without running it.
                if (removeFrom.remove(it) || removeFrom.isShutdown) it.cancelUnstarted()
            }
        }

        private fun markerReturned() {
            if (remaining.decrementAndGet() == 0) {
                returned.complete(null)
                outcome.complete(failure.get())
            }
        }

        private inner class Marker : Runnable {
            private val claimed = AtomicBoolean()
            override fun run() {
                if (!claimed.compareAndSet(false, true)) return
                try {
                    if (failure.get() == null) barrier.await(timeoutMillis, TimeUnit.MILLISECONDS)
                } catch (markerFailure: Throwable) {
                    abort(markerFailure)
                    if (markerFailure is InterruptedException) Thread.currentThread().interrupt()
                } finally { markerReturned() }
            }
            fun cancelUnstarted() { if (claimed.compareAndSet(false, true)) markerReturned() }
        }
    }

    companion object {
        fun load(plugin: Any, nativeWork: BudgetedTaskQueue): JustTeamsBuffBridge? = runCatching {
            @Suppress("DEPRECATION")
            require((plugin as Plugin).description.version == "2.8.1") { "Supported justTeams version is 2.8.1" }
            bind(plugin, nativeWork) { Class.forName(it, false, plugin.javaClass.classLoader) }
        }.onFailure { failure ->
            Bukkit.getLogger().log(Level.WARNING,
                "ArcJustTeams buff editing disabled: unsupported justTeams 2.8.1 integration contract", failure)
        }.getOrNull()

        /** Resolver seam tests descriptors with neutral classes and an actual Java executor. */
        internal fun bind(plugin: Any, nativeWork: BudgetedTaskQueue, fenceTimeoutMillis: Long = 1_000,
            type: (String) -> Class<*>): JustTeamsBuffBridge = JustTeamsBuffBridge(plugin, nativeWork, fenceTimeoutMillis, type)

        private fun method(owner: Class<*>, name: String, result: Class<*>, vararg arguments: Class<*>): Method =
            owner.getMethod(name, *arguments).also { require(it.returnType == result) { "Unsupported ${owner.name}#$name return type" } }
    }
}
