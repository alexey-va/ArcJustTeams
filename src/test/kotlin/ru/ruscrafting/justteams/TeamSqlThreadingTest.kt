package ru.ruscrafting.justteams

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.applicationframework.util.ULID
import me.angeschossen.lands.api.land.Land
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TeamSqlThreadingTest {
    @Test
    fun `delayed SQL leaves ticks free and trust waits revalidates retries and closes safely`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("TeamSqlTest")
            val player = paper.addPlayer("Member")
            val departed = UUID.randomUUID()
            val retained = player.uniqueId
            val nativeTeam = SqlNativeTeam(linkedSetOf(departed, retained))
            val manager = SqlNativeTeamManager(nativeTeam)
            val nativePlugin = mockk<SqlNativePlugin> {
                every { getTeamManager() } returns manager
                every { getTeamUpgradeManager() } returns Any()
                every { getQuestManager() } returns null
                every { getTeamBuffManager() } returns null
            }
            val pluginManager = mockk<PluginManager> { every { getPlugin("justTeams") } returns nativePlugin }
            val integration = mockk<LandsIntegration>()
            val identifier = mockk<ULID>()
            val land = mockk<Land>()
            val trusted = mutableSetOf<UUID>()
            mockkStatic(Bukkit::class)
            mockkStatic(LandsIntegration::class)
            mockkStatic(ULID::class)
            every { Bukkit.getPluginManager() } returns pluginManager
            every { LandsIntegration.of(plugin) } returns integration
            every { ULID.fromString("settlement") } returns identifier
            every { integration.getLandByULID(identifier) } answers { assertTrue(Bukkit.isPrimaryThread()); land }
            every { land.exists() } answers { assertTrue(Bukkit.isPrimaryThread()); true }
            every { land.ownerUID } returns UUID.randomUUID()
            every { land.isTrusted(any<UUID>()) } answers { firstArg<UUID>() in trusted }
            every { land.trustPlayer(any<UUID>()) } answers {
                assertTrue(Bukkit.isPrimaryThread())
                trusted.add(firstArg())
            }
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val work = BudgetedTaskQueue(tasks, maximumSteps = 2)
            val worker = BoundedAsyncExecutor("team-sql-test", capacity = 1)
            val gates = mutableListOf<SqlReadGate>()
            fun gate() = SqlReadGate().also(gates::add)
            try {
                val gateway = JustTeamsGateway("", work, worker, mockk())
                val reconciler = TeamLandReconciler(gateway, LandsBridge(plugin, work), tasks, plugin.logger)
                val snapshot = sqlTeamSnapshot(nativeTeam, retained)
                var heartbeats = 0
                tasks.runTimer(1, 1) { heartbeats++ }
                val enumeration = gate().also { manager.delay.set(it) }
                val membership = gate().also { nativeTeam.delay.set(it) }
                reconciler.start()
                paper.performTicks(100)
                assertTrue(enumeration.entered.await(5, TimeUnit.SECONDS))
                val before = heartbeats
                paper.performTicks(5)
                assertEquals(before + 5, heartbeats)
                assertTrue(trusted.isEmpty())
                enumeration.release.countDown()
                paper.until { membership.entered.count == 0L }
                val result = reconciler.reconcile(snapshot)
                assertSame(result, reconciler.reconcile(snapshot))
                assertFalse(result.isDone)
                val authorization = gate().also { nativeTeam.delay.set(it) }
                membership.release.countDown()
                paper.until { authorization.entered.count == 0L }
                assertFalse(result.isDone)
                assertTrue(trusted.isEmpty())
                nativeTeam.memberIds.remove(departed)
                authorization.release.countDown()
                paper.until { result.isDone }
                assertEquals(LandSyncResult(1, 0), result.join())
                assertEquals(setOf(retained), trusted)

                // Both other enumeration callers also dispatch the blocking public API.
                val browse = gateway.browseTeamsAsync()
                paper.until { browse.isDone }
                assertEquals("Team", browse.join().single().name)
                val elsewhere = gateway.landLinkedElsewhere(99, "settlement")
                paper.until { elsewhere.isDone }
                assertTrue(elsewhere.join())

                // The join path must use the cache-only team lookup, not a full SQL-backed snapshot.
                val joined = gate().also { nativeTeam.delay.set(it) }
                reconciler.onJoin(PlayerJoinEvent(player, Component.empty()))
                paper.performTicks(40)
                paper.until { joined.entered.count == 0L }
                val onJoin = reconciler.reconcile(snapshot)
                joined.release.countDown()
                paper.until { onJoin.isDone }
                assertEquals(LandSyncResult(0, 0), onJoin.join())
                paper.performTicks(2)

                // Worker admission failure is visible and releases the team's retry guard.
                val occupied = gate()
                worker.submit { occupied.pause() }
                assertTrue(occupied.entered.await(5, TimeUnit.SECONDS))
                val queued = worker.submit { Unit }
                val rejected = reconciler.reconcile(snapshot)
                paper.until { rejected.isDone }
                assertTrue(rejected.isCompletedExceptionally)
                occupied.release.countDown()
                paper.until { queued.isDone }
                paper.performTicks(2)
                val retry = reconciler.reconcile(snapshot)
                paper.until { retry.isDone }
                assertEquals(LandSyncResult(0, 0), retry.join())
                paper.performTicks(2)

                val nextEnumeration = gate().also { manager.delay.set(it) }
                val nextMembership = gate().also { nativeTeam.delay.set(it) }
                paper.performTicks(1_200)
                assertTrue(nextEnumeration.entered.await(5, TimeUnit.SECONDS))
                nextEnumeration.release.countDown()
                paper.until { nextMembership.entered.count == 0L }
                val nextSweep = reconciler.reconcile(snapshot)
                nextMembership.release.countDown()
                paper.until { nextSweep.isDone }
                assertEquals(LandSyncResult(0, 0), nextSweep.join())
                paper.performTicks(2)

                // A local unlink invalidates even an already-read link waiting to return from I/O.
                nativeTeam.memberIds.add(departed)
                val unlinkRead = gate().also { nativeTeam.delay.set(it) }
                val unlinked = reconciler.reconcile(snapshot)
                paper.until { unlinkRead.entered.count == 0L }
                nativeTeam.dialogIo = true // Existing dialog write is deliberately outside this bounded fix.
                try { assertTrue(gateway.unbindLand(nativeTeam, "settlement")) }
                finally { nativeTeam.dialogIo = false }
                unlinkRead.release.countDown()
                paper.until { unlinked.isDone }
                assertEquals(LandSyncResult(0, 0), unlinked.join())
                assertEquals(setOf(retained), trusted)
                paper.performTicks(2)

                nativeTeam.custom[JustTeamsGateway.LAND_IDS] = "settlement"
                val closingRead = gate().also { nativeTeam.delay.set(it) }
                val closing = reconciler.reconcile(snapshot)
                paper.until { closingRead.entered.count == 0L }
                work.close()
                tasks.close()
                closingRead.release.countDown()
                paper.until { closing.isDone }
                assertTrue(closing.isCompletedExceptionally)
                assertEquals(setOf(retained), trusted)
            } finally {
                gates.forEach { it.release.countDown() }
                work.close()
                tasks.close()
                worker.closeAsync().get(5, TimeUnit.SECONDS)
                unmockkStatic(Bukkit::class)
                unmockkStatic(LandsIntegration::class)
                unmockkStatic(ULID::class)
            }
        }
    }
}

private fun MockBukkitTestRuntime.until(done: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!done() && System.nanoTime() < deadline) {
        performTicks(1)
        Thread.sleep(1)
    }
    assertTrue(done(), "Async stage did not reach terminal completion")
}

class SqlReadGate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    fun pause() {
        assertFalse(Bukkit.isPrimaryThread(), "SQL ran on Paper's owning thread")
        entered.countDown()
        assertTrue(release.await(5, TimeUnit.SECONDS), "Test did not release SQL")
    }
}

open class SqlNativePlugin : JavaPlugin() {
    open fun getTeamManager(): Any = error("Test stub")
    open fun getTeamUpgradeManager(): Any = error("Test stub")
    open fun getQuestManager(): Any? = null
    open fun getTeamBuffManager(): Any? = null
}

class SqlNativeTeamManager(private val team: SqlNativeTeam) {
    val delay = AtomicReference<SqlReadGate?>()
    fun getAllTeams(): List<SqlNativeTeam> {
        assertFalse(Bukkit.isPrimaryThread())
        delay.getAndSet(null)?.pause()
        return listOf(team)
    }
    fun getPlayerTeamCached(id: UUID): SqlNativeTeam? {
        assertTrue(Bukkit.isPrimaryThread())
        return team.takeIf { id in it.memberIds }
    }
}

class SqlNativeTeam(val memberIds: MutableSet<UUID>) {
    val delay = AtomicReference<SqlReadGate?>()
    val custom = ConcurrentHashMap(mapOf(JustTeamsGateway.LAND_IDS to "settlement"))
    @Volatile var dialogIo = false
    fun getId() = 7
    fun getPlainName(): String { assertTrue(Bukkit.isPrimaryThread()); return "Team" }
    fun getPlainTag(): String { assertTrue(Bukkit.isPrimaryThread()); return "T" }
    fun isPublic(): Boolean { assertTrue(Bukkit.isPrimaryThread()); return true }
    fun getMembers(): List<SqlNativeMember> {
        assertTrue(Bukkit.isPrimaryThread())
        return memberIds.map(::SqlNativeMember)
    }
    fun getCustomData(key: String): Optional<String> {
        if (!dialogIo) assertFalse(Bukkit.isPrimaryThread())
        val value = custom[key]
        if (!dialogIo) delay.getAndSet(null)?.pause()
        return Optional.ofNullable(value)
    }
    fun setCustomData(key: String, value: String): Boolean { custom[key] = value; return true }
}

class SqlNativeMember(private val id: UUID) {
    fun getPlayerUuid(): UUID { assertTrue(Bukkit.isPrimaryThread()); return id }
}

private fun sqlTeamSnapshot(raw: Any, owner: UUID) = TeamSnapshot(
    raw, 7, "Team", "T", "", owner, 1, 0L, 0.0, 2, 2,
    setOf(owner), setOf("settlement"), 0L, false, false, false, true, emptyList(), emptyList(), emptyList(), emptySet(),
)
