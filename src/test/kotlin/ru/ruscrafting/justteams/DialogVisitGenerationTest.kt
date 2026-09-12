package ru.ruscrafting.justteams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialogVisitGenerationTest {
    @Test
    fun `delayed callback is allowed for its unchanged visit`() {
        val generation = DialogVisitGeneration()
        val token = generation.advance()
        var reopened = 0

        assertTrue(generation.ifCurrent(token) { reopened++ })
        assertEquals(1, reopened)
    }

    @Test
    fun `dismissed visit cannot reopen from a delayed callback`() {
        val generation = DialogVisitGeneration()
        val token = generation.advance()
        var reopened = 0
        generation.invalidate()

        assertFalse(generation.ifCurrent(token) { reopened++ })
        assertEquals(0, reopened)
    }

    @Test
    fun `new navigation invalidates the previous visit generation`() {
        val generation = DialogVisitGeneration()
        val oldToken = generation.advance()
        val newToken = generation.advance()
        var reopened = 0

        assertFalse(generation.ifCurrent(oldToken) { reopened++ })
        assertTrue(generation.ifCurrent(newToken) { reopened++ })
        assertEquals(1, reopened)
    }
}
