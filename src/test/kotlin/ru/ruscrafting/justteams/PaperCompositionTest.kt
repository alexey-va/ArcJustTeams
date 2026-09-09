package ru.ruscrafting.justteams

import kotlin.test.Test
import kotlin.test.assertNotNull
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperCompositionTest {
    @Test
    fun `canonical Paper test runtime is available`() {
        MockBukkitTestRuntime.open().use { runtime ->
            assertNotNull(runtime.server)
        }
    }
}
