package ru.ruscrafting.justteams

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import ru.arc.nameplate.NameplateLayer
import ru.arc.nameplate.NameplateLayerKey
import java.util.regex.Pattern
import kotlin.math.ceil

/** One multiline plate contains rank, optional clan, real nickname and observer-specific health/PvP. */
internal class TeamNameplateRows(private val texts: Texts, private val identity: (Player) -> TeamNameplateIdentity) {
    fun common(target: Player, team: TeamNameplateTeam?): List<NameplateLayer> = buildList {
        val playerIdentity = identity(target)
        val rank = singleLine(playerIdentity.rank)
        if (plainText.serialize(rank).isNotBlank()) add(NameplateLayer(RANK, 400, rank))
        val tag = team?.tag?.filterNot(Char::isISOControl)?.trim()?.take(64).orEmpty()
        if (tag.isNotEmpty()) {
            add(NameplateLayer(CLAN, 300, singleLine(texts.get(target, "nameplate.clan", mapOf("tag" to tag)))))
        }
        add(NameplateLayer(IDENTITY, 200, singleLine(playerIdentity.name)))
        val maximum = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: target.health.coerceAtLeast(20.0)
        val absorption = wholeHealth(target.absorptionAmount)
        add(NameplateLayer(HEALTH, 100, singleLine(texts.get(
            target,
            if (absorption > 0) "nameplate.health-absorption" else "nameplate.health",
            mapOf("health" to wholeHealth(target.health), "max_health" to wholeHealth(maximum), "absorption" to absorption),
        ))))
    }

    fun forViewer(viewer: Player, common: Component, pvp: TeamPvpState): Component = common
        .append(Component.text(" · ", TextColor.color(0xe8dfd2)))
        .append(singleLine(texts.get(viewer, pvp.localeKey)))

    companion object {
        const val OWNER = "arcjustteams"
        val RANK = NameplateLayerKey(OWNER, "rank")
        val CLAN = NameplateLayerKey(OWNER, "clan")
        val IDENTITY = NameplateLayerKey(OWNER, "identity")
        val HEALTH = NameplateLayerKey(OWNER, "health")
        private val plainText = PlainTextComponentSerializer.plainText()
        private val rowControls = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]")

        private fun singleLine(component: Component): Component = component.replaceText {
            it.match(rowControls).replacement(" ")
        }

        private fun wholeHealth(value: Double): Int =
            if (value.isFinite()) ceil(value.coerceAtLeast(0.0)).toInt() else 0
    }
}
