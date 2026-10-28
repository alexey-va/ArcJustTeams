package ru.ruscrafting.justteams

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicesManager
import ru.arc.dungeons.api.ArcPvpService
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

class ArcPvpNameplateBridgeTest {
    @Test
    fun `authoritative results use exact melee source and distinguish allowed from unavailable`() {
        val services = mockk<ServicesManager>()
        val plugin = plugin(services)
        val provider = mockk<ArcPvpService>()
        every { services.load(ArcPvpService::class.java) } returns provider
        val attacker = mockk<Player>()
        val victim = mockk<Player>()
        val check = arcPvpNameplateCheck(plugin)
        val cases = mapOf(
            ArcPvpService.Result.SETTLEMENT to TeamPvpState.LANDS,
            ArcPvpService.Result.TEAM to TeamPvpState.CLAN,
            ArcPvpService.Result.ALLY to TeamPvpState.ALLY,
            ArcPvpService.Result.TEAM_PROJECTILE to TeamPvpState.CLAN,
            ArcPvpService.Result.TEAM_TNT to TeamPvpState.CLAN,
            ArcPvpService.Result.ALLOWED to TeamPvpState.ALLOWED,
            ArcPvpService.Result.UNAVAILABLE to TeamPvpState.UNKNOWN,
        )
        cases.forEach { (result, expected) ->
            every { provider.check(attacker, victim, attacker) } returns result
            assertEquals(expected, check(attacker, victim))
        }
        verify(exactly = cases.size) { provider.check(attacker, victim, attacker) }
        verify(exactly = 0) { provider.notifyDenied(any(), any()) }
    }

    @Test
    fun `missing replaced and failed providers are resolved at the point of use`() {
        val services = mockk<ServicesManager>()
        val provider = mockk<ArcPvpService>()
        val attacker = mockk<Player>()
        val victim = mockk<Player>()
        val check = arcPvpNameplateCheck(plugin(services))

        every { services.load(ArcPvpService::class.java) } returns null
        assertEquals(TeamPvpState.UNKNOWN, check(attacker, victim))

        every { services.load(ArcPvpService::class.java) } returns provider
        every { provider.check(attacker, victim, attacker) } returns ArcPvpService.Result.SETTLEMENT
        assertEquals(TeamPvpState.LANDS, check(attacker, victim))

        every { provider.check(attacker, victim, attacker) } throws IllegalStateException("reload")
        assertEquals(TeamPvpState.UNKNOWN, check(attacker, victim))
        every { provider.check(attacker, victim, attacker) } throws NoSuchMethodError("old API")
        assertEquals(TeamPvpState.UNKNOWN, check(attacker, victim))

        every { services.load(ArcPvpService::class.java) } returns null
        assertEquals(TeamPvpState.UNKNOWN, check(attacker, victim))
    }

    private fun plugin(services: ServicesManager): Plugin = mockk {
        every { server.servicesManager } returns services
        every { logger } returns mockk<Logger>(relaxed = true)
    }
}
