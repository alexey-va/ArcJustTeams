package ru.ruscrafting.justteams

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.Optional
import java.util.UUID

internal data class TeamSnapshot(
    val raw: Any,
    val id: Int,
    val name: String,
    val tag: String,
    val ownerId: UUID,
    val tier: Int,
    val points: Long,
    val memberCount: Int,
    val onlineCount: Int,
    val landId: String?,
    val dungeonRuns: Long,
)

internal data class TeamMemberSnapshot(val name: String, val role: String, val online: Boolean)
internal data class TeamUpgradeSnapshot(val currentTier: Int, val nextTier: Int?, val maxMembers: Int, val nextMaxMembers: Int?, val cost: String?)

/** Reflection is deliberate: justTeams 2.6.7 has no stable published API artifact. */
internal class JustTeamsGateway(private val eliteQuestId: String) {
    private val justTeams: Any = requireNotNull(Bukkit.getPluginManager().getPlugin("justTeams"))
    private val teamManager: Any = justTeams.call("getTeamManager")!!
    private val questManager: Any? = justTeams.call("getQuestManager")

    fun team(playerId: UUID): TeamSnapshot? = teamManager.call("getPlayerTeamCached", playerId)?.let(::snapshot)

    fun teamFor(playerId: UUID): Any? = teamManager.call("getPlayerTeamCached", playerId)

    fun teamId(team: Any): Int = team.call("getId") as Int

    fun incrementDungeonRuns(team: Any) {
        // ponytail: read/write is enough for rare completions; move to an atomic shared store if concurrent cross-server runs become common.
        val current = custom(team, DUNGEON_RUNS)?.toLongOrNull() ?: 0
        team.call("setCustomData", DUNGEON_RUNS, (current + 1).toString())
        val questId = eliteQuestId.trim()
        if (questId.isNotEmpty()) questManager?.call("addCustomProgress", team, questId, 1L)
    }

    fun bindLand(team: Any, landId: String) {
        team.call("setCustomData", LAND_ID, landId)
    }

    fun members(team: Any): List<TeamMemberSnapshot> =
        ((team.call("getMembers") as? Collection<*>)?.filterNotNull().orEmpty()).map { member ->
            val id = member.call("getPlayerUuid") as UUID
            @Suppress("DEPRECATION")
            TeamMemberSnapshot(
                name = Bukkit.getOfflinePlayer(id).name ?: id.toString().take(8),
                role = (member.call("getRole") as Enum<*>).name.lowercase(),
                online = member.call("isOnline") == true,
            )
        }.sortedWith(compareByDescending<TeamMemberSnapshot> { it.online }.thenBy { it.name.lowercase() })

    fun upgrade(team: Any): TeamUpgradeSnapshot {
        val manager = justTeams.call("getTeamUpgradeManager")!!
        val tier = team.call("getTier") as Int
        val next = if (manager.call("canUpgrade", tier) == true) tier + 1 else null
        val usesMoney = manager.call("usesMoney") == true
        val cost = next?.let {
            if (usesMoney) teamManager.call("formatCurrency", manager.call("getUpgradeCost", tier) as Double) as String
            else (manager.call("getUpgradePointsCost", tier) as Long).toString()
        }
        return TeamUpgradeSnapshot(
            currentTier = tier,
            nextTier = next,
            maxMembers = manager.call("getMaxMembers", tier) as Int,
            nextMaxMembers = next?.let { manager.call("getMaxMembers", it) as Int },
            cost = cost,
        )
    }

    fun tryUpgrade(player: Player) {
        teamManager.call("tryUpgradeTeamTier", player)
    }

    fun openNative(player: Player, section: String? = null) {
        val suffix = section?.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
        player.performCommand("team$suffix")
    }

    private fun snapshot(team: Any): TeamSnapshot {
        val members = (team.call("getMembers") as? Collection<*>)?.filterNotNull().orEmpty()
        return TeamSnapshot(
            raw = team,
            id = team.call("getId") as Int,
            name = team.call("getPlainName") as String,
            tag = team.call("getPlainTag") as String,
            ownerId = team.call("getOwnerUuid") as UUID,
            tier = team.call("getTier") as Int,
            points = team.call("getPoints") as Long,
            memberCount = members.size,
            onlineCount = members.count { it.call("isOnline") == true },
            landId = custom(team, LAND_ID),
            dungeonRuns = custom(team, DUNGEON_RUNS)?.toLongOrNull() ?: 0,
        )
    }

    private fun custom(team: Any, key: String): String? =
        ((team.call("getCustomData", key) as? Optional<*>)?.orElse(null) as? String)

    private fun Any.call(name: String, vararg args: Any): Any? {
        val method = javaClass.methods.firstOrNull { candidate ->
            candidate.name == name && candidate.parameterCount == args.size &&
                candidate.parameterTypes.zip(args).all { (type, value) -> type.boxed().isInstance(value) }
        } ?: error("Unsupported justTeams 2.6.7 method: ${javaClass.name}#$name/${args.size}")
        return method.invoke(this, *args)
    }

    private fun Class<*>.boxed(): Class<*> = when (this) {
        java.lang.Integer.TYPE -> Int::class.javaObjectType
        java.lang.Long.TYPE -> Long::class.javaObjectType
        java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
        java.lang.Double.TYPE -> Double::class.javaObjectType
        else -> this
    }

    companion object {
        const val LAND_ID = "arcjustteams.land_id"
        const val DUNGEON_RUNS = "arcjustteams.dungeons_completed"
    }
}
