package ru.ruscrafting.justteams

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.plugin.ServicePriority
import ru.arc.core.TestTaskScheduler
import ru.arc.nameplate.PlayerNameplateRegistry
import ru.arc.paper.api.ArcNameplateClaim
import ru.arc.paper.nameplate.PaperNameplateViewerContent
import ru.arc.paper.nameplate.PaperNameplateVisibilityPolicy
import ru.arc.paper.nameplate.PaperPlayerNameplates
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.RecordingPaperNameplateDisplayFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamNameplateIntegrationTest {
    @Test
    fun `same player has private clan and unknown plates and an event claim removes both`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcJustTeamsIntegration")
            plugin.saveDefaultConfig()
            val target = paper.addPlayer("Alexey23")
            val clanmate = paper.addPlayer("Clanmate")
            val stranger = paper.addPlayer("Stranger")
            val team = TeamNameplateTeam(10, "RUS", false, emptySet())
            val protections = mutableMapOf(clanmate.uniqueId to TeamPvpState.CLAN)
            val rows = TeamNameplateRows(Texts(plugin))
            val registry = PlayerNameplateRegistry()
            rows.common(target, team).forEach { registry.upsert(target.uniqueId, it) }
            val displays = RecordingPaperNameplateDisplayFactory()
            val scheduler = TestTaskScheduler()
            PaperPlayerNameplates.openViewerScoped(
                plugin, registry, clanNameplateOptions(), scheduler, displays,
                PaperNameplateVisibilityPolicy { viewer, subject -> viewer != subject && mayShowOrdinaryNameplate(plugin, subject) },
                PaperNameplateViewerContent { viewer, subject, common ->
                    assertEquals(target.uniqueId, subject.uniqueId)
                    rows.forViewer(viewer, common, protections[viewer.uniqueId] ?: TeamPvpState.UNKNOWN)
                },
            ).use { renderer ->
                renderer.refreshNow()
                val clanPlate = displays.records.single { clanmate.uniqueId in it.shownTo }
                val unknownPlate = displays.records.single { stranger.uniqueId in it.shownTo }
                assertFalse(clanPlate === unknownPlate)
                assertEquals(setOf(clanmate.uniqueId), clanPlate.shownTo)
                assertEquals(setOf(stranger.uniqueId), unknownPlate.shownTo)
                assertEquals(2, renderer.activeDisplayCount)
                exportPreview(mapOf("clan" to clanPlate.contents.last(), "unknown" to unknownPlate.contents.last()), rows, target)

                protections[stranger.uniqueId] = TeamPvpState.CLAN
                renderer.refreshNow()
                assertEquals(1, renderer.activeDisplayCount)
                assertEquals(setOf(clanmate.uniqueId, stranger.uniqueId), displays.records.single { !it.closed }.shownTo)
                assertTrue(unknownPlate.closed)

                val events = paper.createSimplePlugin("ArcEvents")
                val claim = ArcNameplateClaim { it == target.uniqueId }
                paper.server.servicesManager.register(ArcNameplateClaim::class.java, claim, events, ServicePriority.Normal)
                renderer.refreshNow()
                assertEquals(0, renderer.activeDisplayCount)
                assertTrue(displays.records.all { it.closed })
            }
        }
    }

    private fun exportPreview(
        frames: Map<String, Component>,
        rows: TeamNameplateRows,
        player: org.bukkit.entity.Player,
    ) {
        val directory = System.getenv("ARC_NAMEPLATE_PREVIEW_DIR")?.let(Path::of) ?: return
        Files.createDirectories(directory)
        val common = Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), rows.common(player,
            TeamNameplateTeam(10, "RUS", false, emptySet())).map { it.content })
        val samples = frames + mapOf(
            "ally" to rows.forViewer(player, common, TeamPvpState.ALLY),
            "lands" to rows.forViewer(player, common, TeamPvpState.LANDS),
        )
        Files.writeString(directory.resolve("frames.tsv"), samples.entries.joinToString("\n") { (name, component) ->
            val mini = MiniMessage.miniMessage().serialize(component)
            val encoded = Base64.getEncoder().encodeToString(mini.toByteArray(Charsets.UTF_8))
            "$name\t$encoded\t${PlainTextComponentSerializer.plainText().serialize(component).replace('\n', '|')}"
        })
        val options = clanNameplateOptions()
        Files.writeString(directory.resolve("geometry.tsv"),
            "scale\t${options.scale}\nvertical-offset\t${options.verticalOffset}\nline-width\t${options.lineWidth}\nbackground-argb\t${options.backgroundColor.asARGB()}")
    }
}
