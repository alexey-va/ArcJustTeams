package ru.ruscrafting.justteams

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.ConfigManager
import ru.arc.text.ConfigLocaleCatalog
import ru.arc.text.LocalizedMiniMessage

internal class Texts(private val plugin: JavaPlugin) {
    private val renderer: LocalizedMiniMessage

    init {
        val catalogs = listOf("ru", "en").associateWith { locale ->
            val path = "lang/$locale.yml"
            if (!plugin.dataFolder.resolve(path).exists()) plugin.saveResource(path, false)
            val config = ConfigManager.of(plugin.dataFolder.toPath(), path)
            config.mergeMissingFromBundled(path)
            ConfigLocaleCatalog(config)
        }
        renderer = LocalizedMiniMessage(catalogs, defaultLocale = { "ru" })
    }

    fun get(player: Player, key: String, values: Map<String, Any> = emptyMap()): Component {
        val components = values.mapValues { (_, value) -> renderer.literal(value) }
        return renderer.render(key, player.locale().toLanguageTag(), components)
            .decoration(TextDecoration.ITALIC, false)
    }

    fun getPlain(player: Player, key: String, values: Map<String, Any> = emptyMap()): String =
        PlainTextComponentSerializer.plainText().serialize(get(player, key, values))
}
