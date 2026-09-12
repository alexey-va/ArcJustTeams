package ru.ruscrafting.justteams

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import java.text.DecimalFormat
import java.util.UUID

internal class TeamDialogs(
    private val runtime: PaperDialogRuntime,
    private val texts: Texts,
    private val teams: JustTeamsGateway,
    private val lands: LandsBridge?,
    private val landReconciler: TeamLandReconciler?,
    private val tasks: LifecycleTaskScope,
    private val minimumDungeonMembers: Int,
    private val dungeonParties: DungeonPartyCoordinator,
) {
    private val numbers = DecimalFormat("#,##0.##")
    private val visitGenerations = mutableMapOf<UUID, DialogVisitGeneration>()

    fun begin(player: Player) {
        visitGeneration(player).invalidate()
        runtime.beginFlow(player)
        open(player)
    }

    fun open(player: Player, notice: String? = null) {
        val team = teams.team(player.uniqueId)
        if (team == null) return openEmpty(player, notice)
        landReconciler?.reconcile(team)
        val rows = listOf(
            texts.get(player, "summary.name") to value("[${team.tag}] ${team.name}"),
            texts.get(player, "summary.tier") to value(team.tier),
            texts.get(player, "summary.points") to value(team.points),
            texts.get(player, "summary.members") to value("${team.onlineCount}/${team.memberCount}"),
            texts.get(player, "summary.lands") to value(team.landIds.size),
            texts.get(player, "summary.dungeons") to value(team.dungeonRuns),
        )
        show(player, PaperDialogScreen(
            id = "arcjustteams.root",
            title = texts.get(player, "title"),
            body = listOfNotNull(noticeBody(player, notice), DialogTextLayout.modelBody(texts.get(player, "intro")),
                DialogTables.body(rows, frame = DialogTables.Frame.EPIC, width = 320)),
            buttons = listOf(
                button("members", player, "nav.members", "nav.members-tooltip") { openMembers(player) },
                button("quests", player, "nav.quests", "nav.quests-tooltip") { openQuests(player) },
                button("upgrades", player, "nav.upgrades", "nav.upgrades-tooltip") { openUpgrades(player) },
                button("buffs", player, "nav.buffs", "nav.buffs-tooltip") { openBuffs(player) },
                button("lands", player, "nav.lands", "nav.lands-tooltip") { openLands(player) },
                button("dungeons", player, "nav.dungeons", "nav.dungeons-tooltip") { openDungeons(player) },
                button("allies", player, "nav.allies", "nav.allies-tooltip") { openAllies(player) },
                button("settings", player, "nav.settings", "nav.settings-tooltip") { openSettings(player) },
            ),
            exitButton = close(player), columns = 2,
        ), reopen = { open(player) })
    }

    private fun openEmpty(player: Player, notice: String?) {
        show(player, PaperDialogScreen(
            id = "arcjustteams.empty",
            title = texts.get(player, "title"),
            body = listOfNotNull(noticeBody(player, notice), DialogTextLayout.modelBody(texts.get(player, "no-team"))),
            buttons = listOf(
                button("create", player, "nav.create", "nav.create-tooltip") { openCreate(player) },
                button("browse", player, "nav.browse", "nav.browse-tooltip") { openBrowse(player) },
            ),
            exitButton = close(player), columns = 2,
        ), reopen = { openEmpty(player, null) })
    }

    private fun openCreate(player: Player, notice: String? = null, initialName: String = "", initialTag: String = "", pending: Boolean = false) {
        show(player, PaperDialogScreen(
            id = "arcjustteams.create",
            title = texts.get(player, "creation.title"),
            body = listOfNotNull(noticeBody(player, notice), DialogTextLayout.modelBody(texts.get(player, "creation.intro"))),
            inputs = listOf(
                PaperDialogTextInput(NAME_INPUT, texts.get(player, "creation.name"), initialName, 300, 16),
                PaperDialogTextInput(TAG_INPUT, texts.get(player, "creation.tag"), initialTag, 300, 6),
            ),
            buttons = listOf(contextButton("create_submit", player, "creation.submit") { context ->
                val submittedName = context.text(NAME_INPUT).orEmpty().trim()
                val submittedTag = context.text(TAG_INPUT).orEmpty().trim()
                val error = teams.create(player, submittedName, submittedTag)
                if (error != null) openCreate(player, error, submittedName, submittedTag)
                else {
                    openCreate(player, initialName = submittedName, initialTag = submittedTag, pending = true)
                    runLaterOpen(player, 20L) {
                        open(player, if (teams.team(player.uniqueId) == null) "creation.failed" else "creation.created")
                    }
                }
            }),
            exitButton = footer(player), columns = 1,
        ), pending = pending)
    }

    private fun openBrowse(player: Player, requestedPage: Int = 0, pending: Boolean = false) {
        val all = teams.browseTeams()
        val pages = maxOf(1, (all.size + 7) / 8)
        val page = requestedPage.coerceIn(0, pages - 1)
        val listed = all.drop(page * 8).take(8)
        val rows = listed.map { team ->
            value("[${team.tag}] ${team.name}") to texts.get(player, if (team.public) "browse.public" else "browse.private",
                mapOf("members" to team.members))
        }
        val buttons = buildList {
            listed.forEachIndexed { index, team ->
                add(button("join_$index", player, "browse.join", values = mapOf("team" to team.name)) {
                    teams.join(player, team.name)
                    openBrowse(player, page, pending = true)
                    runLaterOpen(player, 20L) {
                        open(player, if (teams.team(player.uniqueId) == null) "browse.requested" else "browse.joined")
                    }
                })
            }
            paging(player, page, pages, { openBrowse(player, it) }, this)
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.browse", title = texts.get(player, "browse.title"),
            body = listOf(DialogTextLayout.modelBody(texts.get(player, if (listed.isEmpty()) "browse.empty" else "browse.intro",
                mapOf("page" to page + 1, "pages" to pages))), DialogTables.body(rows, frame = DialogTables.Frame.EPIC, width = 320)),
            buttons = buttons, exitButton = footer(player), columns = 2,
        ), reopen = { openBrowse(player, page) }, pending = pending)
    }

    private fun openMembers(player: Player, requestedPage: Int = 0, notice: String? = null) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val members = teams.members(team.raw)
        val pages = maxOf(1, (members.size + 7) / 8)
        val page = requestedPage.coerceIn(0, pages - 1)
        val listed = members.drop(page * 8).take(8)
        val rows = listed.map { member ->
            val state = if (member.online) "●" else "○"
            Component.text("$state ${member.name}", if (member.online) NamedTextColor.GREEN else NamedTextColor.WHITE) to
                texts.get(player, "members.roles.${member.role}")
        }
        val buttons = buildList {
            listed.forEachIndexed { index, member ->
                add(button("member_$index", player, "members.open", values = mapOf("player" to member.name)) {
                    openMember(player, member.id)
                })
            }
            if (teams.elevated(team, player.uniqueId)) {
                add(button("invite", player, "members.invite") { openInvite(player) })
                add(button("requests", player, "members.requests") { openJoinRequests(player) })
            }
            paging(player, page, pages, { openMembers(player, it) }, this)
            if (team.ownerId == player.uniqueId) add(button("disband", player, "members.disband") { openDisband(player) })
            else add(button("leave", player, "members.leave") { openLeave(player) })
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.members", title = texts.get(player, "members.title"),
            body = listOfNotNull(noticeBody(player, notice), DialogTextLayout.modelBody(texts.get(player, "members.intro",
                mapOf("page" to page + 1, "pages" to pages))), DialogTables.body(rows, frame = DialogTables.Frame.EPIC, width = 320)),
            buttons = buttons, exitButton = footer(player), columns = 2,
        ), reopen = { openMembers(player, page) })
    }

    private fun openMember(player: Player, memberId: UUID) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val member = teams.members(team.raw).firstOrNull { it.id == memberId } ?: return openMembers(player)
        val canEdit = member.id != player.uniqueId && teams.elevated(team, player.uniqueId) && member.role != "owner"
        val buttons = if (!canEdit) emptyList() else buildList {
            if (team.ownerId == player.uniqueId && member.role == "member") add(button("promote", player, "members.promote") {
                teams.promote(player, member.id); openMembers(player, notice = "members.changed")
            })
            if (team.ownerId == player.uniqueId && member.role == "co_owner") add(button("demote", player, "members.demote") {
                teams.demote(player, member.id); openMembers(player, notice = "members.changed")
            })
            add(button("kick", player, "members.kick") { openKick(player, member) })
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.member", title = texts.get(player, "members.member-title", mapOf("player" to member.name)),
            body = listOf(DialogTables.body(listOf(
                texts.get(player, "members.player") to value(member.name),
                texts.get(player, "members.role") to texts.get(player, "members.roles.${member.role}"),
                texts.get(player, "members.status") to texts.get(player, if (member.online) "members.online" else "members.offline"),
            ), frame = DialogTables.Frame.EPIC, width = 300)), buttons = buttons, exitButton = footer(player), columns = 2,
        ), reopen = { openMember(player, memberId) })
    }

    private fun openInvite(player: Player, notice: String? = null, initial: String = "") {
        show(player, PaperDialogScreen(
            id = "arcjustteams.invite", title = texts.get(player, "members.invite-title"),
            body = listOfNotNull(noticeBody(player, notice), DialogTextLayout.modelBody(texts.get(player, "members.invite-intro"))),
            inputs = listOf(PaperDialogTextInput(PLAYER_INPUT, texts.get(player, "members.player-input"), initial, 300, 16)),
            buttons = listOf(contextButton("invite_submit", player, "members.invite-submit") { context ->
                val name = context.text(PLAYER_INPUT).orEmpty().trim()
                val target = Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(name, true) }
                if (target == null) openInvite(player, "members.player-offline", name)
                else { teams.invite(player, target); openMembers(player, notice = "members.invited") }
            }), exitButton = footer(player), columns = 1,
        ))
    }

    private fun openJoinRequests(player: Player) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val requests = teams.joinRequests(team)
        val buttons = requests.mapIndexed { index, id ->
            @Suppress("DEPRECATION") val name = Bukkit.getOfflinePlayer(id).name ?: id.toString().take(8)
            button("request_$index", player, "members.request", values = mapOf("player" to name)) { openJoinRequest(player, id, name) }
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.requests", title = texts.get(player, "members.requests-title"),
            body = listOf(DialogTextLayout.modelBody(texts.get(player, if (requests.isEmpty()) "members.requests-empty" else "members.requests-intro"))),
            buttons = buttons, exitButton = footer(player), columns = 2,
        ), reopen = { openJoinRequests(player) })
    }

    private fun openJoinRequest(player: Player, id: UUID, name: String) {
        show(player, PaperDialogScreen(
            id = "arcjustteams.request", title = texts.get(player, "members.request-title"),
            body = listOf(DialogTextLayout.modelBody(texts.get(player, "members.request-body", mapOf("player" to name)))),
            buttons = listOf(
                button("accept", player, "members.accept") {
                    teams.team(player.uniqueId)?.let { teams.acceptJoinRequest(it, id) }; openJoinRequests(player)
                },
                button("deny", player, "members.deny") {
                    teams.team(player.uniqueId)?.let { teams.denyJoinRequest(it, id) }; openJoinRequests(player)
                },
            ), exitButton = footer(player), columns = 2,
        ))
    }

    private fun openKick(player: Player, member: TeamMemberSnapshot) = confirm(player, "arcjustteams.kick", "members.kick-title",
        "members.kick-body", "members.kick-confirm", mapOf("player" to member.name)) {
        teams.kick(player, member.id); openMembers(player, notice = "members.changed")
    }

    private fun openLeave(player: Player, pending: Boolean = false) {
        confirm(player, "arcjustteams.leave", "members.leave-title", "members.leave-body",
            "members.leave-confirm", pending = pending) {
            teams.leave(player)
            openLeave(player, pending = true)
            runLaterOpen(player, 10L) { open(player) }
        }
    }

    private fun openDisband(player: Player, pending: Boolean = false) {
        confirm(player, "arcjustteams.disband", "members.disband-title", "members.disband-body",
            "members.disband-confirm", pending = pending) {
            teams.disband(player)
            openDisband(player, pending = true)
            runLaterOpen(player, 10L) { open(player) }
        }
    }

    private fun openUpgrades(player: Player, notice: String? = null) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val upgrade = teams.upgrade(team.raw)
        val rows = buildList {
            add(texts.get(player, "upgrades.level") to value(upgrade.currentTier))
            add(texts.get(player, "upgrades.members") to value(upgrade.maxMembers))
            if (upgrade.nextTier != null) {
                add(texts.get(player, "upgrades.next") to value(upgrade.nextTier))
                add(texts.get(player, "upgrades.next-members") to value(upgrade.nextMaxMembers ?: upgrade.maxMembers))
                if (upgrade.moneyCost > 0) add(texts.get(player, "upgrades.money-cost") to value(upgrade.formattedMoneyCost.orEmpty()))
                if (upgrade.pointsCost > 0) add(texts.get(player, "upgrades.points-cost") to value(upgrade.pointsCost))
            }
        }
        val buttons = if (upgrade.nextTier == null) emptyList() else listOf(
            button("upgrade_confirm", player, "upgrades.continue") { openUpgradeConfirm(player, team, upgrade) },
        )
        show(player, PaperDialogScreen(
            id = "arcjustteams.upgrades", title = texts.get(player, "upgrades.title"),
            body = listOfNotNull(noticeBody(player, notice), DialogTextLayout.modelBody(texts.get(player,
                if (!upgrade.enabled) "upgrades.disabled" else if (upgrade.nextTier == null) "upgrades.maximum" else "upgrades.intro")),
                DialogTables.body(rows, frame = DialogTables.Frame.EPIC, width = 320)),
            buttons = buttons, exitButton = footer(player), columns = 1,
        ), reopen = { openUpgrades(player) })
    }

    private fun openUpgradeConfirm(player: Player, team: TeamSnapshot, upgrade: TeamUpgradeSnapshot) {
        show(player, PaperDialogScreen(
            id = "arcjustteams.upgrades.confirm", title = texts.get(player, "upgrades.confirm-title"),
            body = listOf(DialogTextLayout.modelBody(texts.get(player, "upgrades.confirm", mapOf(
                "tier" to upgrade.nextTier.orEmpty(), "cost" to upgradeCost(player, upgrade),
            )))),
            buttons = listOf(button("upgrade_buy", player, "upgrades.buy") {
                val fresh = teams.team(player.uniqueId)
                val freshUpgrade = fresh?.takeIf { it.id == team.id }?.let { teams.upgrade(it.raw) }
                if (freshUpgrade == null || freshUpgrade.currentTier != upgrade.currentTier || freshUpgrade != upgrade) {
                    openUpgrades(player, "upgrades.changed")
                } else {
                    val result = teams.tryUpgrade(player)
                    openUpgrades(player, "upgrades.result.${result.name.lowercase()}")
                }
            }), exitButton = footer(player), columns = 1,
        ))
    }

    private fun openLands(player: Player, notice: String? = null) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val linked = lands?.linked(team.landIds).orEmpty()
        val owned = lands?.ownedLands(player).orEmpty()
        val rows = linked.map { land ->
            value(land.name) to texts.get(player, "settlements.row", mapOf("members" to land.members))
        }
        val buttons = owned.mapIndexed { index, land ->
            if (land.id in team.landIds) button("land_$index", player, "settlements.linked", values = mapOf("land" to land.name)) {
                openUnlinkLand(player, land)
            } else button("land_$index", player, "settlements.link", values = mapOf("land" to land.name), tooltipKey = "settlements.link-tooltip") {
                val fresh = teams.team(player.uniqueId)
                when {
                    fresh?.id != team.id || lands?.ownedBy(land.id, player.uniqueId) != true -> openLands(player, "settlements.changed")
                    teams.landLinkedElsewhere(team.id, land.id) -> openLands(player, "settlements.already-used")
                    !teams.bindLand(fresh.raw, land.id) -> openLands(player, "notice.bind-failed")
                    else -> {
                        val updated = teams.team(player.uniqueId) ?: fresh
                        val sync = landReconciler?.reconcile(updated) ?: LandSyncResult(0, 0)
                        openLands(player, if (sync.failed > 0) "notice.bound-partial" else "notice.bound")
                    }
                }
            }
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.lands", title = texts.get(player, "settlements.title"),
            body = buildList {
                noticeBody(player, notice)?.let(::add)
                add(DialogTextLayout.modelBody(texts.get(player, "settlements.intro")))
                if (lands == null) add(DialogTextLayout.modelBody(texts.get(player, "notice.integration-missing")))
                else if (linked.isEmpty()) add(DialogTextLayout.modelBody(texts.get(player, "settlements.none")))
                else add(DialogTables.body(rows, frame = DialogTables.Frame.LEGENDARY, width = 320))
                if (lands != null && owned.isEmpty()) add(DialogTextLayout.modelBody(texts.get(player, "settlements.empty")))
            }, buttons = buttons, exitButton = footer(player), columns = 2,
        ), reopen = { openLands(player) })
    }

    private fun openUnlinkLand(player: Player, land: TeamLand) = confirm(player, "arcjustteams.land.unlink", "settlements.unlink-title",
        "settlements.unlink-body", "settlements.unlink-confirm", mapOf("land" to land.name)) {
        val team = teams.team(player.uniqueId)
        if (team == null || lands?.ownedBy(land.id, player.uniqueId) != true || land.id !in team.landIds) openLands(player, "settlements.changed")
        else if (teams.unbindLand(team.raw, land.id)) openLands(player, "settlements.unlinked")
        else openLands(player, "notice.bind-failed")
    }

    private fun openDungeons(player: Player, notice: String? = null, noticeValues: Map<String, Any> = emptyMap()) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        show(player, PaperDialogScreen(
            id = "arcjustteams.dungeons", title = texts.get(player, "dungeons.title"),
            body = listOfNotNull(notice?.let { DialogTextLayout.modelBody(texts.get(player, it, noticeValues)) },
                DialogTextLayout.modelBody(texts.get(player, "dungeons.body", mapOf("minimum" to minimumDungeonMembers))),
                DialogTables.body(listOf(
                    texts.get(player, "summary.dungeons") to value(team.dungeonRuns),
                    texts.get(player, "dungeons.minimum") to value(minimumDungeonMembers),
                    texts.get(player, "dungeons.destination") to texts.get(player, "dungeons.spawn"),
                ), frame = DialogTables.Frame.ARTIFACT, width = 300)),
            buttons = listOf(if (teams.elevated(team, player.uniqueId)) {
                button("gather", player, "dungeons.gather", "dungeons.gather-tooltip") {
                    val fresh = teams.team(player.uniqueId)
                    when {
                        fresh?.id != team.id -> open(player, "members.changed")
                        !teams.elevated(fresh, player.uniqueId) -> openDungeons(player)
                        dungeonParties.gather(player, fresh) -> openDungeons(player, "dungeons.gathering")
                        else -> openDungeons(player, "dungeons.unavailable")
                    }
                }
            } else {
                button("travel", player, "dungeons.travel", "dungeons.travel-tooltip") {
                    if (dungeonParties.travel(player)) openDungeons(player, "dungeons.travelling")
                    else openDungeons(player, "dungeons.unavailable")
                }
            }), exitButton = footer(player), columns = 1,
        ), reopen = { openDungeons(player) })
    }

    fun showDungeonPartyResult(player: Player, result: DungeonPartyResult) {
        val notice = when {
            result.unavailable -> "dungeons.unavailable"
            result.skipped > 0 -> "dungeons.ready-partial"
            else -> "dungeons.ready"
        }
        openDungeons(player, notice, mapOf("members" to result.readyMemberIds.size, "skipped" to result.skipped))
    }

    private fun openSettings(player: Player, notice: String? = null) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val editable = teams.elevated(team, player.uniqueId)
        val rows = listOf(
            texts.get(player, "settings.tag") to value(team.tag),
            texts.get(player, "settings.description") to value(team.description),
            texts.get(player, "settings.public") to texts.get(player, stateKey(team.public)),
            texts.get(player, "settings.pvp") to texts.get(player, stateKey(team.pvp)),
            texts.get(player, "settings.glow") to texts.get(player, stateKey(team.glow)),
        )
        val buttons = if (!editable) emptyList() else listOf(
            button("tag", player, "settings.tag-edit") { openTag(player) },
            button("description", player, "settings.description-edit") { openDescription(player) },
            toggle("public", player, "settings.public-toggle", team.public) { teams.togglePublic(player); openSettings(player, "settings.saved") },
            toggle("pvp", player, "settings.pvp-toggle", team.pvp) { teams.togglePvp(player); openSettings(player, "settings.saved") },
            toggle("glow", player, "settings.glow-toggle", team.glow) { teams.toggleGlow(player); openSettings(player, "settings.saved") },
        )
        show(player, PaperDialogScreen(
            id = "arcjustteams.settings", title = texts.get(player, "settings.title"),
            body = listOfNotNull(noticeBody(player, notice), if (!editable) DialogTextLayout.modelBody(texts.get(player, "settings.forbidden")) else null,
                DialogTables.body(rows, frame = DialogTables.Frame.LEGENDARY, width = 320)),
            buttons = buttons, exitButton = footer(player), columns = 2,
        ), reopen = { openSettings(player) })
    }

    private fun openTag(player: Player, notice: String? = null, initial: String? = null) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        show(player, PaperDialogScreen(
            id = "arcjustteams.settings.tag", title = texts.get(player, "settings.tag-title"),
            body = listOfNotNull(noticeBody(player, notice), DialogTextLayout.modelBody(texts.get(player, "settings.tag-body"))),
            inputs = listOf(PaperDialogTextInput(TAG_INPUT, texts.get(player, "settings.tag-input"), initial ?: team.tag, 300, 6)),
            buttons = listOf(contextButton("save_tag", player, "settings.save") { context ->
                val value = context.text(TAG_INPUT).orEmpty().trim()
                teams.setTag(player, value)?.let { openTag(player, it, value) } ?: openSettings(player, "settings.saved")
            }), exitButton = footer(player), columns = 1,
        ))
    }

    private fun openDescription(player: Player, notice: String? = null, initial: String? = null) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        show(player, PaperDialogScreen(
            id = "arcjustteams.settings.description", title = texts.get(player, "settings.description-title"),
            body = listOfNotNull(noticeBody(player, notice), DialogTextLayout.modelBody(texts.get(player, "settings.description-body"))),
            inputs = listOf(PaperDialogTextInput(DESCRIPTION_INPUT, texts.get(player, "settings.description-input"), initial ?: team.description, 468, 64)),
            buttons = listOf(contextButton("save_description", player, "settings.save") { context ->
                val value = context.text(DESCRIPTION_INPUT).orEmpty().trim()
                teams.setDescription(player, value)?.let { openDescription(player, it, value) } ?: openSettings(player, "settings.saved")
            }), exitButton = footer(player), columns = 1,
        ))
    }

    private fun openAllies(player: Player, notice: String? = null) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val rows = buildList {
            team.allies.forEach { add(value(teams.teamName(it)) to texts.get(player, "allies.active")) }
            team.receivedAllyRequests.forEach { add(value(teams.teamName(it)) to texts.get(player, "allies.incoming")) }
            team.sentAllyRequests.forEach { add(value(teams.teamName(it)) to texts.get(player, "allies.outgoing")) }
        }
        val editable = teams.elevated(team, player.uniqueId)
        val buttons = buildList {
            if (editable) {
                add(button("add", player, "allies.add") { openAddAlly(player) })
                add(toggle("requests", player, "allies.requests-toggle", team.acceptsAllyRequests) {
                    teams.toggleAcceptRequests(player); openAllies(player, "allies.changed")
                })
                team.receivedAllyRequests.forEachIndexed { index, id ->
                    add(button("incoming_$index", player, "allies.review", values = mapOf("team" to teams.teamName(id))) {
                        openAllyRequest(player, id)
                    })
                }
                team.allies.forEachIndexed { index, id ->
                    add(button("ally_$index", player, "allies.open", values = mapOf("team" to teams.teamName(id))) {
                        openRemoveAlly(player, id)
                    })
                }
            }
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.allies", title = texts.get(player, "allies.title"),
            body = buildList {
                noticeBody(player, notice)?.let(::add)
                add(DialogTextLayout.modelBody(texts.get(player, if (rows.isEmpty()) "allies.empty" else "allies.intro")))
                if (rows.isNotEmpty()) add(DialogTables.body(rows, frame = DialogTables.Frame.LEGENDARY, width = 320))
                if (!editable) add(DialogTextLayout.modelBody(texts.get(player, "allies.forbidden")))
            }, buttons = buttons, exitButton = footer(player), columns = 2,
        ), reopen = { openAllies(player) })
    }

    private fun openAddAlly(player: Player, notice: String? = null, initial: String = "", pending: Boolean = false) {
        show(player, PaperDialogScreen(
            id = "arcjustteams.allies.add", title = texts.get(player, "allies.add-title"),
            body = listOfNotNull(noticeBody(player, notice), DialogTextLayout.modelBody(texts.get(player, "allies.add-body"))),
            inputs = listOf(PaperDialogTextInput(TEAM_INPUT, texts.get(player, "allies.team-input"), initial, 300, 16)),
            buttons = listOf(contextButton("send", player, "allies.send") { context ->
                val name = context.text(TEAM_INPUT).orEmpty().trim()
                if (teams.browseTeams().none { it.name.equals(name, true) }) openAddAlly(player, "allies.not-found", name)
                else {
                    teams.sendAllyRequest(player, name)
                    openAddAlly(player, initial = name, pending = true)
                    runLaterOpen(player, 10L) { openAllies(player, "allies.sent") }
                }
            }), exitButton = footer(player), columns = 1,
        ), pending = pending)
    }

    private fun openAllyRequest(player: Player, id: Int, pending: Boolean = false) {
        val name = teams.teamName(id)
        show(player, PaperDialogScreen(
            id = "arcjustteams.allies.request", title = texts.get(player, "allies.request-title"),
            body = listOf(DialogTextLayout.modelBody(texts.get(player, "allies.request-body", mapOf("team" to name)))),
            buttons = listOf(
                button("accept", player, "allies.accept") {
                    teams.acceptAllyRequest(player, id)
                    openAllyRequest(player, id, pending = true)
                    runLaterOpen(player, 10L) { openAllies(player, "allies.changed") }
                },
                button("deny", player, "allies.deny") {
                    teams.denyAllyRequest(player, id)
                    openAllyRequest(player, id, pending = true)
                    runLaterOpen(player, 10L) { openAllies(player, "allies.changed") }
                },
            ), exitButton = footer(player), columns = 2,
        ), pending = pending)
    }

    private fun openRemoveAlly(player: Player, id: Int, knownName: String? = null, pending: Boolean = false) {
        val name = knownName ?: teams.teamName(id)
        confirm(player, "arcjustteams.allies.remove", "allies.remove-title", "allies.remove-body", "allies.remove-confirm",
            mapOf("team" to name), pending = pending) {
                teams.removeAlly(player, name)
                openRemoveAlly(player, id, name, pending = true)
                runLaterOpen(player, 10L) { openAllies(player, "allies.changed") }
            }
    }

    private fun openQuests(player: Player, requestedPage: Int = 0, notice: String? = null) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val all = teams.quests(team)
        val pages = maxOf(1, (all.size + 7) / 8)
        val page = requestedPage.coerceIn(0, pages - 1)
        val listed = all.drop(page * 8).take(8)
        val rows = listed.map { quest ->
            value(quest.name) to texts.get(player, when {
                quest.claimed -> "quests.claimed"
                quest.completed -> "quests.ready"
                else -> "quests.progress"
            }, mapOf("progress" to quest.progress, "required" to quest.required))
        }
        val buttons = buildList {
            listed.forEachIndexed { index, quest ->
                add(button("quest_$index", player, "quests.open", values = mapOf("quest" to quest.name)) { openQuest(player, quest.id) })
            }
            paging(player, page, pages, { openQuests(player, it) }, this)
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.quests", title = texts.get(player, "quests.title"),
            body = buildList {
                noticeBody(player, notice)?.let(::add)
                add(DialogTextLayout.modelBody(texts.get(player, if (all.isEmpty()) "quests.empty" else "quests.intro")))
                if (rows.isNotEmpty()) add(DialogTables.body(rows, frame = DialogTables.Frame.EPIC, width = 320))
            }, buttons = buttons, exitButton = footer(player), columns = 2,
        ), reopen = { openQuests(player, page) })
    }

    private fun openQuest(player: Player, questId: String) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val quest = teams.quests(team).firstOrNull { it.id == questId } ?: return openQuests(player)
        val rows = listOf(
            texts.get(player, "quests.progress-label") to value("${quest.progress}/${quest.required}"),
            texts.get(player, "quests.points-label") to value(quest.rewardPoints),
            texts.get(player, "quests.money-label") to value(numbers.format(quest.rewardMoney)),
        )
        val buttons = if (quest.completed && !quest.claimed) listOf(button("claim", player, "quests.claim") {
            val success = teams.claimQuest(player, team.id, quest.id)
            openQuests(player, notice = if (success) "quests.claim-success" else "quests.claim-failed")
        }) else emptyList()
        show(player, PaperDialogScreen(
            id = "arcjustteams.quest", title = value(quest.name),
            body = listOf(DialogTextLayout.modelBody(value(quest.description)), DialogTables.body(rows, frame = DialogTables.Frame.EPIC, width = 300)),
            buttons = buttons, exitButton = footer(player), columns = 1,
        ), reopen = { openQuest(player, questId) })
    }

    private fun openBuffs(player: Player) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val buffs = teams.buffs(team)
        val (maximum, nearby, radius) = teams.buffLimits()
        val rows = buffs.map { buff ->
            value(buff.display) to texts.get(player, if (buff.active) "buffs.active" else "buffs.available", mapOf("cost" to buff.pointsCost))
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.buffs", title = texts.get(player, "buffs.title"),
            body = buildList {
                add(DialogTextLayout.modelBody(texts.get(player, "buffs.intro", mapOf("maximum" to maximum, "nearby" to nearby, "radius" to radius))))
                add(DialogTextLayout.modelBody(texts.get(player, "buffs.read-only")))
                if (rows.isEmpty()) add(DialogTextLayout.modelBody(texts.get(player, "buffs.empty")))
                else add(DialogTables.body(rows, frame = DialogTables.Frame.EPIC, width = 320))
            }, buttons = emptyList(), exitButton = footer(player), columns = 1,
        ), reopen = { openBuffs(player) })
    }

    private fun confirm(
        player: Player,
        id: String,
        titleKey: String,
        bodyKey: String,
        confirmKey: String,
        values: Map<String, Any> = emptyMap(),
        pending: Boolean = false,
        action: () -> Unit,
    ) = show(player, PaperDialogScreen(
        id = id, title = texts.get(player, titleKey, values),
        body = listOf(DialogTextLayout.modelBody(texts.get(player, bodyKey, values))),
        buttons = listOf(button("confirm", player, confirmKey, values = values, action = action)),
        exitButton = footer(player), columns = 1,
    ), pending = pending)

    private fun show(player: Player, screen: PaperDialogScreen, reopen: (() -> Unit)? = null, pending: Boolean = false) {
        val generation = visitGeneration(player)
        generation.advance()
        val presented = if (pending) screen.copy(
            body = screen.body + DialogTextLayout.modelBody(texts.get(player, "pending")),
            buttons = emptyList(),
        ) else screen
        runtime.open(player, presented, reopen, {
            generation.invalidate()
            visitGenerations.remove(player.uniqueId, generation)
        }, false)
    }

    private fun runLaterOpen(player: Player, delayTicks: Long, action: () -> Unit) {
        val generation = visitGeneration(player)
        val token = generation.current()
        tasks.runLater(delayTicks) {
            generation.ifCurrent(token, action)
        }
    }

    private fun visitGeneration(player: Player): DialogVisitGeneration =
        visitGenerations.getOrPut(player.uniqueId) { DialogVisitGeneration() }

    private fun button(
        id: String,
        player: Player,
        key: String,
        tooltipKey: String? = null,
        values: Map<String, Any> = emptyMap(),
        action: () -> Unit,
    ) = PaperDialogButton(
        id = PaperDialogActionId.of(id), label = texts.get(player, key, values),
        tooltip = tooltipKey?.let { texts.get(player, it, values) } ?: Component.empty(), width = 230,
        closeDialogBeforeAction = false, onClick = { action() },
    )

    private fun contextButton(id: String, player: Player, key: String, action: (PaperDialogClickContext) -> Unit) =
        PaperDialogButton(PaperDialogActionId.of(id), texts.get(player, key), width = 300, closeDialogBeforeAction = false, onClick = action)

    private fun toggle(id: String, player: Player, key: String, enabled: Boolean, action: () -> Unit) =
        button(id, player, "$key.${if (enabled) "on" else "off"}", action = action)

    private fun footer(player: Player) = button("back", player, "nav.back") {}.copy(width = 200)
    private fun close(player: Player) = PaperDialogButton(PaperDialogActionId.of("close"), texts.get(player, "nav.close"),
        width = 200, closeDialogBeforeAction = true, onClick = {})

    private fun paging(player: Player, page: Int, pages: Int, open: (Int) -> Unit, destination: MutableList<PaperDialogButton>) {
        if (page > 0) destination += button("previous", player, "nav.previous") { open(page - 1) }
        if (page < pages - 1) destination += button("next", player, "nav.next") { open(page + 1) }
    }

    private fun noticeBody(player: Player, key: String?) = key?.let { DialogTextLayout.modelBody(texts.get(player, it)) }
    private fun stateKey(enabled: Boolean) = if (enabled) "state.on" else "state.off"
    private fun upgradeCost(player: Player, upgrade: TeamUpgradeSnapshot): String = listOfNotNull(
        upgrade.formattedMoneyCost?.takeIf { upgrade.moneyCost > 0 },
        upgrade.pointsCost.takeIf { it > 0 }?.let { texts.getPlain(player, "upgrades.points-value", mapOf("points" to it)) },
    ).joinToString(" + ")
    private fun value(value: Any): Component = Component.text(value.toString(), NamedTextColor.WHITE)

    companion object {
        private val NAME_INPUT = PaperDialogInputId.of("name")
        private val TAG_INPUT = PaperDialogInputId.of("tag")
        private val PLAYER_INPUT = PaperDialogInputId.of("player")
        private val TEAM_INPUT = PaperDialogInputId.of("team")
        private val DESCRIPTION_INPUT = PaperDialogInputId.of("description")
    }
}

private fun Int?.orEmpty(): String = this?.toString().orEmpty()
