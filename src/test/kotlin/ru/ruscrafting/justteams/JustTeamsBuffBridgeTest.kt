package ru.ruscrafting.justteams

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JustTeamsBuffBridgeTest {
    @Test
    fun `native headless action binds once charges each activation and rejects repeated desired state`() {
        withBuffFixture { fixture ->
            val bound = fixture.resolutions.toList()
            assertEquals(BuffAttempt.ENABLED, fixture.attempt("haste", true, 100))
            assertEquals(BuffAttempt.CHANGED, fixture.attempt("haste", true, 100))
            assertEquals(1, fixture.probe.toggleCalls.get())
            assertEquals(100L, fixture.probe.charged.get())
            fixture.setPoints(0)
            assertEquals(BuffAttempt.DISABLED, fixture.attempt("haste", false, 100))
            assertEquals(100L, fixture.probe.charged.get())
            fixture.setPoints(500)
            assertEquals(BuffAttempt.ENABLED, fixture.attempt("haste", true, 100))
            assertEquals(200L, fixture.probe.charged.get())
            assertEquals(3, fixture.probe.toggleCalls.get())
            assertEquals(3, fixture.probe.refreshCalls.get())
            assertEquals(0, fixture.probe.openCalls.get())
            assertSame(fixture.player, fixture.probe.lastActor)
            assertEquals(bound, fixture.resolutions)
        }
    }

    @Test
    fun `fresh identity membership rights quote availability limit and points guard native invocation`() {
        withBuffFixture { fixture ->
            assertEquals(BuffAttempt.CHANGED, fixture.attempt("haste", true, 100, teamId = 8))
            fixture.team.member = false
            assertEquals(BuffAttempt.NOT_ALLOWED, fixture.attempt("haste", true, 100))
            fixture.team.member = true
            fixture.team.elevated = false
            assertEquals(BuffAttempt.NOT_ALLOWED, fixture.attempt("haste", true, 100))
            fixture.team.elevated = true
            assertEquals(BuffAttempt.CHANGED, fixture.attempt("haste", true, 99))
            assertEquals(BuffAttempt.CHANGED, fixture.attempt("unknown", true, 100))
            assertEquals(BuffAttempt.CHANGED, fixture.attempt("haste", true, -1))
            fixture.buffs.buffsEnabled = false
            assertEquals(BuffAttempt.UNAVAILABLE, fixture.attempt("haste", true, 100))
            fixture.buffs.buffsEnabled = true
            fixture.buffs.maximum = 0
            assertEquals(BuffAttempt.MAXIMUM, fixture.attempt("haste", true, 100))
            fixture.buffs.maximum = 2
            fixture.setPoints(99)
            assertEquals(BuffAttempt.NOT_ENOUGH_POINTS, fixture.attempt("haste", true, 100))
            fixture.setPoints(500)
            fixture.manager.uncacheTeamName()
            assertEquals(BuffAttempt.UNAVAILABLE, fixture.attempt("haste", true, 100))
            assertEquals(0, fixture.probe.toggleCalls.get())
            assertTrue(fixture.team.active.isEmpty())
        }
    }

    @Test
    fun `global gate prevents two coowners spending the same last balance and covers other clans free buffs`() {
        withBuffFixture { fixture ->
            fixture.setPoints(150)
            val coownerId = UUID.randomUUID()
            val coowner = mockk<Player> {
                every { uniqueId } returns coownerId
                every { isOnline } returns true
            }
            fixture.manager.link(coowner.uniqueId, fixture.team)
            val otherId = UUID.randomUUID()
            val other = mockk<Player> {
                every { uniqueId } returns otherId
                every { isOnline } returns true
            }
            fixture.manager.link(other.uniqueId, BuffNativeTeam(other.uniqueId, 8))
            val charge = fixture.gate().also { fixture.probe.nextCharge.set(it) }
            val first = fixture.bridge.setBuff(fixture.player, 7, "haste", true, 100)
            fixture.paper.untilBuff { charge.entered.count == 0L }
            assertEquals(BuffAttempt.PENDING, fixture.bridge.setBuff(coowner, 7, "resistance", true, 150).join())
            assertEquals(BuffAttempt.PENDING, fixture.bridge.setBuff(other, 8, "speed", true, 0).join())
            assertEquals(1, fixture.probe.toggleCalls.get())
            fixture.paper.performTicks(5) // Gameplay ticks continue while both native effects are pending.
            assertFalse(first.isDone)
            charge.release.countDown()
            assertEquals(BuffAttempt.ENABLED, fixture.await(first))
            assertEquals(50L, fixture.team.points)
            assertEquals(BuffAttempt.NOT_ENOUGH_POINTS,
                fixture.await(fixture.bridge.setBuff(coowner, 7, "resistance", true, 150)))
            assertEquals(setOf("haste"), fixture.team.active)
            assertEquals(1, fixture.probe.toggleCalls.get())
        }
    }

    @Test
    fun `disable cannot overtake the previous native selected set save`() {
        withBuffFixture { fixture ->
            val save = fixture.gate().also { fixture.probe.nextSave.set(it) }
            val enabled = fixture.bridge.setBuff(fixture.player, 7, "haste", true, 100)
            fixture.paper.untilBuff { save.entered.count == 0L }
            assertEquals(BuffAttempt.PENDING, fixture.bridge.setBuff(fixture.player, 7, "haste", false, 100).join())
            assertEquals(1, fixture.probe.toggleCalls.get())
            assertTrue(fixture.probe.persisted.get().isEmpty())
            save.release.countDown()
            assertEquals(BuffAttempt.ENABLED, fixture.await(enabled))
            assertEquals(setOf("haste"), fixture.probe.persisted.get())
            assertEquals(BuffAttempt.DISABLED, fixture.attempt("haste", false, 100))
            assertTrue(fixture.probe.persisted.get().isEmpty())
            assertEquals(2, fixture.probe.toggleCalls.get())
        }
    }

    @Test
    fun `overall deadline covers all busy workers and fence retry never replays native toggle`() {
        withBuffFixture(fenceTimeoutMillis = 200) { fixture ->
            val charge = fixture.gate().also { fixture.probe.nextCharge.set(it) }
            val save = fixture.gate().also { fixture.probe.nextSave.set(it) }
            val enabled = fixture.bridge.setBuff(fixture.player, 7, "haste", true, 100)
            fixture.paper.untilBuff { charge.entered.count == 0L && save.entered.count == 0L }
            assertEquals(BuffAttempt.PENDING, fixture.await(enabled))
            assertEquals(BuffAttempt.PENDING, fixture.bridge.setBuff(fixture.player, 7, "haste", false, 100).join())
            assertEquals(1, fixture.probe.toggleCalls.get())
            charge.release.countDown()
            save.release.countDown()
            fixture.paper.untilBuff { fixture.executor.activeCount == 0 && fixture.executor.queue.isEmpty() }
            fixture.paper.performTicks(3)
            // The next interaction only re-fences the unresolved operation, even after its tasks returned.
            assertEquals(BuffAttempt.PENDING, fixture.bridge.setBuff(fixture.player, 7, "haste", false, 100).join())
            fixture.awaitStartup()
            assertEquals(1, fixture.probe.toggleCalls.get())
            assertEquals(setOf("haste"), fixture.team.active)
            assertEquals(BuffAttempt.DISABLED, fixture.attempt("haste", false, 100))
            assertEquals(2, fixture.probe.toggleCalls.get())
        }
    }

    @Test
    fun `startup fences prior native work and close releases only owned markers without shutting borrowed pool`() {
        withBuffFixture(bind = false, fenceTimeoutMillis = 1_000) { fixture ->
            val prior = fixture.gate()
            fixture.runner.runAsync { prior.pause() }
            fixture.paper.untilBuff { prior.entered.count == 0L }
            fixture.bind()
            assertEquals(BuffAttempt.PENDING, fixture.bridge.setBuff(fixture.player, 7, "haste", true, 100).join())
            assertEquals(0, fixture.probe.toggleCalls.get())
            fixture.bridge.close()
            fixture.paper.untilBuff { fixture.bridge.cleanup.isDone }
            assertFalse(fixture.executor.isShutdown)
            assertEquals(1L, prior.release.count, "Closing addon must not release or cancel a native job")
            assertEquals(BuffAttempt.UNAVAILABLE, fixture.bridge.setBuff(fixture.player, 7, "haste", true, 100).join())
            prior.release.countDown()
            val healthy = CompletableFuture<Unit>()
            fixture.executor.execute { healthy.complete(Unit) }
            fixture.paper.untilBuff { healthy.isDone }
        }
    }

    @Test
    fun `native shutdownNow drained markers cannot keep addon cleanup pending`() {
        withBuffFixture(bind = false) { fixture ->
            val first = fixture.gate()
            val second = fixture.gate()
            fixture.runner.runAsync { first.pause() }
            fixture.runner.runAsync { second.pause() }
            fixture.paper.untilBuff { first.entered.count == 0L && second.entered.count == 0L }
            fixture.bind()
            assertEquals(BuffAttempt.PENDING, fixture.bridge.setBuff(fixture.player, 7, "haste", true, 100).join())
            val nativeDrained = fixture.executor.shutdownNow()
            assertEquals(2, nativeDrained.size, "Native owner drained both queued addon markers")
            fixture.bridge.close()
            fixture.paper.untilBuff { fixture.bridge.cleanup.isDone }
            assertTrue(fixture.executor.isShutdown)
            assertEquals(0, fixture.probe.toggleCalls.get())
        }
    }

    @Test
    fun `rejected fence retains unresolved operation without replaying native action`() {
        withBuffFixture { fixture ->
            fixture.probe.shutdownAfterToggle = true
            assertEquals(BuffAttempt.PENDING, fixture.attempt("haste", true, 100))
            assertEquals(1, fixture.probe.toggleCalls.get())
            assertEquals(BuffAttempt.PENDING, fixture.attempt("haste", false, 100))
            fixture.paper.performTicks(3)
            assertEquals(1, fixture.probe.toggleCalls.get())
            assertEquals(setOf("haste"), fixture.team.active)
        }
    }

    @Test
    fun `readback stays ambiguous unsupported version and descriptors fail closed and gateway revalidates on Main`() {
        withBuffFixture { fixture ->
            fixture.probe.mutate = false
            assertEquals(BuffAttempt.FAILED, fixture.attempt("haste", true, 100))
            every { fixture.plugin.description } returns PluginDescriptionFile("justTeams", "2.8.2", "Native")
            assertNull(JustTeamsBuffBridge.load(fixture.plugin, fixture.queue))
            assertFailsWith<NoSuchMethodException> {
                JustTeamsBuffBridge.bind(fixture.plugin, fixture.queue) { name ->
                    if (name == "eu.kotori.justapi.gui.ClickContext") Any::class.java else fixture.type(name)
                }
            }
            fixture.executor.maximumPoolSize = 3
            assertFailsWith<IllegalArgumentException> {
                JustTeamsBuffBridge.bind(fixture.plugin, fixture.queue, type = fixture::type)
            }
            fixture.executor.maximumPoolSize = 2
            val gateway = JustTeamsGateway(fixture.queue, mockk(), mockk()) { _, _ -> fixture.bridge }
            assertTrue(gateway.buffEditingAvailable())
            val revoked = gateway.setBuff(fixture.player, 7, "haste", true, 100)
            assertFalse(revoked.isDone)
            fixture.team.elevated = false
            assertEquals(BuffAttempt.NOT_ALLOWED, fixture.await(revoked))
            fixture.team.elevated = true
            fixture.probe.fail = true
            assertEquals(BuffAttempt.FAILED, fixture.await(gateway.setBuff(fixture.player, 7, "haste", true, 100)))
            assertFalse(gateway.buffEditingAvailable())
            assertEquals(BuffAttempt.UNAVAILABLE, fixture.await(gateway.setBuff(fixture.player, 7, "haste", true, 100)))
            gateway.close()
            assertSame(fixture.bridge.cleanup, gateway.cleanup)
        }
    }
}

private fun MockBukkitTestRuntime.untilBuff(done: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!done() && System.nanoTime() < deadline) {
        performTicks(1)
        Thread.sleep(1)
    }
    assertTrue(done(), "Native buff stage did not complete within five seconds")
}

private fun withBuffFixture(fenceTimeoutMillis: Long = 1_000, bind: Boolean = true, test: (BuffFixture) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        val fixture = BuffFixture(paper, fenceTimeoutMillis)
        val pluginManager = mockk<PluginManager> { every { getPlugin("justTeams") } returns fixture.plugin }
        mockkStatic(Bukkit::class)
        every { Bukkit.getPluginManager() } returns pluginManager
        try {
            if (bind) { fixture.bind(); fixture.awaitStartup() }
            test(fixture)
        } finally {
            try { fixture.close() } finally { unmockkStatic(Bukkit::class) }
        }
    }
}

private class BuffFixture(val paper: MockBukkitTestRuntime, private val fenceTimeoutMillis: Long) : AutoCloseable {
    private val owner = paper.createSimplePlugin("BuffQueueTest")
    val player = paper.addPlayer("BuffOwner")
    val team = BuffNativeTeam(player.uniqueId)
    val manager = BuffNativeTeamManager(team)
    val buffs = BuffNativeBuffManager()
    val probe = BuffNativeProbe()
    val executor = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        { runnable -> Thread(runnable, "native-buff-test").also { it.isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    val runner = BuffNativeTaskRunner(executor)
    val plugin = mockk<BuffNativePlugin> {
        every { getTeamManager() } returns manager
        every { getTeamUpgradeManager() } returns Any()
        every { getTeamBuffManager() } returns buffs
        every { getTaskRunner() } returns runner
        every { getProbe() } returns probe
        every { description } returns PluginDescriptionFile("justTeams", "2.8.1", "Native")
    }
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(owner))
    val queue = BudgetedTaskQueue(tasks)
    private val gates = mutableListOf<BuffNativeGate>()
    val resolutions = mutableListOf<String>()
    lateinit var bridge: JustTeamsBuffBridge
    fun bind() {
        bridge = JustTeamsBuffBridge.bind(plugin, queue, fenceTimeoutMillis) { name -> resolutions += name; type(name) }
    }
    fun gate(): BuffNativeGate = BuffNativeGate().also(gates::add)
    fun setPoints(value: Long) { team.points = value; team.storedPoints.set(value) }
    fun awaitStartup() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val result = await(bridge.setBuff(player, -1, "haste", true, 100))
            if (result == BuffAttempt.CHANGED) return
            assertEquals(BuffAttempt.PENDING, result)
            paper.performTicks(1)
            Thread.sleep(1)
        }
        error("Startup/recovery fence did not settle")
    }
    fun await(result: CompletableFuture<BuffAttempt>): BuffAttempt {
        paper.untilBuff { result.isDone }
        return result.join()
    }
    fun attempt(id: String, enabled: Boolean, cost: Long, teamId: Int = 7): BuffAttempt =
        await(bridge.setBuff(player, teamId, id, enabled, cost))
    fun type(name: String): Class<*> = when (name) {
        "eu.kotori.justTeams.JustTeams" -> BuffNativePlugin::class.java
        "eu.kotori.justTeams.team.Team" -> BuffNativeTeam::class.java
        "eu.kotori.justTeams.managers.TeamBuffManager\$BuffDef" -> BuffNativeDef::class.java
        "eu.kotori.justTeams.gui.sub.TeamBuffMenu" -> BuffNativeMenu::class.java
        "eu.kotori.justapi.gui.Menu" -> BuffNativeBaseMenu::class.java
        "eu.kotori.justapi.gui.ClickContext" -> BuffNativeClick::class.java
        else -> error("Unexpected native binding: $name")
    }
    override fun close() {
        val wasShutdown = executor.isShutdown
        if (::bridge.isInitialized) {
            bridge.close()
            // Closing the gameplay queue first must not stop marker-return cleanup.
            queue.close()
            paper.untilBuff { bridge.cleanup.isDone }
            assertEquals(wasShutdown, executor.isShutdown, "Addon must not shut down its borrowed executor")
        }
        gates.forEach { it.release.countDown() }
        queue.close()
        tasks.close()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }
}

// Neutral-package doubles exercise the reflection/queue boundary; no third-party classes are patched.
open class BuffNativePlugin : JavaPlugin() {
    open fun getTeamManager(): BuffNativeTeamManager = error("Test stub")
    open fun getTeamUpgradeManager(): Any = error("Test stub")
    open fun getTeamBuffManager(): BuffNativeBuffManager = error("Test stub")
    open fun getTaskRunner(): BuffNativeTaskRunner = error("Test stub")
    open fun getProbe(): BuffNativeProbe = error("Test stub")
}

class BuffNativeTaskRunner(private val asyncExecutor: ExecutorService) {
    fun runAsync(job: Runnable) { asyncExecutor.execute { try { job.run() } catch (_: Throwable) { } } }
}

class BuffNativeTeamManager(team: BuffNativeTeam) {
    private val cacheLock: Any = Any()
    private val teamNameCache: MutableMap<String, BuffNativeTeam> = mutableMapOf("clan" to team)
    private val playerTeams = mutableMapOf(team.playerId to team)
    fun getPlayerTeamCached(id: UUID): BuffNativeTeam? = playerTeams[id]
    fun link(id: UUID, team: BuffNativeTeam) = synchronized(cacheLock) {
        playerTeams[id] = team
        teamNameCache[team.getId().toString()] = team
        team.members += id
    }
    fun uncacheTeamName() = synchronized(cacheLock) { teamNameCache.clear() }
    fun assertNativeLookupCannotReachStorage(team: BuffNativeTeam) {
        assertTrue(Thread.holdsLock(cacheLock))
        assertTrue(teamNameCache.values.any { it === team })
    }
}

class BuffNativeTeam(val playerId: UUID, private val teamId: Int = 7) {
    var member = true
    var elevated = true
    @Volatile var points = 500L
    val storedPoints = AtomicLong(points)
    val members = linkedSetOf(playerId)
    val active = linkedSetOf<String>()
    fun getId(): Int = teamId
    fun isMember(id: UUID): Boolean = member && id in members
    fun hasElevatedPermissions(id: UUID): Boolean = elevated && id in members
    fun getActiveBuffs(): Set<String> = active
}

class BuffNativeBuffManager {
    var buffsEnabled = true
    var maximum = 2
    private val definitions = mapOf("haste" to BuffNativeDef("haste", 100),
        "resistance" to BuffNativeDef("resistance", 150), "speed" to BuffNativeDef("speed", 0))
    fun isEnabled(): Boolean = buffsEnabled
    fun getPool(): Map<String, BuffNativeDef> = definitions
    fun getMaxActive(): Int = maximum
}

class BuffNativeDef(@JvmField val key: String, @JvmField val pointsCost: Long)
class BuffNativeGate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    fun pause() {
        assertFalse(Bukkit.isPrimaryThread())
        entered.countDown()
        assertTrue(release.await(5, TimeUnit.SECONDS))
    }
}

class BuffNativeProbe {
    val toggleCalls = AtomicInteger()
    val refreshCalls = AtomicInteger()
    val openCalls = AtomicInteger()
    val charged = AtomicLong()
    val nextCharge = AtomicReference<BuffNativeGate?>()
    val nextSave = AtomicReference<BuffNativeGate?>()
    val persisted = AtomicReference<Set<String>>(emptySet())
    var mutate = true
    var fail = false
    var shutdownAfterToggle = false
    var lastActor: Player? = null
}

abstract class BuffNativeBaseMenu(private val viewer: Player?, private val probe: BuffNativeProbe) {
    fun refresh() {
        probe.refreshCalls.incrementAndGet()
        if (viewer == null) return
        probe.openCalls.incrementAndGet()
        error("Headless action must not open or schedule an inventory")
    }
}

class BuffNativeMenu(private val plugin: BuffNativePlugin, viewer: Player?, private val team: BuffNativeTeam) :
    BuffNativeBaseMenu(viewer, plugin.getProbe()) {
    init { assertNull(viewer) }
    private fun toggle(context: BuffNativeClick, buff: BuffNativeDef) {
        assertTrue(Bukkit.isPrimaryThread())
        plugin.getTeamManager().assertNativeLookupCannotReachStorage(team)
        val probe = plugin.getProbe()
        probe.toggleCalls.incrementAndGet()
        probe.lastActor = context.player()
        if (probe.fail) error("Native action failed with an unknown outcome")
        if (probe.mutate) {
            if (!team.active.remove(buff.key)) {
                team.active += buff.key
                if (buff.pointsCost > 0) {
                    val gate = probe.nextCharge.getAndSet(null)
                    plugin.getTaskRunner().runAsync {
                        gate?.pause()
                        val before = team.storedPoints.get()
                        val after = maxOf(0, before - buff.pointsCost)
                        team.storedPoints.set(after)
                        team.points = after
                        probe.charged.addAndGet(before - after)
                    }
                }
            }
            val snapshot = team.active.toSet()
            val save = probe.nextSave.getAndSet(null)
            plugin.getTaskRunner().runAsync { save?.pause(); probe.persisted.set(snapshot) }
        }
        context.refresh()
        if (probe.shutdownAfterToggle) {
            val field = BuffNativeTaskRunner::class.java.getDeclaredField("asyncExecutor").also { it.isAccessible = true }
            (field.get(plugin.getTaskRunner()) as ExecutorService).shutdown()
        }
    }
}

class BuffNativeClick(private val actor: Player, slot: Int, click: ClickType,
    private val menu: BuffNativeBaseMenu, item: ItemStack?) {
    init { assertEquals(-1, slot); assertEquals(ClickType.LEFT, click); assertNull(item) }
    fun player(): Player = actor
    fun refresh() = menu.refresh()
}
