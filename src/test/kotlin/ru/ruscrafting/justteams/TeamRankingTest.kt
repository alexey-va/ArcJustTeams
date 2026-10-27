package ru.ruscrafting.justteams

import kotlin.test.Test
import kotlin.test.assertEquals

class TeamRankingTest {
    @Test
    fun `points then level and membership define top order independently of public joining`() {
        val clans = listOf(
            TeamBrowseSnapshot("Большой", "BIG", 30, true, id = 1, points = 50, tier = 2),
            TeamBrowseSnapshot("Лидер", "TOP", 3, false, id = 2, points = 300, tier = 1),
            TeamBrowseSnapshot("Уровень", "LVL", 2, true, id = 3, points = 50, tier = 3),
            TeamBrowseSnapshot("Малый", "SM", 10, false, id = 4, points = 50, tier = 2),
        )
        assertEquals(listOf(2, 3, 1, 4), clans.sortedWith(TEAM_RANKING).map { it.id })
    }
}
