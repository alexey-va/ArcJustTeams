package ru.ruscrafting.justteams

import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.UUID

class TeamDungeonTrackerTest {
    @Test
    fun `counts distinct members and only qualifying teams`() {
        val first = UUID.randomUUID()
        val participants = listOf(
            TeamParticipation(first, 7),
            TeamParticipation(first, 7),
            TeamParticipation(UUID.randomUUID(), 7),
            TeamParticipation(UUID.randomUUID(), 9),
        )

        assertEquals(setOf(7), qualifyingTeamIds(participants, 2))
        assertEquals(emptySet(), qualifyingTeamIds(participants, 3))
    }
}
