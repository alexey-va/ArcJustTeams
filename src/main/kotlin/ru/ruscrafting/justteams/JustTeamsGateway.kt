package ru.ruscrafting.justteams

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.paper.player.PaperOfflinePlayerData

internal data class TeamSnapshot(
    val raw: Any,
    val id: Int,
    val name: String,
    val tag: String,
    val description: String,
    val ownerId: UUID,
    val tier: Int,
    val points: Long,
    val balance: Double,
    val memberCount: Int,
    val onlineCount: Int,
    val memberIds: Set<UUID>,
    val landIds: Set<String>,
    val dungeonRuns: Long,
    val public: Boolean,
    val pvp: Boolean,
    val glow: Boolean,
    val acceptsAllyRequests: Boolean,
    val allies: List<Int>,
    val sentAllyRequests: List<Int>,
    val receivedAllyRequests: List<Int>,
    val activeBuffs: Set<String>,
)

internal data class TeamLandMembership(
    val raw: Any, val id: Int, val landIds: Set<String>, val memberIds: Set<UUID>, val landRevision: Long,
)

internal data class TeamMemberSnapshot(val id: UUID, val name: String, val role: String, val online: Boolean)
internal data class TeamJoinRequestSnapshot(val id: UUID, val name: String)
internal data class TeamBrowseSnapshot(val name: String, val tag: String, val members: Int, val public: Boolean)
internal data class TeamUpgradeSnapshot(
    val enabled: Boolean,
    val currentTier: Int,
    val nextTier: Int?,
    val maxMembers: Int,
    val nextMaxMembers: Int?,
    val moneyCost: Double,
    val pointsCost: Long,
    val formattedMoneyCost: String?,
)

internal data class TeamQuestSnapshot(
    val id: String,
    val name: String,
    val description: String,
    val progress: Long,
    val required: Long,
    val completed: Boolean,
    val claimed: Boolean,
    val rewardPoints: Long,
    val rewardMoney: Double,
)

internal data class TeamBuffSnapshot(val display: String, val active: Boolean, val pointsCost: Long)

internal enum class UpgradeAttempt { SUCCESS, DISABLED, NOT_OWNER, MAXIMUM, NOT_ENOUGH_MONEY, NOT_ENOUGH_POINTS, FAILED }

/** Reflection is deliberate: justTeams has no stable published API artifact. */
internal class JustTeamsGateway(
    private val eliteQuestId: String,
    private val nativeWork: BudgetedTaskQueue,
    private val worker: BoundedAsyncExecutor,
    private val offlineData: PaperOfflinePlayerData,
) {
    private val justTeams: Any = requireNotNull(Bukkit.getPluginManager().getPlugin("justTeams"))
    private val teamManager: Any = justTeams.call("getTeamManager")!!
    private val upgradeManager: Any = justTeams.call("getTeamUpgradeManager")!!
    private val questManager: Any? = justTeams.call("getQuestManager")
    private val buffManager: Any? = justTeams.call("getTeamBuffManager")
    private val logger = Bukkit.getLogger()
    private val landRevisions = mutableMapOf<Int, Long>()

    fun team(playerId: UUID): TeamSnapshot? = teamManager.call("getPlayerTeamCached", playerId)?.let(::snapshot)

    fun teamFor(playerId: UUID): Any? = teamManager.call("getPlayerTeamCached", playerId)

    fun teamId(team: Any): Int = team.call("getId") as Int

    /** justTeams 2.6.9 uses this storage load in its own async browse flow; native consumers resume on Main. */
    fun rawTeamsAsync(): CompletableFuture<List<Any>> = worker.submit {
        (teamManager.call("getAllTeams") as? Collection<*>)?.filterNotNull().orEmpty()
    }

    fun browseTeamsAsync(): CompletableFuture<List<TeamBrowseSnapshot>> {
        return rawTeamsAsync().thenCompose { loaded ->
            nativeWork.call({ true }) {
                val teams = loaded.iterator()
                val captured = arrayListOf<TeamBrowseSnapshot>()
                nativeWork.submit({ true }) {
                    if (teams.hasNext()) {
                        val team = teams.next()
                        captured += TeamBrowseSnapshot(team.call("getPlainName") as String, team.call("getPlainTag") as String,
                            (team.call("getMembers") as? Collection<*>)?.size ?: 0, team.call("isPublic") == true)
                    }
                    !teams.hasNext()
                }.thenApply { captured }
            }.thenCompose { it }
        }.thenApplyAsync({ captured -> captured.sortedWith(compareByDescending<TeamBrowseSnapshot> { it.public }.thenByDescending { it.members }.thenBy { it.name.lowercase() }) }, worker)
    }

    fun incrementDungeonRuns(team: Any) {
        val current = custom(team, DUNGEON_RUNS)?.toLongOrNull() ?: 0
        team.call("setCustomData", DUNGEON_RUNS, (current + 1).toString())
        val questId = eliteQuestId.trim()
        if (questId.isNotEmpty()) questManager?.call("addCustomProgress", team, questId, 1L)
    }

    fun bindLand(team: Any, landId: String): Boolean {
        invalidateLandMembership(team)
        val ids = landIds(team) + landId
        team.call("setCustomData", LAND_IDS, ids.joinToString(","))
        team.call("setCustomData", LEGACY_LAND_ID, ids.first())
        return landId in landIds(team)
    }

    fun unbindLand(team: Any, landId: String): Boolean {
        invalidateLandMembership(team)
        val ids = landIds(team) - landId
        team.call("setCustomData", LAND_IDS, ids.joinToString(","))
        team.call("setCustomData", LEGACY_LAND_ID, ids.firstOrNull().orEmpty())
        return landId !in landIds(team)
    }

    fun landLinkedElsewhere(teamId: Int, landId: String): CompletableFuture<Boolean> =
        rawTeamsAsync().thenApplyAsync({ loaded ->
            loaded.any { team -> this.teamId(team) != teamId && landId in landIds(team) }
        }, worker)

    fun landMembershipAsync(team: Any): CompletableFuture<TeamLandMembership> {
        val id = teamId(team)
        val revision = landRevisions[id] ?: 0L
        val members = rawMembers(team).iterator()
        val ids = linkedSetOf<UUID>()
        return nativeWork.submit({ true }) {
            if (members.hasNext()) { ids += members.next().call("getPlayerUuid") as UUID }
            return@submit !members.hasNext()
        }.thenCompose { worker.submit { TeamLandMembership(team, id, landIds(team), ids.toSet(), revision) } }
    }

    /** A fresh storage check, awaited before each new trust; no Bukkit/Lands access on the worker. */
    fun landStillLinkedAsync(membership: TeamLandMembership, landId: String): CompletableFuture<Boolean> =
        worker.submit { landId in landIds(membership.raw) }

    /** Main-thread fence, checked again after storage preparation immediately before the native trust. */
    fun mayTrust(membership: TeamLandMembership, landId: String, member: UUID): Boolean =
        landId in membership.landIds && (landRevisions[membership.id] ?: 0L) == membership.landRevision &&
            teamFor(member)?.let(::teamId) == membership.id

    private fun invalidateLandMembership(team: Any) {
        val id = teamId(team)
        landRevisions[id] = (landRevisions[id] ?: 0L) + 1L
    }

    fun membersAsync(team: Any): CompletableFuture<List<TeamMemberSnapshot>> {
        val pending = rawMembers(team).iterator()
        val captured = mutableListOf<TeamMemberSnapshot>()
        val cached = mutableSetOf<UUID>()
        return nativeWork.submit({ true }) {
            if (!pending.hasNext()) true else {
                val member = pending.next()
                val id = member.call("getPlayerUuid") as UUID
                @Suppress("DEPRECATION")
                val name = Bukkit.getOfflinePlayer(id).playerProfile.name?.takeIf(String::isNotEmpty)
                if (name != null) cached += id
                captured += TeamMemberSnapshot(id, name ?: id.toString().take(8),
                    (member.call("getRole") as Enum<*>).name.lowercase(), member.call("isOnline") == true)
                false
            }
        }.thenApplyAsync({
            val persisted = offlineData.names(captured.asSequence().map { it.id }.filter { it !in cached }.asIterable())
            reportNameFailures(persisted)
            captured.map { member -> persisted.names[member.id]?.let { member.copy(name = it) } ?: member }
                .sortedWith(compareByDescending<TeamMemberSnapshot> { it.online }.thenBy { it.name.lowercase() })
        }, worker)
    }

    fun joinRequestsAsync(team: TeamSnapshot): CompletableFuture<List<TeamJoinRequestSnapshot>> {
        val ids = joinRequests(team)
        val pending = ids.iterator()
        val cached = linkedMapOf<UUID, String>()
        return nativeWork.submit({ true }) {
            if (!pending.hasNext()) true else {
                val id = pending.next()
                @Suppress("DEPRECATION")
                val name = Bukkit.getOfflinePlayer(id).playerProfile.name
                name?.takeIf(String::isNotEmpty)?.let { cached[id] = it }
                false
            }
        }.thenApplyAsync({
            val persisted = offlineData.names(ids.filter { it !in cached })
            reportNameFailures(persisted)
            ids.map { id -> TeamJoinRequestSnapshot(id, cached[id] ?: persisted.names[id] ?: id.toString().take(8)) }
        }, worker)
    }

    private fun reportNameFailures(names: PaperOfflinePlayerData.Names) {
        if (names.failures.isNotEmpty()) logger.warning("Could not read ${names.failures.size} offline team player names; UUIDs remain available")
    }

    fun joinRequests(team: TeamSnapshot): List<UUID> =
        (team.raw.call("getJoinRequests") as? Collection<*>)?.filterIsInstance<UUID>().orEmpty()

    fun elevated(team: TeamSnapshot, playerId: UUID): Boolean = team.raw.call("hasElevatedPermissions", playerId) == true

    fun upgrade(team: Any): TeamUpgradeSnapshot {
        val tier = team.call("getTier") as Int
        val enabled = upgradeManager.call("isEnabled") == true
        val next = if (enabled && upgradeManager.call("canUpgrade", tier) == true) tier + 1 else null
        val usesMoney = upgradeManager.call("usesMoney") == true
        val usesPoints = upgradeManager.call("usesPoints") == true
        val moneyCost = if (next != null && usesMoney) upgradeManager.call("getUpgradeCost", tier) as Double else 0.0
        val pointsCost = if (next != null && usesPoints) upgradeManager.call("getUpgradePointsCost", tier) as Long else 0L
        return TeamUpgradeSnapshot(
            enabled = enabled,
            currentTier = tier,
            nextTier = next,
            maxMembers = upgradeManager.call("getMaxMembers", tier) as Int,
            nextMaxMembers = next?.let { upgradeManager.call("getMaxMembers", it) as Int },
            moneyCost = moneyCost,
            pointsCost = pointsCost,
            formattedMoneyCost = moneyCost.takeIf { it > 0.0 }?.let { teamManager.call("formatCurrency", it) as String },
        )
    }

    fun tryUpgrade(player: Player): UpgradeAttempt {
        val team = team(player.uniqueId) ?: return UpgradeAttempt.FAILED
        val upgrade = upgrade(team.raw)
        if (!upgrade.enabled) return UpgradeAttempt.DISABLED
        if (team.ownerId != player.uniqueId) return UpgradeAttempt.NOT_OWNER
        if (upgrade.nextTier == null) return UpgradeAttempt.MAXIMUM
        if (team.balance < upgrade.moneyCost) return UpgradeAttempt.NOT_ENOUGH_MONEY
        if (team.points < upgrade.pointsCost) return UpgradeAttempt.NOT_ENOUGH_POINTS
        return if (teamManager.call("tryUpgradeTeamTier", player) == true) UpgradeAttempt.SUCCESS else UpgradeAttempt.FAILED
    }

    fun create(player: Player, name: String, tag: String): String? {
        if (teamManager.call("validateTeamName", name) != null) return "creation.invalid-name"
        if (teamManager.call("validateTagInput", tag) != null || teamManager.call("isTagTaken", tag) == true) return "creation.invalid-tag"
        teamManager.call("createTeam", player, name, tag)
        return null
    }

    fun join(player: Player, teamName: String) = teamManager.call("joinTeam", player, teamName)
    fun invite(player: Player, target: Player) = teamManager.call("invitePlayer", player, target)
    fun kick(player: Player, target: UUID) = teamManager.call("kickPlayerDirect", player, target)
    fun promote(player: Player, target: UUID) = teamManager.call("promotePlayer", player, target)
    fun demote(player: Player, target: UUID) = teamManager.call("demotePlayer", player, target)
    fun leave(player: Player) = teamManager.call("leaveTeam", player)
    fun disband(player: Player) = teamManager.call("disbandTeam", player)
    fun acceptJoinRequest(team: TeamSnapshot, target: UUID) = teamManager.call("acceptJoinRequest", team.raw, target)
    fun denyJoinRequest(team: TeamSnapshot, target: UUID) = teamManager.call("denyJoinRequest", team.raw, target)

    fun setTag(player: Player, value: String): String? {
        if (teamManager.call("validateTagInput", value) != null || teamManager.call("isTagTaken", value) == true) return "settings.invalid-tag"
        teamManager.call("setTeamTag", player, value)
        return null
    }

    fun setDescription(player: Player, value: String): String? {
        val maximum = justTeams.call("getConfigManager")!!.call("getMaxDescriptionLength") as Int
        if (value.length > maximum) return "settings.invalid-description"
        teamManager.call("setTeamDescription", player, value)
        return null
    }

    fun togglePublic(player: Player) = teamManager.call("togglePublicStatus", player)
    fun togglePvp(player: Player) = teamManager.call("togglePvpStatus", player)
    fun toggleGlow(player: Player) = teamManager.call("toggleGlow", player)
    fun toggleAcceptRequests(player: Player) = teamManager.call("toggleAcceptRequests", player)

    fun teamName(id: Int): String = (teamManager.call("getTeamById", id) as? Optional<*>)?.orElse(null)?.let {
        it.call("getPlainName") as String
    } ?: "#$id"

    fun sendAllyRequest(player: Player, name: String) = teamManager.call("sendAllyRequest", player, name)
    fun acceptAllyRequest(player: Player, id: Int) = teamManager.call("acceptAllyRequest", player, id)
    fun denyAllyRequest(player: Player, id: Int) = teamManager.call("denyAllyRequest", player, id)
    fun removeAlly(player: Player, name: String) = teamManager.call("removeAlly", player, name)

    fun quests(team: TeamSnapshot): List<TeamQuestSnapshot> {
        val manager = questManager ?: return emptyList()
        if (manager.call("isEnabled") != true) return emptyList()
        val progress = (manager.call("getProgress", team.id) as? Map<*, *>).orEmpty()
        return progress.entries.mapNotNull { (id, rawProgress) ->
            val questId = id as? String ?: return@mapNotNull null
            val state = rawProgress ?: return@mapNotNull null
            val quest = manager.call("getQuest", questId) ?: return@mapNotNull null
            TeamQuestSnapshot(
                id = questId,
                name = plain(quest.call("getDisplayName") as String),
                description = (quest.call("getDescription") as? Collection<*>)?.joinToString(" ") { plain(it.toString()) }.orEmpty(),
                progress = state.call("getProgress") as Long,
                required = quest.call("getRequired") as Long,
                completed = state.call("isCompleted") == true,
                claimed = state.call("isClaimed") == true,
                rewardPoints = quest.call("getRewardPoints") as Long,
                rewardMoney = quest.call("getRewardMoney") as Double,
            )
        }.sortedWith(compareBy<TeamQuestSnapshot> { it.claimed }.thenByDescending { it.completed }.thenBy { it.name.lowercase() })
    }

    fun claimQuest(player: Player, teamId: Int, questId: String): Boolean =
        questManager?.call("claimReward", teamId, questId, player) == true

    fun buffs(team: TeamSnapshot): List<TeamBuffSnapshot> {
        val manager = buffManager ?: return emptyList()
        if (manager.call("isEnabled") != true) return emptyList()
        val pool = (manager.call("getPool") as? Map<*, *>).orEmpty()
        return pool.entries.mapNotNull { (id, raw) ->
            val key = id as? String ?: return@mapNotNull null
            val buff = raw ?: return@mapNotNull null
            TeamBuffSnapshot(
                display = plain(buff.field("display") as String),
                active = key in team.activeBuffs,
                pointsCost = (buff.field("pointsCost") as Number).toLong(),
            )
        }.sortedWith(compareByDescending<TeamBuffSnapshot> { it.active }.thenBy { it.display.lowercase() })
    }

    fun buffLimits(): Triple<Int, Int, Int> {
        val manager = buffManager ?: return Triple(0, 0, 0)
        return Triple(
            manager.call("getMaxActive") as Int,
            manager.call("getRequiredNearby") as Int,
            manager.call("getRadius") as Int,
        )
    }

    fun snapshot(team: Any): TeamSnapshot {
        val members = rawMembers(team)
        return TeamSnapshot(
            raw = team,
            id = team.call("getId") as Int,
            name = team.call("getPlainName") as String,
            tag = team.call("getPlainTag") as String,
            description = team.call("getDescription") as String,
            ownerId = team.call("getOwnerUuid") as UUID,
            tier = team.call("getTier") as Int,
            points = team.call("getPoints") as Long,
            balance = team.call("getBalance") as Double,
            memberCount = members.size,
            onlineCount = members.count { it.call("isOnline") == true },
            memberIds = members.mapTo(linkedSetOf()) { it.call("getPlayerUuid") as UUID },
            landIds = landIds(team),
            dungeonRuns = custom(team, DUNGEON_RUNS)?.toLongOrNull() ?: 0,
            public = team.call("isPublic") == true,
            pvp = team.call("isPvpEnabled") == true,
            glow = team.call("isGlowEnabled") == true,
            acceptsAllyRequests = team.call("acceptsRequests") == true,
            allies = intList(team.call("getAllies")),
            sentAllyRequests = intList(team.call("getSentAllyRequests")),
            receivedAllyRequests = intList(team.call("getReceivedAllyRequests")),
            activeBuffs = (team.call("getActiveBuffs") as? Collection<*>)?.mapTo(linkedSetOf()) { it.toString() }.orEmpty(),
        )
    }

    private fun rawMembers(team: Any): List<Any> =
        (team.call("getMembers") as? Collection<*>)?.filterNotNull().orEmpty()

    private fun landIds(team: Any): Set<String> = buildSet {
        custom(team, LAND_IDS)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.let(::addAll)
        custom(team, LEGACY_LAND_ID)?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
    }

    private fun custom(team: Any, key: String): String? =
        ((team.call("getCustomData", key) as? Optional<*>)?.orElse(null) as? String)

    private fun intList(value: Any?): List<Int> = (value as? Collection<*>)?.mapNotNull { (it as? Number)?.toInt() }.orEmpty()

    private fun plain(value: String): String = value.replace(Regex("<[^>]+>"), "").replace(Regex("&[0-9a-fk-or]", RegexOption.IGNORE_CASE), "")

    private fun Any.call(name: String, vararg args: Any): Any? {
        val key = MethodKey(javaClass, name, args.map { it.javaClass })
        val method = methods.computeIfAbsent(key) {
            javaClass.methods.firstOrNull { candidate ->
                candidate.name == name && candidate.parameterCount == args.size &&
                    candidate.parameterTypes.zip(args).all { (type, value) -> type.boxed().isInstance(value) }
            } ?: error("Unsupported justTeams method: ${javaClass.name}#$name/${args.size}")
        }
        return method.invoke(this, *args)
    }

    private fun Any.field(name: String): Any? = fields.computeIfAbsent(FieldKey(javaClass, name)) { javaClass.getField(name) }.get(this)

    private fun Class<*>.boxed(): Class<*> = when (this) {
        java.lang.Integer.TYPE -> Int::class.javaObjectType
        java.lang.Long.TYPE -> Long::class.javaObjectType
        java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
        java.lang.Double.TYPE -> Double::class.javaObjectType
        else -> this
    }

    companion object {
        private data class MethodKey(val owner: Class<*>, val name: String, val arguments: List<Class<*>>)
        private data class FieldKey(val owner: Class<*>, val name: String)
        private val methods = ConcurrentHashMap<MethodKey, Method>()
        private val fields = ConcurrentHashMap<FieldKey, Field>()
        const val LAND_IDS = "arcjustteams.land_ids"
        const val LEGACY_LAND_ID = "arcjustteams.land_id"
        const val DUNGEON_RUNS = "arcjustteams.dungeons_completed"
    }
}
