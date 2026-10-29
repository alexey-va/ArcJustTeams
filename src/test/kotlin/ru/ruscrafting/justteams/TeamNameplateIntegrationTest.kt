package ru.ruscrafting.justteams

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.plugin.ServicePriority
import ru.arc.core.TestTaskScheduler
import ru.arc.nameplate.PlayerNameplateRegistry
import ru.arc.paper.api.ArcNameplateClaim
import ru.arc.paper.nameplate.PaperNameplateDisplay
import ru.arc.paper.nameplate.PaperNameplateDisplayFactory
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamNameplateIntegrationTest {
    @Test
    fun `each viewer sees one complete display and an event claim removes the entire plate`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcJustTeamsIntegration")
            plugin.saveDefaultConfig()
            val target = paper.addPlayer("Alexey23")
            val clanmate = paper.addPlayer("Clanmate")
            val stranger = paper.addPlayer("Stranger")
            val team = TeamNameplateTeam(10, "RUS", false, emptySet())
            val protections = mutableMapOf(clanmate.uniqueId to TeamPvpState.CLAN, stranger.uniqueId to TeamPvpState.ALLOWED)
            val rows = TeamNameplateRows(Texts(plugin)) {
                TeamNameplateIdentity(Component.text("\uE5A7"), Component.text(it.name))
            }
            val registry = PlayerNameplateRegistry(512, 4, 160)
            rows.common(target, team).forEach { registry.upsert(target.uniqueId, it) }
            val displays = RecordingPaperNameplateDisplayFactory()
            val readiness = TeamNameplateDisplayReadiness(displays)
            readiness.captured(target.uniqueId, true)
            assertFalse(readiness.ownsIdentity(target.uniqueId), "Capture alone must leave the native nickname visible")
            val scheduler = TestTaskScheduler()
            val visibility = PaperNameplateVisibilityPolicy { viewer, subject -> viewer != subject && mayShowOrdinaryNameplate(plugin, subject) }
            val options = teamNameplateOptions()
            PaperPlayerNameplates.openViewerScoped(
                plugin, registry, options, scheduler, readiness, visibility,
                PaperNameplateViewerContent { viewer, subject, common ->
                    assertEquals(target.uniqueId, subject.uniqueId)
                    rows.forViewer(viewer, common, protections[viewer.uniqueId] ?: TeamPvpState.UNKNOWN)
                },
            ).use { renderer ->
                renderer.refreshNow()
                assertTrue(readiness.ownsIdentity(target.uniqueId))
                val protectedPlate = displays.records.single { clanmate.uniqueId in it.shownTo }
                val allowedPlate = displays.records.single { stranger.uniqueId in it.shownTo }
                assertFalse(protectedPlate === allowedPlate)
                assertEquals(setOf(clanmate.uniqueId), protectedPlate.shownTo)
                assertEquals(setOf(stranger.uniqueId), allowedPlate.shownTo)
                assertEquals("\uE5A7\n[RUS]\nAlexey23\n20/20 ❤ · ⛨", plain(protectedPlate.contents.last()))
                assertEquals("\uE5A7\n[RUS]\nAlexey23\n20/20 ❤ · ⚔", plain(allowedPlate.contents.last()))
                assertEquals(2, renderer.activeDisplayCount,
                    "Two content variants exist, but each viewer sees exactly one complete display")
                exportPreview(mapOf("plate-protected" to protectedPlate.contents.last(),
                    "plate-allowed" to allowedPlate.contents.last(),
                    "plate-unknown" to rows.forViewer(target, checkNotNull(registry.snapshot(target.uniqueId)).content, TeamPvpState.UNKNOWN)))

                protections[stranger.uniqueId] = TeamPvpState.CLAN
                renderer.refreshNow()
                assertEquals(1, renderer.activeDisplayCount)
                assertEquals(setOf(clanmate.uniqueId, stranger.uniqueId), displays.records.single { !it.closed }.shownTo)
                assertTrue(allowedPlate.closed)
                assertTrue(readiness.ownsIdentity(target.uniqueId), "Retiring one variant must retain ownership of the shown variant")

                registry.remove(target.uniqueId, TeamNameplateRows.CLAN)
                rows.common(target, null).forEach { registry.upsert(target.uniqueId, it) }
                renderer.refreshNow()
                assertEquals(1, renderer.activeDisplayCount)
                assertEquals("\uE5A7\nAlexey23\n20/20 ❤ · ⛨", plain(displays.records.single { !it.closed }.contents.last()))

                val events = paper.createSimplePlugin("ArcEvents")
                val claim = ArcNameplateClaim { it == target.uniqueId }
                paper.server.servicesManager.register(ArcNameplateClaim::class.java, claim, events, ServicePriority.Normal)
                renderer.refreshNow()
                assertEquals(0, renderer.activeDisplayCount)
                assertTrue(displays.records.all { it.closed })
                assertFalse(readiness.ownsIdentity(target.uniqueId), "A claim closes the last shown variant and restores native ownership")
            }
        }
    }

    @Test
    fun `creation and show failures never hide native identity and close clears readiness even on failure`() {
        MockBukkitTestRuntime.open().use { paper ->
            val target = paper.addPlayer("Target")
            val viewer = paper.addPlayer("Viewer")
            val recording = RecordingPaperNameplateDisplayFactory()
            var failCreate = true
            var failShow = true
            var failClose = false
            val readiness = TeamNameplateDisplayReadiness(PaperNameplateDisplayFactory { player, content ->
                check(!failCreate) { "create failed" }
                val display = recording.create(player, content)
                object : PaperNameplateDisplay by display {
                    override fun show(viewer: org.bukkit.entity.Player) {
                        check(!failShow) { "show failed" }
                        display.show(viewer)
                    }

                    override fun close() {
                        display.close()
                        check(!failClose) { "close failed" }
                    }
                }
            })
            readiness.captured(target.uniqueId, true)
            assertFailsWith<IllegalStateException> { readiness.create(target, Component.text("Target")) }
            assertFalse(readiness.ownsIdentity(target.uniqueId))

            failCreate = false
            val display = readiness.create(target, Component.text("Target"))
            assertFalse(readiness.ownsIdentity(target.uniqueId), "A created but unshown display is not ready")
            assertFailsWith<IllegalStateException> { display.show(viewer) }
            assertFalse(readiness.ownsIdentity(target.uniqueId))

            failShow = false
            display.show(viewer)
            assertTrue(readiness.ownsIdentity(target.uniqueId))
            readiness.captured(target.uniqueId, false)
            assertFalse(readiness.ownsIdentity(target.uniqueId), "Capture failure or event ownership revokes readiness")
            readiness.captured(target.uniqueId, true)
            assertTrue(readiness.ownsIdentity(target.uniqueId), "A previously shown valid surface survives capture recovery")

            failShow = true
            assertFailsWith<IllegalStateException> { display.show(viewer) }
            assertFalse(readiness.ownsIdentity(target.uniqueId), "Failure also revokes an already shown handle")
            failShow = false
            display.show(viewer)
            failClose = true
            assertFailsWith<IllegalStateException> { display.close() }
            assertFalse(readiness.ownsIdentity(target.uniqueId))
        }
    }

    private fun exportPreview(
        frames: Map<String, Component>,
    ) {
        val directory = System.getenv("ARC_NAMEPLATE_PREVIEW_DIR")?.let(Path::of) ?: return
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("frames.tsv"), frames.entries.joinToString("\n") { (name, component) ->
            val mini = MiniMessage.miniMessage().serialize(component)
            val encoded = Base64.getEncoder().encodeToString(mini.toByteArray(Charsets.UTF_8))
            "$name\t$encoded\t${PlainTextComponentSerializer.plainText().serialize(component).replace('\n', '|')}"
        })
        val options = teamNameplateOptions()
        Files.writeString(directory.resolve("geometry.tsv"),
            "surface\tone-multiline-display\nanchor\tnative-name-tag\nworld-anchor-above-head\t0.5\nscale\t${options.scale}\nvertical-offset\t${options.verticalOffset}\nfont-line-stride\t10\nline-width\t${options.lineWidth}\nbackground-argb\t${options.backgroundColor.asARGB()}\nshadowed\t${options.shadowed}\nblock-light\t${options.brightness?.blockLight}\nsky-light\t${options.brightness?.skyLight}")
    }

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
}
