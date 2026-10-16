package ru.ruscrafting.justteams

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation

class TextsLocalePolicyTest {
    @Test
    fun `Russian is the default and client language requires explicit opt-in`() = failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcJustTeamsLocale")
            plugin.saveDefaultConfig()
            val player = paper.addPlayer("EnglishClient").apply { setLocale(Locale.US) }
            val texts = Texts(plugin)

            assertEquals("ru", plugin.config.getString("locale.default"))
            assertFalse(plugin.config.getBoolean("locale.use-client-locale"))
            assertEquals("Команда", texts.getPlain(player, "title"))

            plugin.config.setDefaults(YamlConfiguration())
            plugin.config.set("locale", null)
            assertEquals("Команда", texts.getPlain(player, "title"), "Existing configs without locale settings stay Russian")

            val configFile = plugin.dataFolder.resolve("config.yml")
            configFile.writeText("locale:\n  default: ru\n  use-client-locale: true\n")
            plugin.reloadConfig()
            assertEquals("Team", texts.getPlain(player, "title"))

            player.setLocale(Locale.GERMAN)
            assertEquals("Команда", texts.getPlain(player, "title"), "Unsupported client languages use the configured default")

            configFile.writeText("locale:\n  default: en\n  use-client-locale: false\n")
            plugin.reloadConfig()
            player.setLocale(Locale.forLanguageTag("ru-RU"))
            assertEquals("Team", texts.getPlain(player, "title"), "English remains selectable for every client")
        }
    }
}
