package ru.ruscrafting.justteams

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamAllySuggestionsTest {
    @Test
    fun `eligible suggestions retain catalog rank and selected proposal sends once`() = failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper ->
            AllySuggestionsFixture(paper).use { fixture ->
                fixture.team = fixture.team.copy(allies = listOf(11), sentAllyRequests = listOf(12), receivedAllyRequests = listOf(13))
                fixture.catalog = listOf(
                    candidate(4, "Own Clan", 700), candidate(20, "Silver Wolves", 600),
                    candidate(11, "Existing Ally", 500), candidate(12, "Outgoing Clan", 400),
                    candidate(13, "Incoming Clan", 300), candidate(14, "Closed Clan", 200).copy(acceptsAllyRequests = false),
                    candidate(21, "Iron Hawks", 100),
                )

                fixture.browseCandidates()
                assertEquals(listOf("#2 Silver Wolves ›", "#7 Iron Hawks ›"),
                    fixture.screen.buttons.filter { it.id.value.startsWith("candidate_") }.map { plain(it.label) })
                fixture.click("candidate_0")
                assertEquals("arcjustteams.allies.propose", fixture.screen.id)
                assertTrue("Silver Wolves" in fixture.body)
                verify(exactly = 0) { fixture.teams.sendAllyRequest(any(), any()) }

                every { fixture.teams.sendAllyRequest(fixture.player, "Silver Wolves") } answers {
                    fixture.team = fixture.team.copy(sentAllyRequests = fixture.team.sentAllyRequests + 20)
                }
                fixture.click("send")
                fixture.until { fixture.screen.id == "arcjustteams.allies" }
                assertTrue(fixture.message("allies.sent") in fixture.body)
                assertTrue(20 in fixture.team.sentAllyRequests)
                verify(exactly = 1) { fixture.teams.sendAllyRequest(fixture.player, "Silver Wolves") }
            }
        }
    }

    @Test
    fun `changed target and changed actor are rejected before native proposal`() = failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper ->
            AllySuggestionsFixture(paper).use { fixture ->
                val target = candidate(20, "Silver Wolves", 600)
                fixture.catalog = listOf(target)
                fixture.browseCandidates()
                fixture.click("candidate_0")

                fixture.catalog = listOf(target.copy(acceptsAllyRequests = false))
                fixture.click("send")
                fixture.until { fixture.screen.id == "arcjustteams.allies.add" && fixture.screen.buttons.any { it.id.value == "search" } }
                assertTrue(fixture.message("allies.candidate-changed") in fixture.body)
                assertTrue(fixture.screen.buttons.none { it.id.value.startsWith("candidate_") })
                verify(exactly = 0) { fixture.teams.sendAllyRequest(any(), any()) }

                fixture.catalog = listOf(target)
                fixture.browseCandidates()
                fixture.click("candidate_0")
                fixture.team = fixture.team.copy(id = 99, name = "Another Clan")
                fixture.click("send")
                assertEquals("arcjustteams.allies", fixture.screen.id)
                assertTrue(fixture.message("allies.candidate-changed") in fixture.body)
                verify(exactly = 0) { fixture.teams.sendAllyRequest(any(), any()) }
            }
        }
    }
}

private class AllySuggestionsFixture(private val paper: MockBukkitTestRuntime) : AutoCloseable {
    private val plugin = paper.createSimplePlugin("AllySuggestions").also { it.saveDefaultConfig() }
    val player = paper.addPlayer("ClanOwner")
    private val texts = Texts(plugin)
    val teams = mockk<JustTeamsGateway>()
    var team = TeamSnapshot(Any(), 4, "Own Clan", "OWN", "", player.uniqueId,
        3, 2450L, 12000.0, 12, 5, setOf(player.uniqueId), emptySet(),
        18L, false, false, true, true, emptyList(), emptyList(), emptyList(), emptySet())
    var catalog = emptyList<TeamBrowseSnapshot>()
    private val screens = mutableListOf<PaperDialogScreen>()
    private val runtime = mockk<PaperDialogRuntime>(relaxed = true)
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    private val nativeWork = BudgetedTaskQueue(tasks)
    private val worker = BoundedAsyncExecutor("ally-suggestions-test", 1, 16, false)
    private val dialogs: TeamDialogs

    init {
        every { teams.team(player.uniqueId) } answers { team }
        every { teams.elevated(any(), player.uniqueId) } returns true
        every { teams.teamName(any()) } answers { "Clan ${firstArg<Int>()}" }
        every { teams.browseTeamsAsync() } answers { CompletableFuture.completedFuture(catalog) }
        every { runtime.open(any(), any(), any(), any(), any()) } answers { screens += secondArg<PaperDialogScreen>() }
        dialogs = TeamDialogs(runtime, texts, teams, null, null, tasks, 2,
            mockk(relaxed = true), nativeWork, worker)
    }

    val screen: PaperDialogScreen get() = screens.last()
    val body: String get() = screen.body.joinToString(" ") { plain(it.text) }
        .replace(Regex("\\p{Co}"), "").replace(Regex("\\s+"), " ").trim()
    fun message(key: String) = texts.getPlain(player, key)

    fun browseCandidates() {
        dialogs.begin(player)
        click("allies")
        click("add")
        until { screen.buttons.any { it.id.value == "search" } }
    }

    fun click(id: String) = screen.buttons.single { it.id.value == id }.onClick.handle(mockk())

    fun until(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!done() && System.nanoTime() < deadline) {
            paper.performTicks(1)
            Thread.sleep(1)
        }
        assertTrue(done(), "Alliance flow did not complete; screen=${screen.id}, body=$body")
    }

    override fun close() {
        nativeWork.close()
        tasks.close()
        worker.closeAsync().get(5, TimeUnit.SECONDS)
    }
}

private fun candidate(id: Int, name: String, points: Long) =
    TeamBrowseSnapshot(name, "T$id", 5, false, id, points, 2, true)

private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
