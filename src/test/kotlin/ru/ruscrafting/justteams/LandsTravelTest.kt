package ru.ruscrafting.justteams

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.applicationframework.util.ULID
import me.angeschossen.lands.api.land.Land
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LandsTravelTest {
    @Test
    fun `settlement travel uses player native spawn flow and refuses a missing spawn`() {
        mockkStatic(LandsIntegration::class)
        mockkStatic(ULID::class)
        try {
            val server = mockk<Server>()
            val plugin = mockk<JavaPlugin> { every { getServer() } returns server }
            val integration = mockk<LandsIntegration>()
            val land = mockk<Land>()
            val id = mockk<ULID>()
            val player = mockk<Player>()
            every { server.isPrimaryThread } returns true
            every { LandsIntegration.of(plugin) } returns integration
            every { ULID.fromString("settlement") } returns id
            every { integration.getLandByULID(id) } returns land
            every { land.exists() } returns true
            every { land.name } returns "Кедровый"
            every { land.spawnPosition } returns mockk()
            every { player.isOnline } returns true
            every { server.dispatchCommand(player, "lands:lands spawn Кедровый") } returns true
            val bridge = LandsBridge(plugin, mockk())

            assertTrue(bridge.travel(player, "settlement"))
            every { land.spawnPosition } returns null
            assertFalse(bridge.travel(player, "settlement"))
            every { land.spawnPosition } returns mockk()
            every { player.isOnline } returns false
            assertFalse(bridge.travel(player, "settlement"))
            verify(exactly = 1) { server.dispatchCommand(player, any()) }
        } finally {
            unmockkStatic(LandsIntegration::class)
            unmockkStatic(ULID::class)
        }
    }
}
