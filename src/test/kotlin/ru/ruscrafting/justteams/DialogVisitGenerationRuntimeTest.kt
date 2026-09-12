package ru.ruscrafting.justteams

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.event.player.PlayerCustomClickEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime

class DialogVisitGenerationRuntimeTest {
    @Test
    fun `fresh exit callback dismisses pending work after consumed action`() {
        MockBukkitTestRuntime.open().use { paper ->
            val player = spyk(paper.addPlayer("DialogGenerationTester"))
            every { player.closeDialog() } just Runs
            val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
            val generation = DialogVisitGeneration()
            lateinit var registration: Any
            var renders = 0
            var pending: (() -> Unit)? = null
            var pendingButtons = -1

            val runtime = runtimeWithPresenter(paper.createSimplePlugin("DialogGeneration")) { screen, registered ->
                renders++
                registration = registered
                assertEquals("pending", screen.id)
                if (renders == 2) pendingButtons = screen.buttons.size
            }
            runtime.use { runtime ->
                fun render(pendingScreen: Boolean = false) {
                    generation.advance()
                    runtime.open(player, PaperDialogScreen(
                        id = "pending", title = Component.text("Pending"),
                        buttons = if (pendingScreen) emptyList() else listOf(
                            PaperDialogButton(PaperDialogActionId.of("wait"), Component.text("Wait")) {
                                render(pendingScreen = true)
                                val token = generation.current()
                                pending = { generation.ifCurrent(token) { render() } }
                            },
                        ),
                        exitButton = PaperDialogButton(PaperDialogActionId.of("close"), Component.text("Close")) {},
                    ), null, generation::invalidate, false)
                }

                fun click(id: String) {
                    runtime.onCustomClick(mockk<PlayerCustomClickEvent> {
                        every { commonConnection } returns connection
                        every { identifier } returns Key.key(registrationKey(registration, id))
                        every { dialogResponseView } returns null
                    })
                }

                runtime.beginFlow(player)
                render()
                click("wait")
                assertEquals(2, renders)
                assertEquals(0, pendingButtons)

                click("close")
                pending!!.invoke()
                assertEquals(2, renders)
            }
        }
    }

    @Test
    fun `team create publishes a pending footer and stale task cannot reopen after back`() {
        MockBukkitTestRuntime.open().use { paper ->
            val player = spyk(paper.addPlayer("TeamDialogsTester"))
            every { player.closeDialog() } just Runs
            val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
            val plugin = paper.createSimplePlugin("TeamDialogsIntegration")
            val screens = mutableListOf<PaperDialogScreen>()
            lateinit var registration: Any
            val presenterRuntime = runtimeWithPresenter(plugin) { screen, registered ->
                screens += screen
                registration = registered
            }
            val texts = mockk<Texts>()
            every { texts.get(any(), any(), any()) } answers { Component.text(secondArg<String>()) }
            val teams = mockk<JustTeamsGateway>()
            every { teams.team(any()) } returns null
            every { teams.create(any(), any(), any()) } returns null
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val dialogs = TeamDialogs(
                runtime = presenterRuntime,
                texts = texts,
                teams = teams,
                lands = null,
                landReconciler = null,
                tasks = tasks,
                minimumDungeonMembers = 2,
                dungeonParties = mockk(relaxed = true),
            )

            fun click(id: String) {
                presenterRuntime.onCustomClick(mockk<PlayerCustomClickEvent> {
                    every { commonConnection } returns connection
                    every { identifier } returns Key.key(registrationKey(registration, id, "teamdialogsintegration"))
                    every { dialogResponseView } returns null
                })
            }

            presenterRuntime.use {
                tasks.use {
                    dialogs.begin(player)
                    click("create")
                    click("create_submit")
                    assertEquals("arcjustteams.create", screens.last().id)
                    assertEquals(0, screens.last().buttons.size)
                    click("back")
                    verify(exactly = 0) { player.closeDialog() }
                    val beforeTicks = screens.size
                    paper.performTicks(20)
                    assertEquals(beforeTicks, screens.size)
                    assertEquals("arcjustteams.empty", screens.last().id)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runtimeWithPresenter(
        plugin: org.bukkit.plugin.Plugin,
        onPresent: (PaperDialogScreen, Any) -> Unit,
    ): PaperDialogRuntime {
        val presenter = object : Function3<Any, Any, Any, Unit> {
            override fun invoke(first: Any, screen: Any, registration: Any) {
                onPresent(screen as PaperDialogScreen, registration)
            }
        }
        val constructor = PaperDialogRuntime::class.java.declaredConstructors
            .single { it.parameterTypes.size == 2 }
            .also { it.isAccessible = true }
        return constructor.newInstance(plugin, presenter) as PaperDialogRuntime
    }

    private fun registrationKey(registration: Any, id: String, namespace: String = "dialoggeneration"): String =
        "$namespace:dialog/${registration.javaClass.getMethod("getNonce").invoke(registration)}/$id"
}
