package ru.ruscrafting.justteams

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import me.clip.placeholderapi.PlaceholderAPIPlugin
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.Optional
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamNameplateExpansionTest {
    private lateinit var paper: MockBukkitTestRuntime

    @BeforeTest
    fun openPaper() {
        paper = MockBukkitTestRuntime.open()
    }

    @AfterTest
    fun closePaper() {
        if (::paper.isInitialized) paper.close()
    }

    @Test
    fun `readiness uses only published UUIDs and falls back after close or for unknown targets`() {
        val plugin = mockk<Plugin> { every { pluginMeta.version } returns "test" }
        val targetId = UUID.randomUUID()
        val target = mockk<OfflinePlayer> { every { uniqueId } returns targetId }
        val offline = mockk<OfflinePlayer> { every { uniqueId } returns UUID.randomUUID() }
        val eligible = mutableSetOf<UUID>()
        val expansion = TeamNameplateExpansion(plugin, eligible::contains)
        val manager = mockk<LocalExpansionManager>()
        val papi = mockk<PlaceholderAPIPlugin> { every { localExpansionManager } returns manager }
        mockkStatic(PlaceholderAPIPlugin::class)
        try {
            every { PlaceholderAPIPlugin.getInstance() } returns papi
            every { manager.register(expansion) } returns true
            every { manager.findExpansionByIdentifier("arcjustteams") } returns Optional.of(expansion)
            every { manager.unregister(expansion) } answers {
                assertEquals("false", expansion.onRequest(target, "nameplate_ready"))
                true
            }
            assertTrue(expansion.register())
            assertTrue(expansion.persist())
            assertEquals("arcjustteams", expansion.identifier)
            assertEquals("test", expansion.version)
            assertEquals("false", expansion.onRequest(null, "nameplate_ready"))
            assertEquals("false", expansion.onRequest(target, "nameplate_ready"))
            eligible += targetId
            assertEquals("true", expansion.onRequest(target, "nameplate_ready"))
            assertEquals("false", expansion.onRequest(offline, "nameplate_ready"))
            assertEquals("false", expansion.onRequest(target, "unknown"))
            eligible.clear()
            assertEquals("false", expansion.onRequest(target, "nameplate_ready"))
            eligible += targetId
            expansion.close()
            expansion.close()
            assertEquals("false", expansion.onRequest(target, "nameplate_ready"))
            verify(exactly = 1) { manager.unregister(expansion) }
            verify(exactly = 0) { target.isOnline; target.player }
        } finally {
            unmockkStatic(PlaceholderAPIPlugin::class)
        }
    }

    @Test
    fun `closing a retired expansion never unregisters its replacement`() {
        val plugin = mockk<Plugin> { every { pluginMeta.version } returns "test" }
        val old = TeamNameplateExpansion(plugin) { true }
        val replacement = TeamNameplateExpansion(plugin) { true }
        val manager = mockk<LocalExpansionManager>()
        val papi = mockk<PlaceholderAPIPlugin> { every { localExpansionManager } returns manager }
        mockkStatic(PlaceholderAPIPlugin::class)
        try {
            every { PlaceholderAPIPlugin.getInstance() } returns papi
            every { manager.findExpansionByIdentifier("arcjustteams") } returns Optional.of<PlaceholderExpansion>(replacement)
            old.close()
            verify(exactly = 0) { manager.unregister(any()) }
        } finally {
            unmockkStatic(PlaceholderAPIPlugin::class)
        }
    }
}
