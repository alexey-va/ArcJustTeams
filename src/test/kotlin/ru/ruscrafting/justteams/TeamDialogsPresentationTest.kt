package ru.ruscrafting.justteams

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamDialogsPresentationTest {
    @Test
    fun `root groups clan facts and named settlements in actual localized components`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ClanPresentation")
            plugin.saveDefaultConfig()
            val player = paper.addPlayer("ClanOwner")
            val texts = Texts(plugin)
            val teams = mockk<JustTeamsGateway>()
            val lands = mockk<LandsBridge>()
            val team = TeamSnapshot(Any(), 4, "Северный союз", "NORD", "", player.uniqueId,
                3, 2450L, 12000.0, 12, 5, setOf(player.uniqueId), setOf("oak", "coast"),
                18L, false, false, true, true, emptyList(), emptyList(), emptyList(), setOf("speed"))
            every { teams.team(player.uniqueId) } returns team
            every { lands.linked(team.landIds) } returns listOf(
                TeamLand("oak", "Дубовая долина", 12), TeamLand("coast", "Северная пристань", 9),
            )
            val screens = mutableListOf<PaperDialogScreen>()
            val runtime = mockk<PaperDialogRuntime>(relaxed = true)
            every { runtime.open(any(), any(), any(), any(), any()) } answers { screens += secondArg<PaperDialogScreen>() }
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                val dialogs = TeamDialogs(runtime, texts, teams, lands, null, tasks, 2,
                    mockk(relaxed = true), BudgetedTaskQueue(tasks), mockk(relaxed = true))
                dialogs.begin(player)
                val root = screens.single()
                assertEquals("arcjustteams.root", root.id)
                assertEquals("[NORD] Северный союз", plain(root.title))
                val content = root.body.joinToString("\n") { plain(it.text) }
                listOf("Состав и вылазки", "Развитие клана", "Дубовая долина", "Северная пристань").forEach {
                    assertTrue(it in content, "Missing $it in root")
                }
                assertEquals(listOf("members", "allies", "quests", "dungeons", "upgrades", "buffs", "lands", "settings", "spawn_0", "spawn_1"),
                    root.buttons.map { it.id.value })
                assertTrue(root.buttons.all { !it.closeDialogBeforeAction })
                assertEquals(2, root.columns)
                export(root, "root")
                verify(exactly = 0) { lands.travel(any(), any()) }

                every { teams.buffs(team) } returns listOf(
                    TeamBuffSnapshot("Speed I", true, 0, "speed"),
                    TeamBuffSnapshot("Regeneration I", false, 0, "regeneration"),
                    TeamBuffSnapshot("Haste I", false, 100, "haste"),
                    TeamBuffSnapshot("Resistance I", false, 150, "resistance"),
                    TeamBuffSnapshot("Strength I", false, 200, "strength"),
                    TeamBuffSnapshot("+10% Mining XP", false, 150, "mining_xp"),
                    TeamBuffSnapshot("Fire Resistance", false, 150, "fire_resistance"),
                )
                every { teams.buffLimits() } returns Triple(2, 3, 20)
                every { teams.buffEditingAvailable() } returns false
                root.buttons.single { it.id.value == "buffs" }.onClick.handle(mockk())
                val buffs = screens.last()
                assertEquals("arcjustteams.buffs", buffs.id)
                assertEquals("✔ Скорость I ›", plain(buffs.buttons.first().label))
                assertEquals(7, buffs.buttons.size)
                export(buffs, "buffs")
                buffs.buttons.first().onClick.handle(mockk())
                val detail = screens.last()
                assertEquals("arcjustteams.buff", detail.id)
                assertEquals("Скорость I", plain(detail.title))
                assertTrue(detail.buttons.isEmpty(), "Unavailable native mutation must remain inaccessible")
                assertTrue(detail.body.any { "Ускоряет передвижение" in plain(it.text) })
                export(detail, "buff-detail-unavailable")
                every { teams.buffEditingAvailable() } returns true
                every { teams.elevated(team, player.uniqueId) } returns true
                root.buttons.single { it.id.value == "buffs" }.onClick.handle(mockk())
                val editable = screens.last()
                export(editable, "buffs")
                editable.buttons.single { it.id.value == "buff_4" }.onClick.handle(mockk())
                val paid = screens.last()
                assertEquals("Сила I", plain(paid.title))
                assertEquals(listOf("configure"), paid.buttons.map { it.id.value })
                export(paid, "buff-detail")
                paid.buttons.single().onClick.handle(mockk())
                val confirmation = screens.last()
                assertEquals("arcjustteams.buff.confirm", confirmation.id)
                assertTrue(confirmation.body.any { "200" in plain(it.text) })
                export(confirmation, "buff-confirm")
                verify(exactly = 0) { teams.setBuff(any(), any(), any(), any(), any()) }
            }
        }
    }

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)

    /** Captures the production renderer output for the existing resource-pack preview tool. */
    private fun export(screen: PaperDialogScreen, name: String) {
        val legacy = LegacyComponentSerializer.builder().character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build()
        val data = YamlConfiguration()
        val glyphs = linkedMapOf<String, String>()
        fun text(path: String, component: Component) {
            data.set(path, legacy.serialize(component))
            fun collect(node: Component, inherited: String = "minecraft:default") {
                val font = node.font()?.asString() ?: inherited
                if (node is TextComponent) node.content().codePoints().forEach { point ->
                    if (Character.getType(point) == Character.PRIVATE_USE.toInt()) glyphs[String(Character.toChars(point))] = font
                }
                node.children().forEach { collect(it, font) }
            }
            collect(component)
        }
        text("name", screen.title)
        text("external_title", screen.externalTitle)
        data.set("columns", screen.columns)
        screen.body.forEachIndexed { index, body ->
            text("body.section$index.text", body.text)
            data.set("body.section$index.width", body.width)
        }
        screen.buttons.forEach { button ->
            text("buttons.${button.id.value}.text", button.label)
            text("buttons.${button.id.value}.tooltip", button.tooltip)
            data.set("buttons.${button.id.value}.width", button.width)
        }
        screen.exitButton?.let { button ->
            text("exit.text", button.label)
            text("exit.tooltip", button.tooltip)
            data.set("exit.width", button.width)
        }
        screen.inputs.forEach { input ->
            text("inputs.${input.id.value}.label", input.label)
            data.set("inputs.${input.id.value}.initial", input.initial)
            data.set("inputs.${input.id.value}.width", input.width)
        }
        data.set("glyphs", glyphs.map { (character, provider) -> mapOf("character" to character, "provider" to provider) })
        val path = Path.of("build/dialog-preview/$name.yml").toFile()
        path.parentFile.mkdirs()
        data.save(path)
    }
}
