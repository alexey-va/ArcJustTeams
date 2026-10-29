package ru.ruscrafting.justteams

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import me.clip.placeholderapi.PlaceholderAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import kotlin.test.Test
import kotlin.test.assertEquals

class TeamNameplateIdentityTest {
    @Test
    fun `rank keeps trusted legacy colors while nickname stays literal and white`() {
        val target = mockk<Player> { every { name } returns "<red>Hero&b" }
        val prefixes = listOf(
            "&f\uE5A7&r" to NamedTextColor.WHITE,
            "§6\uE5A7§r" to NamedTextColor.GOLD,
            "&#ab12cd\uE5A7&r" to TextColor.color(0xab12cd),
            "&x&a&b&1&2&c&d\uE5A7&r" to TextColor.color(0xab12cd),
            "§x§a§b§1§2§c§d\uE5A7§r" to TextColor.color(0xab12cd),
        )
        mockkStatic(PlaceholderAPI::class)
        try {
            for ((prefix, color) in prefixes) {
                every { PlaceholderAPI.setPlaceholders(target, "%luckperms_prefix%") } returns prefix
                val identity = teamNameplateIdentity(target)
                assertEquals("\uE5A7", PlainTextComponentSerializer.plainText().serialize(identity.rank))
                assertTextColor(identity.rank, color)
                assertEquals(Component.text("<red>Hero&b", NamedTextColor.WHITE), identity.name)
            }
            verify(exactly = prefixes.size) { PlaceholderAPI.setPlaceholders(target, "%luckperms_prefix%") }
            verify(exactly = 0) { PlaceholderAPI.setPlaceholders(target, "%cmi_user_afk_symbol%") }
        } finally {
            unmockkStatic(PlaceholderAPI::class)
        }
    }

    @Test
    fun `missing rank stays empty and prefix controls cannot create additional rows`() {
        val target = mockk<Player> { every { name } returns "Target" }
        mockkStatic(PlaceholderAPI::class)
        try {
            for (prefix in listOf("", "%luckperms_prefix%")) {
                every { PlaceholderAPI.setPlaceholders(target, "%luckperms_prefix%") } returns prefix
                assertEquals(Component.empty(), teamNameplateIdentity(target).rank)
            }
            every { PlaceholderAPI.setPlaceholders(target, "%luckperms_prefix%") } returns "&f\uE5A7\n\r\t&r"
            val identity = teamNameplateIdentity(target)
            assertEquals("\uE5A7", PlainTextComponentSerializer.plainText().serialize(identity.rank))
            assertEquals(Component.text("Target", NamedTextColor.WHITE), identity.name)
        } finally {
            unmockkStatic(PlaceholderAPI::class)
        }
    }

    private fun assertTextColor(component: Component, expected: TextColor, inherited: TextColor? = null) {
        val actual = component.color() ?: inherited
        if (component is TextComponent && component.content().isNotEmpty()) assertEquals(expected, actual)
        component.children().forEach { assertTextColor(it, expected, actual) }
    }
}
