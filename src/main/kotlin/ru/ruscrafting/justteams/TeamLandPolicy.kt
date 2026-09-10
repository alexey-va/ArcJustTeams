package ru.ruscrafting.justteams

import java.util.UUID

internal object TeamLandPolicy {
    fun missingMembers(ownerId: UUID, trusted: Set<UUID>, teamMembers: Set<UUID>): Set<UUID> =
        teamMembers.filterTo(linkedSetOf()) { it != ownerId && it !in trusted }
}
