package ru.ruscrafting.justteams

import io.mockk.every
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.GameRules
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.net.URLClassLoader
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TeamPvpPolicyTest {
    @Test
    fun `same target uses authoritative viewer specific protection without native Lands inference`() {
        val world = world(true)
        val target = player(world)
        val viewers = listOf(player(world), player(world), player(world), player(world))
        val results = listOf(TeamPvpState.CLAN, TeamPvpState.ALLY, TeamPvpState.LANDS, TeamPvpState.UNKNOWN)
        val states = viewers.zip(results).toMap()
        val policy = TeamPvpPolicy { viewer, victim ->
            assertEquals(target, victim)
            states.getValue(viewer)
        }

        viewers.zip(results).forEach { (viewer, expected) -> assertEquals(expected, policy.evaluate(viewer, target)) }
    }

    @Test
    fun `world and native denials do not query optional protection providers`() {
        val world = world(false)
        val viewer = player(world)
        val target = player(world)
        val policy = TeamPvpPolicy { _, _ -> error("A native denial must short-circuit the query") }
        assertEquals(TeamPvpState.WORLD, policy.evaluate(viewer, target))

        every { world.getGameRuleValue(GameRules.PVP) } returns true
        every { target.isInvulnerable } returns true
        assertEquals(TeamPvpState.PROTECTED, policy.evaluate(viewer, target))

        every { target.isInvulnerable } returns false
        every { target.gameMode } returns GameMode.CREATIVE
        assertEquals(TeamPvpState.PROTECTED, policy.evaluate(viewer, target))

        every { target.gameMode } returns GameMode.SURVIVAL
        every { viewer.gameMode } returns GameMode.SPECTATOR
        assertEquals(TeamPvpState.PROTECTED, policy.evaluate(viewer, target))

        every { viewer.gameMode } returns GameMode.SURVIVAL
        every { target.world } returns this.world(true)
        assertEquals(TeamPvpState.WORLD, policy.evaluate(viewer, target))
    }

    @Test
    fun `API absent factory and method discovery stay binary safe without loading typed adapter`() {
        val absentApi = object : URLClassLoader(
            arrayOf(TeamPvpPolicy::class.java.protectionDomain.codeSource.location),
            TeamPvpPolicy::class.java.classLoader,
        ) {
            var adapterLoaded = false

            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("ru.arc.dungeons.api.")) throw ClassNotFoundException(name)
                if (!name.startsWith("ru.ruscrafting.justteams.")) return super.loadClass(name, resolve)
                if (name == "ru.ruscrafting.justteams.ArcPvpNameplateBridge") adapterLoaded = true
                return synchronized(getClassLoadingLock(name)) {
                    (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
                }
            }
        }
        absentApi.use { loader ->
            val factory = loader.loadClass("ru.ruscrafting.justteams.TeamPvpPolicyKt")
            factory.declaredMethods.forEach { it.parameterTypes; it.returnType }
            loader.loadClass("ru.ruscrafting.justteams.TeamPvpPolicy").declaredMethods.forEach { it.parameterTypes; it.returnType }
            loader.loadClass("ru.ruscrafting.justteams.TeamNameplateRuntime").declaredMethods.forEach { it.parameterTypes; it.returnType }
            @Suppress("UNCHECKED_CAST")
            val check = factory.getDeclaredMethod("arcPvpNameplateCheck", Plugin::class.java)
                .invoke(null, mockk<Plugin>()) as (Player, Player) -> Any
            assertEquals("UNKNOWN", check(mockk(), mockk()).toString())
            assertFalse(loader.adapterLoaded)
        }
    }

    private fun world(pvp: Boolean): World = mockk {
        every { uid } returns UUID.randomUUID()
        every { getGameRuleValue(GameRules.PVP) } returns pvp
    }

    private fun player(world: World): Player = mockk {
        every { uniqueId } returns UUID.randomUUID()
        every { this@mockk.world } returns world
        every { isOnline } returns true
        every { isDead } returns false
        every { gameMode } returns GameMode.SURVIVAL
        every { isInvulnerable } returns false
    }
}
