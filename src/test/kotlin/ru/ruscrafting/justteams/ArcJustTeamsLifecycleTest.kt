package ru.ruscrafting.justteams

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.bukkit.Bukkit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ArcJustTeamsLifecycleTest {
    @Test
    fun `reenable preserves the exact delayed worker through duplicate and superseded disables`() {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("justTeams")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val preparations = AtomicInteger()
            mockkStatic(ConfigManager::class)
            every { ConfigManager.of(any(), any()) } answers {
                assertFalse(Bukkit.isPrimaryThread())
                if (preparations.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                throw IllegalStateException("isolated delayed team locale preparation")
            }
            var plugin: ArcJustTeamsPlugin? = null
            try {
                plugin = paper.loadPlugin<ArcJustTeamsPlugin>()
                awaitTeamsLifecycle(paper) { entered.count == 0L }
                paper.server.pluginManager.disablePlugin(plugin)
                assertFalse(plugin.drained().isDone)
                plugin.onDisable()
                assertFalse(plugin.drained().isDone)
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                assertEquals(1, preparations.get())
                paper.server.pluginManager.disablePlugin(plugin)
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                assertEquals(1, preparations.get())
                release.countDown()
                awaitTeamsLifecycle(paper) { preparations.get() == 2 }
                awaitTeamsLifecycle(paper) { !plugin.isEnabled }
                assertEquals(2, preparations.get())
            } finally {
                release.countDown()
                plugin?.let { active ->
                    paper.server.pluginManager.disablePlugin(active)
                    active.drained().get(5, TimeUnit.SECONDS)
                }
                unmockkStatic(ConfigManager::class)
                ConfigManager.clear()
            }
        }
    }
}

private fun awaitTeamsLifecycle(paper: MockBukkitTestRuntime, ready: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!ready() && System.nanoTime() < deadline) { paper.performTicks(1); Thread.sleep(1) }
    assertTrue(ready())
}
