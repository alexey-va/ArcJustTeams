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
    fun `clan and viewer scoped health occupy separate lanes and an event claim removes both`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcJustTeamsIntegration")
            plugin.saveDefaultConfig()
            val target = paper.addPlayer("Alexey23")
            val clanmate = paper.addPlayer("Clanmate")
            val stranger = paper.addPlayer("Stranger")
            val team = TeamNameplateTeam(10, "RUS", false, emptySet())
            val protections = mutableMapOf(clanmate.uniqueId to TeamPvpState.CLAN, stranger.uniqueId to TeamPvpState.ALLOWED)
            val rows = TeamNameplateRows(Texts(plugin))
            val clanRegistry = PlayerNameplateRegistry(512, 1, 160)
            val healthRegistry = PlayerNameplateRegistry(512, 1, 160)
            rows.common(target, team).forEach {
                (if (it.key == TeamNameplateRows.CLAN) clanRegistry else healthRegistry).upsert(target.uniqueId, it)
            }
            val clanDisplays = RecordingPaperNameplateDisplayFactory()
            val healthDisplays = RecordingPaperNameplateDisplayFactory()
            val scheduler = TestTaskScheduler()
            val visibility = PaperNameplateVisibilityPolicy { viewer, subject -> viewer != subject && mayShowOrdinaryNameplate(plugin, subject) }
            val clanOptions = clanNameplateOptions()
            val healthOptions = healthNameplateOptions()
            assertTrue(clanOptions.verticalOffset > healthOptions.verticalOffset + 0.4F,
                "The native nickname has a gap between the upper clan and lower health lanes")
            PaperPlayerNameplates.openViewerScoped(
                plugin, clanRegistry, clanOptions, scheduler, clanDisplays, visibility,
                PaperNameplateViewerContent { _, _, common -> common },
            ).use { clanRenderer ->
                PaperPlayerNameplates.openViewerScoped(
                    plugin, healthRegistry, healthOptions, scheduler, healthDisplays, visibility,
                    PaperNameplateViewerContent { viewer, subject, common ->
                        assertEquals(target.uniqueId, subject.uniqueId)
                        rows.forViewer(viewer, common, protections[viewer.uniqueId] ?: TeamPvpState.UNKNOWN)
                    },
                ).use { healthRenderer ->
                    clanRenderer.refreshNow()
                    healthRenderer.refreshNow()
                    val clanPlate = clanDisplays.records.single()
                    val protectedPlate = healthDisplays.records.single { clanmate.uniqueId in it.shownTo }
                    val allowedPlate = healthDisplays.records.single { stranger.uniqueId in it.shownTo }
                    assertFalse(protectedPlate === allowedPlate)
                    assertEquals(setOf(clanmate.uniqueId, stranger.uniqueId), clanPlate.shownTo)
                    assertEquals(setOf(clanmate.uniqueId), protectedPlate.shownTo)
                    assertEquals(setOf(stranger.uniqueId), allowedPlate.shownTo)
                    assertEquals("[RUS]", plain(clanPlate.contents.last()))
                    assertEquals("20/20 ❤ · ⛨", plain(protectedPlate.contents.last()))
                    assertEquals("20/20 ❤ · ⚔", plain(allowedPlate.contents.last()))
                    assertEquals(1, clanRenderer.activeDisplayCount)
                    assertEquals(2, healthRenderer.activeDisplayCount)
                    exportPreview(mapOf("clan" to clanPlate.contents.last(), "health-protected" to protectedPlate.contents.last(),
                        "health-allowed" to allowedPlate.contents.last()), rows, target)

                    protections[stranger.uniqueId] = TeamPvpState.CLAN
                    healthRenderer.refreshNow()
                    assertEquals(1, healthRenderer.activeDisplayCount)
                    assertEquals(setOf(clanmate.uniqueId, stranger.uniqueId), healthDisplays.records.single { !it.closed }.shownTo)
                    assertTrue(allowedPlate.closed)

                    val events = paper.createSimplePlugin("ArcEvents")
                    val claim = ArcNameplateClaim { it == target.uniqueId }
                    paper.server.servicesManager.register(ArcNameplateClaim::class.java, claim, events, ServicePriority.Normal)
                    clanRenderer.refreshNow()
                    healthRenderer.refreshNow()
                    assertEquals(0, clanRenderer.activeDisplayCount)
                    assertEquals(0, healthRenderer.activeDisplayCount)
                    assertTrue((clanDisplays.records + healthDisplays.records).all { it.closed })
                }
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
        val health = rows.common(player, null).single().content
        val samples = frames + ("health-unknown" to rows.forViewer(player, health, TeamPvpState.UNKNOWN))
        Files.writeString(directory.resolve("frames.tsv"), samples.entries.joinToString("\n") { (name, component) ->
            val mini = MiniMessage.miniMessage().serialize(component)
            val encoded = Base64.getEncoder().encodeToString(mini.toByteArray(Charsets.UTF_8))
            "$name\t$encoded\t${PlainTextComponentSerializer.plainText().serialize(component).replace('\n', '|')}"
        })
        val options = clanNameplateOptions()
        Files.writeString(directory.resolve("geometry.tsv"),
            "scale\t${options.scale}\nclan-vertical-offset\t${options.verticalOffset}\nhealth-vertical-offset\t${healthNameplateOptions().verticalOffset}\nline-width\t${options.lineWidth}\nbackground-argb\t${options.backgroundColor.asARGB()}")
    }

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
}
