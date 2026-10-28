package ru.ruscrafting.justteams

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import ru.arc.nameplate.NameplateLayer
import ru.arc.nameplate.NameplateLayerKey
import kotlin.math.ceil

/** Clan sits above the native TAB identity, health below; PvP is resolved for each observer. */
internal class TeamNameplateRows(private val texts: Texts) {
    fun common(target: Player, team: TeamNameplateTeam?): List<NameplateLayer> = buildList {
        val tag = team?.tag?.filterNot(Char::isISOControl)?.trim()?.take(64).orEmpty()
        if (tag.isNotEmpty()) {
            add(NameplateLayer(CLAN, 200, texts.get(target, "nameplate.clan", mapOf("tag" to tag))))
        }
        val maximum = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: target.health.coerceAtLeast(20.0)
        val absorption = wholeHealth(target.absorptionAmount)
        add(NameplateLayer(HEALTH, 100, texts.get(
            target,
            if (absorption > 0) "nameplate.health-absorption" else "nameplate.health",
            mapOf("health" to wholeHealth(target.health), "max_health" to wholeHealth(maximum), "absorption" to absorption),
        )))
    }

    fun forViewer(viewer: Player, common: Component, pvp: TeamPvpState): Component = common
        .append(Component.text(" · ", TextColor.color(0xe8dfd2)))
        .append(texts.get(viewer, pvp.localeKey))

    companion object {
        const val OWNER = "arcjustteams"
        val CLAN = NameplateLayerKey(OWNER, "clan")
        val HEALTH = NameplateLayerKey(OWNER, "health")

        private fun wholeHealth(value: Double): Int =
            if (value.isFinite()) ceil(value.coerceAtLeast(0.0)).toInt() else 0
    }
}
