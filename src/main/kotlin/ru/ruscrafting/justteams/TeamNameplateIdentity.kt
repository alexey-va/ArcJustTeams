package ru.ruscrafting.justteams

import me.clip.placeholderapi.PlaceholderAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player

private const val RANK_PREFIX = "%luckperms_prefix%"
private val rankLegacy = LegacyComponentSerializer.builder().character('&').hexColors()
    .useUnusualXRepeatedCharacterHexFormat().build()

internal data class TeamNameplateIdentity(val rank: Component, val name: Component)

/** Resolve the trusted rank prefix on Paper's primary thread; the real nickname is always literal. */
internal fun teamNameplateIdentity(target: Player): TeamNameplateIdentity {
    val prefix = PlaceholderAPI.setPlaceholders(target, RANK_PREFIX)
        .filterNot(Char::isISOControl).trim().takeUnless { it == RANK_PREFIX }.orEmpty()
    return TeamNameplateIdentity(
        rankLegacy.deserialize(prefix.replace('§', '&')),
        Component.text(target.name, NamedTextColor.WHITE),
    )
}
