package ru.ruscrafting.justteams

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin
import java.util.UUID

/** TAB may request off-thread; [ready] must only read the published online-target snapshot. */
internal class TeamNameplateExpansion(
    private val plugin: Plugin,
    private val ready: (UUID) -> Boolean,
) : PlaceholderExpansion(), AutoCloseable {
    @Volatile private var closed = false

    override fun getIdentifier(): String = "arcjustteams"
    override fun getAuthor(): String = "RusCrafting"
    override fun getVersion(): String = plugin.pluginMeta.version
    override fun persist(): Boolean = true
    override fun getPlaceholders(): List<String> = listOf("%arcjustteams_nameplate_ready%")

    override fun onRequest(player: OfflinePlayer?, params: String): String =
        (!closed && params == "nameplate_ready" && player != null && ready(player.uniqueId)).toString()

    override fun close() {
        if (closed) return
        closed = true
        // PAPI's isRegistered uses value equality and can match a newer generation of this expansion.
        if (placeholderAPI.localExpansionManager.findExpansionByIdentifier(identifier).orElse(null) === this) unregister()
    }
}
