package ru.ruscrafting.justteams

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

internal class Texts(private val plugin: JavaPlugin) {
    private val mini = MiniMessage.miniMessage()
    private val locales = mapOf("ru" to load("ru"), "en" to load("en"))

    fun get(player: Player, key: String, values: Map<String, Any> = emptyMap()): Component {
        val locale = if (player.locale().language.equals("ru", ignoreCase = true)) "ru" else "en"
        var source = locales.getValue(locale).getString(key) ?: locales.getValue("ru").getString(key) ?: key
        values.forEach { (name, value) -> source = source.replace("{$name}", value.toString()) }
        return mini.deserialize(source)
    }

    private fun load(locale: String): YamlConfiguration {
        val path = "lang/$locale.yml"
        val file = File(plugin.dataFolder, path)
        if (!file.exists()) plugin.saveResource(path, false)
        return YamlConfiguration.loadConfiguration(file)
    }
}
