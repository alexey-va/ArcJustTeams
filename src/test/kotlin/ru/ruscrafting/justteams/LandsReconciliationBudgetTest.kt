package ru.ruscrafting.justteams

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.applicationframework.util.ULID
import me.angeschossen.lands.api.land.Land
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LandsReconciliationBudgetTest {
    @Test
    fun `trust work shares one budget and rechecks membership after yielding`() {
        mockkStatic(LandsIntegration::class)
        mockkStatic(ULID::class)
        val plugin = mockk<JavaPlugin>()
        val integration = mockk<LandsIntegration>()
        val identifier = mockk<ULID>()
        val land = mockk<Land>()
        val owner = UUID.randomUUID()
        val members = (1..8).map { UUID.randomUUID() }.toSet()
        val accepted = mutableListOf<UUID>()
        var allowed = true
        every { LandsIntegration.of(plugin) } returns integration
        every { ULID.fromString("settlement") } returns identifier
        every { integration.getLandByULID(identifier) } returns land
        every { land.exists() } returns true
        every { land.ownerUID } returns owner
        every { land.isTrusted(any<UUID>()) } returns false
        every { land.trustPlayer(any<UUID>()) } answers { accepted += firstArg<UUID>(); true }
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val work = BudgetedTaskQueue(scope, maximumSteps = 2)
        try {
            val bridge = LandsBridge(plugin, work)
            val first = bridge.sync(setOf("settlement"), members) { _, _ -> allowed }
            val second = bridge.sync(setOf("settlement"), members) { _, _ -> allowed }
            assertEquals(0, accepted.size)
            scheduler.tick()
            assertEquals(2, accepted.size)
            assertFalse(first.isDone)
            assertFalse(second.isDone)
            allowed = false
            scheduler.tick(16)
            assertEquals(2, accepted.size)
            assertEquals(2, first.join().added + second.join().added)
            verify(exactly = 0) { integration.lands }
        } finally {
            work.close()
            scope.close()
            unmockkStatic(LandsIntegration::class)
            unmockkStatic(ULID::class)
        }
    }
}
