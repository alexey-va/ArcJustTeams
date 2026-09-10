package ru.ruscrafting.justteams

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class TeamLandPolicyTest {
    @Test
    fun `only missing team members are added and nobody is removed`() {
        val owner = UUID.randomUUID()
        val existingTeamMember = UUID.randomUUID()
        val missingTeamMember = UUID.randomUUID()
        val manualResident = UUID.randomUUID()

        assertEquals(
            setOf(missingTeamMember),
            TeamLandPolicy.missingMembers(
                owner,
                setOf(existingTeamMember, manualResident),
                setOf(owner, existingTeamMember, missingTeamMember),
            ),
        )
    }
}
