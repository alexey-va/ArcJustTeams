package ru.ruscrafting.justteams

import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.util.UUID

internal data class ElitePartyAssembly(
    val ready: Boolean,
    val memberIds: List<UUID>,
)

/** Exact EliteMobs 10.8 integration boundary; method discovery happens once at startup. */
internal class EliteMobsPartyBridge private constructor(
    private val isInPartyMethod: Method,
    private val createMethod: Method,
    private val inviteMethod: Method,
    private val acceptMethod: Method,
    private val getPartyMethod: Method,
    private val getMembersMethod: Method,
) {
    fun assemble(leader: Player, members: Collection<Player>): ElitePartyAssembly {
        if (!isInParty(leader.uniqueId)) createMethod.invoke(null, leader)
        if (!isInParty(leader.uniqueId)) return ElitePartyAssembly(false, emptyList())

        members.distinctBy(Player::getUniqueId).forEach { member ->
            if (member.uniqueId == leader.uniqueId || sameParty(leader.uniqueId, member.uniqueId)) return@forEach
            if (!member.isOnline || member.hasMetadata("NPC") || !member.hasPermission("elitemobs.party") ||
                isInParty(member.uniqueId) || partyMembers(leader.uniqueId).size >= MAX_PARTY_MEMBERS
            ) {
                return@forEach
            }
            inviteMethod.invoke(null, leader, member.name)
            acceptMethod.invoke(null, member)
        }
        val party = getPartyMethod.invoke(null, leader.uniqueId)
        return ElitePartyAssembly(
            ready = party != null,
            memberIds = if (party == null) emptyList() else uuidList(getMembersMethod.invoke(party)),
        )
    }

    private fun isInParty(playerId: UUID): Boolean = isInPartyMethod.invoke(null, playerId) == true

    private fun sameParty(first: UUID, second: UUID): Boolean {
        val firstParty = getPartyMethod.invoke(null, first) ?: return false
        return firstParty === getPartyMethod.invoke(null, second)
    }

    private fun partyMembers(playerId: UUID): List<UUID> {
        val party = getPartyMethod.invoke(null, playerId) ?: return emptyList()
        return uuidList(getMembersMethod.invoke(party))
    }

    private fun uuidList(value: Any?): List<UUID> =
        (value as? Collection<*>)?.mapNotNull { it as? UUID }.orEmpty()

    companion object {
        private const val MAX_PARTY_MEMBERS = 5

        fun load(): EliteMobsPartyBridge? = runCatching {
            val manager = Class.forName("com.magmaguy.elitemobs.parties.PartyManager")
            val party = Class.forName("com.magmaguy.elitemobs.parties.Party")
            EliteMobsPartyBridge(
                isInPartyMethod = manager.getMethod("isInParty", UUID::class.java),
                createMethod = manager.getMethod("create", Player::class.java),
                inviteMethod = manager.getMethod("invite", Player::class.java, String::class.java),
                acceptMethod = manager.getMethod("accept", Player::class.java),
                getPartyMethod = manager.getMethod("getParty", UUID::class.java),
                getMembersMethod = party.getMethod("getMembers"),
            )
        }.getOrNull()
    }
}
