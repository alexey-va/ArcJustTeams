package ru.ruscrafting.justteams

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.whenCompleteSync
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.DialogDecoration
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
    private val nativeWork: BudgetedTaskQueue,
    private val worker: BoundedAsyncExecutor,
) {
    private val numbers = DecimalFormat("#,##0.##")
    private val visitGenerations = mutableMapOf<UUID, DialogVisitGeneration>()
    private val landBindings = mutableSetOf<String>()
    private var browseSnapshot = emptyList<TeamBrowseSnapshot>()

    fun begin(player: Player) {
        visitGeneration(player).invalidate()
        runtime.beginFlow(player)
        open(player)
    }

    fun open(player: Player, notice: String? = null) {
        val team = teams.team(player.uniqueId)
        if (team == null) return openEmpty(player, notice)
        landReconciler?.reconcile(team)
        val linked = lands?.linked(team.landIds).orEmpty()
        val quickLands = linked.take(2)
        show(player, PaperDialogScreen(
            id = "arcjustteams.root",
            title = texts.get(player, "summary.title", mapOf("tag" to team.tag, "team" to team.name)),
            body = buildList {
                noticeBody(player, notice)?.let(::add)
                add(DialogTables.groupedFactsBody(listOf(
                    DialogTables.FactsGroup(texts.get(player, "summary.roster"), listOf(
                        field(player, "summary.members", DialogDecoration.FieldIcon.PLAYER) to value(team.memberCount),
                        field(player, "summary.online", DialogDecoration.FieldIcon.ONLINE) to value(team.onlineCount),
                        texts.get(player, "summary.dungeons") to value(team.dungeonRuns),
                    )),
                    DialogTables.FactsGroup(texts.get(player, "summary.progression"), listOf(
                        field(player, "summary.tier", DialogDecoration.FieldIcon.RANK) to value(team.tier),
                        texts.get(player, "summary.points") to value(numbers.format(team.points)),
                        texts.get(player, "summary.buffs") to value(team.activeBuffs.size),
                    )),
                ), DialogTables.PanelSpec(DialogTables.PanelSkin.PLUM, 340, 2, true)))
                if (quickLands.isNotEmpty()) {
                    add(DialogTables.body(quickLands.map { land ->
                        value(land.name) to texts.get(player, "settlements.row", mapOf("members" to land.members))
                    }, headers = field(player, "summary.lands", DialogDecoration.FieldIcon.LANDS) to
                        texts.get(player, "summary.land-count", mapOf("count" to linked.size)),
                        frame = DialogTables.Frame.UNCOMMON, width = 340, columns = DialogTables.Columns.AUTO))
                    if (linked.size > quickLands.size) add(DialogTextLayout.modelBody(texts.get(player, "summary.more-lands",
                        mapOf("count" to linked.size - quickLands.size))))
                } else add(DialogTextLayout.modelBody(texts.get(player,
                    if (lands == null) "notice.integration-missing" else "settlements.none")))
            },
            buttons = buildList {
                add(button("members", player, "nav.members", "nav.members-tooltip") { openMembers(player) })
                add(button("allies", player, "nav.allies", "nav.allies-tooltip") { openAllies(player) })
                add(button("quests", player, "nav.quests", "nav.quests-tooltip") { openQuests(player) })
                add(button("dungeons", player, "nav.dungeons", "nav.dungeons-tooltip") { openDungeons(player) })
                add(button("upgrades", player, "nav.upgrades", "nav.upgrades-tooltip") { openUpgrades(player) })
                add(button("buffs", player, "nav.buffs", "nav.buffs-tooltip") { openBuffs(player) })
                add(button("lands", player, "nav.lands", "nav.lands-tooltip") { openLands(player) })
                add(button("settings", player, "nav.settings", "nav.settings-tooltip") { openSettings(player) })
                quickLands.forEachIndexed { index, land ->
                    add(button("spawn_$index", player, "settlements.travel", "settlements.travel-tooltip",
                        mapOf("land" to land.name)) { travelToLand(player, team.id, land.id) })
                }
            },
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
        if (pending) return presentBrowse(player, requestedPage, true, browseSnapshot)
        show(player, PaperDialogScreen(
            id = "arcjustteams.browse", title = texts.get(player, "browse.title"),
            body = emptyList(), buttons = emptyList(), exitButton = footer(player), columns = 2,
        ), pending = true)
        val generation = visitGeneration(player)
        val token = generation.current()
        teams.browseTeamsAsync().whenCompleteSync(tasks) { all, failure ->
            if (!player.isOnline) return@whenCompleteSync
            generation.ifCurrent(token) {
                if (failure != null) open(player, "no-team")
                else {
                    browseSnapshot = all.orEmpty()
                    presentBrowse(player, requestedPage, false, browseSnapshot)
                }
            }
        }
    }

    private fun presentBrowse(player: Player, requestedPage: Int, pending: Boolean, all: List<TeamBrowseSnapshot>) {
        val pages = maxOf(1, (all.size + 5) / 6)
        val page = requestedPage.coerceIn(0, pages - 1)
        val listed = all.subList(page * 6, minOf(all.size, page * 6 + 6))
        val rows = listed.mapIndexed { index, team ->
            texts.get(player, "browse.ranked-name", mapOf("rank" to page * 6 + index + 1, "tag" to team.tag, "team" to team.name)) to
                texts.get(player, if (team.public) "browse.public" else "browse.private",
                    mapOf("members" to team.members, "points" to numbers.format(team.points)))
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
                mapOf("page" to page + 1, "pages" to pages))), DialogTables.body(rows, headers = null, frame = DialogTables.Frame.COMMON, width = 320, columns = DialogTables.Columns.AUTO)),
            buttons = buttons, exitButton = footer(player), columns = 2,
        ), reopen = { openBrowse(player, page) }, pending = pending)
    }

    private fun openMembers(player: Player, requestedPage: Int = 0, notice: String? = null) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        show(player, PaperDialogScreen(
            id = "arcjustteams.members", title = texts.get(player, "members.title"),
            body = emptyList(), buttons = emptyList(), exitButton = footer(player), columns = 2,
        ), pending = true)
        val generation = visitGeneration(player)
        val token = generation.current()
        teams.membersAsync(team.raw).whenCompleteSync(tasks) { members, failure ->
            if (!player.isOnline) return@whenCompleteSync
            generation.ifCurrent(token) {
                val current = teams.team(player.uniqueId)
                if (failure != null || current?.id != team.id) open(player)
                else presentMembers(player, current, members.orEmpty(), requestedPage, notice)
            }
        }
    }

    private fun presentMembers(player: Player, team: TeamSnapshot, members: List<TeamMemberSnapshot>, requestedPage: Int, notice: String?) {
        val pages = maxOf(1, (members.size + 7) / 8)
        val page = requestedPage.coerceIn(0, pages - 1)
        val listed = members.subList(page * 8, minOf(members.size, page * 8 + 8))
        val rows = listed.map { member ->
            val state = if (member.online) "●" else "○"
            Component.text("$state ${member.name}", if (member.online) NamedTextColor.GREEN else NamedTextColor.WHITE) to
                texts.get(player, "members.roles.${member.role}")
        }
        val buttons = buildList {
            listed.forEachIndexed { index, member ->
                add(button("member_$index", player, "members.open", values = mapOf("player" to member.name)) {
                    openMember(player, member.id, member.name)
                })
            }
            if (teams.elevated(team, player.uniqueId)) {
                add(button("invite", player, "members.invite") { openInvite(player) })
                add(button("requests", player, "members.requests") { openJoinRequests(player) })
            }
            if (team.ownerId == player.uniqueId) add(button("disband", player, "members.disband") { openDisband(player) })
            else add(button("leave", player, "members.leave") { openLeave(player) })
            paging(player, page, pages, { openMembers(player, it) }, this)
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.members", title = texts.get(player, "members.title"),
            body = listOfNotNull(noticeBody(player, notice), DialogTextLayout.modelBody(texts.get(player, "members.intro",
                mapOf("page" to page + 1, "pages" to pages))), DialogTables.body(rows, headers = null, frame = DialogTables.Frame.COMMON, width = 320, columns = DialogTables.Columns.AUTO)),
            buttons = buttons, exitButton = footer(player), columns = 2,
        ), reopen = { openMembers(player, page) })
    }

    private fun openMember(player: Player, memberId: UUID, previousName: String) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        show(player, PaperDialogScreen(
            id = "arcjustteams.member", title = texts.get(player, "members.member-title", mapOf("player" to previousName)),
            body = emptyList(), buttons = emptyList(), exitButton = footer(player), columns = 2,
        ), pending = true)
        val generation = visitGeneration(player)
        val token = generation.current()
        teams.membersAsync(team.raw).thenApplyAsync({ members -> members.firstOrNull { it.id == memberId } }, worker)
            .whenCompleteSync(tasks) { member, failure ->
                if (!player.isOnline) return@whenCompleteSync
                generation.ifCurrent(token) {
                    val current = teams.team(player.uniqueId)
                    if (failure != null || current?.id != team.id || member == null) openMembers(player)
                    else presentMember(player, current, member)
                }
            }
    }

    private fun presentMember(player: Player, team: TeamSnapshot, member: TeamMemberSnapshot) {
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
            ), headers = null, frame = DialogTables.Frame.COMMON, width = 300, columns = DialogTables.Columns.AUTO)), buttons = buttons, exitButton = footer(player), columns = 2,
        ), reopen = { openMember(player, member.id, member.name) })
    }

    private fun openInvite(player: Player, notice: String? = null, initial: String = "") {
        show(player, PaperDialogScreen(
            id = "arcjustteams.invite", title = texts.get(player, "members.invite-title"),
            body = listOfNotNull(noticeBody(player, notice), DialogTextLayout.modelBody(texts.get(player, "members.invite-intro"))),
            inputs = listOf(PaperDialogTextInput(PLAYER_INPUT, texts.get(player, "members.player-input"), initial, 300, 16)),
            buttons = listOf(contextButton("invite_submit", player, "members.invite-submit") { context ->
                val name = context.text(PLAYER_INPUT).orEmpty().trim()
                val target = Bukkit.getPlayerExact(name)
                if (target == null) openInvite(player, "members.player-offline", name)
                else { teams.invite(player, target); openMembers(player, notice = "members.invited") }
            }), exitButton = footer(player), columns = 1,
        ))
    }

    private fun openJoinRequests(player: Player) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        if (!teams.elevated(team, player.uniqueId)) return openMembers(player)
        show(player, PaperDialogScreen(
            id = "arcjustteams.requests", title = texts.get(player, "members.requests-title"),
            body = emptyList(), buttons = emptyList(), exitButton = footer(player), columns = 2,
        ), pending = true)
        val generation = visitGeneration(player)
        val token = generation.current()
        teams.joinRequestsAsync(team).whenCompleteSync(tasks) { requests, failure ->
            if (!player.isOnline) return@whenCompleteSync
            generation.ifCurrent(token) {
                val current = teams.team(player.uniqueId)
                if (failure != null || current?.id != team.id || !teams.elevated(current, player.uniqueId)) open(player)
                else presentJoinRequests(player, current, requests.orEmpty(), generation, token)
            }
        }
    }

    private fun presentJoinRequests(player: Player, team: TeamSnapshot, requests: List<TeamJoinRequestSnapshot>,
        generation: DialogVisitGeneration, token: DialogVisitGeneration.Token) {
        val teamId = team.id
        val buttons = ArrayList<PaperDialogButton>()
        val pending = requests.iterator()
        val title = texts.get(player, "members.requests-title")
        val body = listOf(DialogTextLayout.modelBody(texts.get(player,
            if (requests.isEmpty()) "members.requests-empty" else "members.requests-intro")))
        val footer = footer(player)
        fun current(): Boolean = player.isOnline && generation.current() == token &&
            teams.teamFor(player.uniqueId) === team.raw && teams.elevated(team, player.uniqueId)
        nativeWork.submit(::current) {
            if (!pending.hasNext()) true else {
                val request = pending.next()
                buttons += button("request_${buttons.size}", player, "members.request", values = mapOf("player" to request.name)) {
                    openJoinRequest(player, teamId, request.id, request.name)
                }
                false
            }
        }.thenCompose {
            // Model uniqueness validation is pure CPU; no player/native reads on this worker.
            worker.submit { PaperDialogScreen(id = "arcjustteams.requests", title = title, body = body,
                buttons = java.util.Collections.unmodifiableList(buttons), exitButton = footer, columns = 2) }
        }.whenCompleteSync(tasks) { screen, failure ->
            if (!player.isOnline) return@whenCompleteSync
            generation.ifCurrent(token) {
                if (failure != null || screen == null || !current()) { open(player); return@ifCurrent }
                val binding = generation.advance()
                runtime.openAsync(player, screen, nativeWork,
                    isCurrent = { player.isOnline && generation.current() == binding &&
                        teams.teamFor(player.uniqueId) === team.raw && teams.elevated(team, player.uniqueId) },
                    reopen = { openJoinRequests(player) },
                    onDismiss = {
                        generation.invalidate()
                        visitGenerations.remove(player.uniqueId, generation)
                    },
                ).whenCompleteSync(tasks) { _, bindingFailure ->
                    if (bindingFailure != null && player.isOnline) generation.ifCurrent(binding) { open(player) }
                }
            }
        }
    }

    private fun openJoinRequest(player: Player, teamId: Int, id: UUID, name: String) {
        val team = teams.team(player.uniqueId)
        if (team?.id != teamId || !teams.elevated(team, player.uniqueId)) return open(player)
        fun update(accept: Boolean) {
            val current = teams.team(player.uniqueId)
            if (current?.id != teamId || !teams.elevated(current, player.uniqueId)) { open(player); return }
            if (accept) teams.acceptJoinRequest(current, id) else teams.denyJoinRequest(current, id)
            openJoinRequests(player)
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.request", title = texts.get(player, "members.request-title"),
            body = listOf(DialogTextLayout.modelBody(texts.get(player, "members.request-body", mapOf("player" to name)))),
            buttons = listOf(
                button("accept", player, "members.accept") { update(true) },
                button("deny", player, "members.deny") { update(false) },
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
        val canUpgrade = upgrade.enabled && upgrade.nextTier != null && team.ownerId == player.uniqueId &&
            team.balance >= upgrade.moneyCost && team.points >= upgrade.pointsCost
        val buttons = if (!canUpgrade) emptyList() else listOf(
            button("upgrade_confirm", player, "upgrades.continue") { openUpgradeConfirm(player, team, upgrade) },
        )
        show(player, PaperDialogScreen(
            id = "arcjustteams.upgrades", title = texts.get(player, "upgrades.title"),
            body = buildList {
                noticeBody(player, notice)?.let(::add)
                val groups = buildList {
                    add(DialogTables.FactsGroup(texts.get(player, "upgrades.current"), listOf(
                        field(player, "summary.tier", DialogDecoration.FieldIcon.RANK) to value(upgrade.currentTier),
                        texts.get(player, "upgrades.members") to value(upgrade.maxMembers),
                    )))
                    if (upgrade.nextTier != null) add(DialogTables.FactsGroup(texts.get(player, "upgrades.next"), listOf(
                        field(player, "summary.tier", DialogDecoration.FieldIcon.RANK) to value(upgrade.nextTier),
                        texts.get(player, "upgrades.members") to value(upgrade.nextMaxMembers ?: upgrade.maxMembers),
                    )))
                }
                add(DialogTables.groupedFactsBody(groups, DialogTables.PanelSpec(DialogTables.PanelSkin.PLUM, 340, groups.size, true)))
                if (upgrade.enabled && upgrade.nextTier != null) {
                    if (upgrade.moneyCost > 0) add(DialogTables.factsBody(listOf(
                        field(player, "upgrades.money-cost", DialogDecoration.FieldIcon.BALANCE) to
                            value("${numbers.format(team.balance)} / ${numbers.format(upgrade.moneyCost)}"),
                    ), DialogTables.FactsLayout.ROWS, DialogTables.PanelSpec(DialogTables.PanelSkin.COCOA, 340, 1, true)))
                    if (upgrade.pointsCost > 0) add(DialogDecoration.library().progressBody(
                        texts.get(player, "upgrades.points-progress"), team.points.toDouble(), upgrade.pointsCost.toDouble(),
                        value("${numbers.format(team.points)} / ${numbers.format(upgrade.pointsCost)}"),
                        340, 12, DialogDecoration.ProgressColor.VIOLET,
                    ))
                }
                val status = when {
                    !upgrade.enabled -> "upgrades.disabled"
                    upgrade.nextTier == null -> "upgrades.maximum"
                    team.ownerId != player.uniqueId -> "upgrades.owner-only"
                    team.balance < upgrade.moneyCost -> "upgrades.need-money"
                    team.points < upgrade.pointsCost -> "upgrades.need-points"
                    else -> "upgrades.ready"
                }
                add(DialogTextLayout.modelBody(texts.get(player, status)))
            },
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

    private fun openLands(player: Player, notice: String? = null, pending: Boolean = false, requestedPage: Int = 0) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val linked = lands?.linked(team.landIds).orEmpty()
        val owned = lands?.ownedLands(player).orEmpty()
        val available = linked + owned.filter { it.id !in team.landIds }
        val pages = maxOf(1, (available.size + 7) / 8)
        val page = requestedPage.coerceIn(0, pages - 1)
        val listed = available.drop(page * 8).take(8)
        val buttons = buildList {
            listed.forEachIndexed { index, land ->
                add(if (land.id in team.landIds) button("land_$index", player, "settlements.linked",
                    "settlements.open-tooltip", mapOf("land" to land.name)) { openLand(player, land.id) }
                else button("land_$index", player, "settlements.link", "settlements.link-tooltip", mapOf("land" to land.name)) {
                    val fresh = teams.team(player.uniqueId)
                    when {
                        fresh?.id != team.id || lands?.ownedBy(land.id, player.uniqueId) != true -> openLands(player, "settlements.changed")
                        !landBindings.add(land.id) -> openLands(player, "settlements.changed")
                        else -> linkLand(player, team, land)
                    }
                })
            }
            paging(player, page, pages, { openLands(player, requestedPage = it) }, this)
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.lands", title = texts.get(player, "settlements.title"),
            body = buildList {
                noticeBody(player, notice)?.let(::add)
                if (lands == null) add(DialogTextLayout.modelBody(texts.get(player, "notice.integration-missing")))
                else {
                    add(DialogTables.factsBody(listOf(
                        field(player, "summary.lands", DialogDecoration.FieldIcon.LANDS) to value(linked.size),
                        texts.get(player, "settlements.owned") to value(owned.count { it.id !in team.landIds }),
                    ), DialogTables.FactsLayout.METRICS, DialogTables.PanelSpec(DialogTables.PanelSkin.EVERGREEN, 320, 2, true)))
                    add(DialogTextLayout.modelBody(texts.get(player, "settlements.intro")))
                    if (available.isEmpty()) add(DialogTextLayout.modelBody(texts.get(player, "settlements.empty")))
                    if (pages > 1) add(DialogTextLayout.modelBody(texts.get(player, "settlements.page",
                        mapOf("page" to page + 1, "pages" to pages))))
                }
            }, buttons = buttons, exitButton = footer(player), columns = 2,
        ), reopen = { openLands(player, requestedPage = page) }, pending = pending)
    }

    private fun openLand(player: Player, landId: String) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val land = lands?.linked(team.landIds)?.firstOrNull { it.id == landId }
            ?: return openLands(player, "settlements.changed")
        val owned = lands?.ownedBy(land.id, player.uniqueId) == true
        show(player, PaperDialogScreen(
            id = "arcjustteams.land", title = texts.get(player, "settlements.land-title", mapOf("land" to land.name)),
            body = listOf(DialogTables.factsBody(listOf(
                field(player, "settlements.residents", DialogDecoration.FieldIcon.PLAYER) to value(land.members),
                texts.get(player, "settlements.your-role") to texts.get(player, if (owned) "settlements.owner" else "settlements.resident"),
            ), DialogTables.FactsLayout.ROWS, DialogTables.PanelSpec(DialogTables.PanelSkin.EVERGREEN, 320, 1, true))),
            buttons = buildList {
                add(button("travel", player, "settlements.travel", "settlements.travel-tooltip",
                    mapOf("land" to land.name)) { travelToLand(player, team.id, land.id) })
                if (owned) add(button("unlink", player, "settlements.unlink-title") { openUnlinkLand(player, land) })
            }, exitButton = footer(player), columns = 1,
        ), reopen = { openLand(player, landId) })
    }

    private fun travelToLand(player: Player, expectedTeamId: Int, landId: String) {
        val fresh = teams.team(player.uniqueId)
        if (fresh?.id != expectedTeamId || landId !in fresh.landIds) {
            open(player, "settlements.changed")
            return
        }
        runtime.close(player)
        if (lands?.travel(player, landId) != true) player.sendMessage(texts.get(player, "settlements.travel-unavailable"))
    }

    private fun linkLand(player: Player, team: TeamSnapshot, land: TeamLand) {
        openLands(player, pending = true)
        val generation = visitGeneration(player)
        val token = generation.current()
        teams.landLinkedElsewhere(team.id, land.id).whenCompleteSync(tasks) { elsewhere, failure ->
            landBindings.remove(land.id)
            if (!player.isOnline) return@whenCompleteSync
            generation.ifCurrent(token) {
                val fresh = teams.team(player.uniqueId)
                when {
                    failure != null -> openLands(player, "notice.bind-failed")
                    fresh?.id != team.id || lands?.ownedBy(land.id, player.uniqueId) != true -> openLands(player, "settlements.changed")
                    elsewhere == true -> openLands(player, "settlements.already-used")
                    !teams.bindLand(fresh.raw, land.id) -> openLands(player, "notice.bind-failed")
                    else -> {
                        val updated = teams.team(player.uniqueId) ?: fresh
                        val sync = landReconciler?.reconcile(updated)
                        if (sync == null) openLands(player, "notice.bound") else sync.whenCompleteSync(tasks) { result, syncFailure ->
                            if (player.isOnline) generation.ifCurrent(token) {
                                openLands(player, if (syncFailure != null || (result?.failed ?: 0) > 0) "notice.bound-partial" else "notice.bound")
                            }
                        }
                    }
                }
            }
        }
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
                DialogTables.body(rows, headers = null, frame = DialogTables.Frame.RARE, width = 320, columns = DialogTables.Columns.AUTO)),
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
                if (rows.isNotEmpty()) add(DialogTables.body(rows, headers = null, frame = DialogTables.Frame.COMMON, width = 320, columns = DialogTables.Columns.AUTO))
                if (!editable) add(DialogTextLayout.modelBody(texts.get(player, "allies.forbidden")))
            }, buttons = buttons, exitButton = footer(player), columns = 2,
        ), reopen = { openAllies(player) })
    }

    private fun openAddAlly(player: Player, query: String = "", requestedPage: Int = 0, notice: String? = null) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        if (!teams.elevated(team, player.uniqueId)) return openAllies(player)
        show(player, PaperDialogScreen(
            id = "arcjustteams.allies.add", title = texts.get(player, "allies.add-title"),
            body = emptyList(),
            inputs = listOf(PaperDialogTextInput(TEAM_INPUT, texts.get(player, "allies.team-input"), query, 300, 32)),
            buttons = emptyList(), exitButton = footer(player), columns = 2,
        ), pending = true)
        val generation = visitGeneration(player)
        val token = generation.current()
        teams.browseTeamsAsync().thenApplyAsync({ all ->
            all.withIndex().filter { (_, candidate) ->
                allyCandidate(team, candidate) &&
                    (query.isBlank() || candidate.name.contains(query, true) || candidate.tag.contains(query, true))
            }
        }, worker).whenCompleteSync(tasks) { candidates, failure ->
            if (player.isOnline) generation.ifCurrent(token) {
                val current = teams.team(player.uniqueId)
                if (current?.id != team.id || !teams.elevated(current, player.uniqueId)) open(player)
                else if (failure != null) openAllies(player, "allies.load-failed")
                else presentAllyCandidates(player, current, candidates.orEmpty(), query, requestedPage, notice)
            }
        }
    }

    private fun presentAllyCandidates(player: Player, team: TeamSnapshot, candidates: List<IndexedValue<TeamBrowseSnapshot>>,
        query: String, requestedPage: Int, notice: String?) {
        val pages = maxOf(1, (candidates.size + 5) / 6)
        val page = requestedPage.coerceIn(0, pages - 1)
        val listed = candidates.drop(page * 6).take(6)
        val buttons = buildList {
            listed.forEachIndexed { index, ranked ->
                val candidate = ranked.value
                add(button("candidate_$index", player, "allies.candidate", "allies.candidate-tooltip", mapOf(
                    "rank" to ranked.index + 1, "team" to candidate.name, "tag" to candidate.tag,
                    "tier" to candidate.tier, "members" to candidate.members, "points" to numbers.format(candidate.points),
                )) { openAllyProposal(player, team.id, candidate) })
            }
            add(contextButton("search", player, "allies.search") { context ->
                openAddAlly(player, context.text(TEAM_INPUT).orEmpty().trim())
            }.copy(width = 230))
            if (query.isNotEmpty()) add(button("clear", player, "allies.clear") { openAddAlly(player) })
            paging(player, page, pages, { openAddAlly(player, query, it) }, this)
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.allies.add", title = texts.get(player, "allies.add-title"),
            body = listOfNotNull(noticeBody(player, notice), DialogTextLayout.modelBody(texts.get(player,
                if (listed.isEmpty()) "allies.candidates-empty" else "allies.add-body", mapOf("page" to page + 1, "pages" to pages)))),
            inputs = listOf(PaperDialogTextInput(TEAM_INPUT, texts.get(player, "allies.team-input"), query, 300, 32)),
            buttons = buttons, exitButton = footer(player), columns = 2,
        ), reopen = { openAddAlly(player, query, page) })
    }

    private fun openAllyProposal(player: Player, sourceTeamId: Int, candidate: TeamBrowseSnapshot, pending: Boolean = false) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        if (team.id != sourceTeamId || !teams.elevated(team, player.uniqueId) || !allyCandidate(team, candidate))
            return openAllies(player, "allies.candidate-changed")
        show(player, PaperDialogScreen(
            id = "arcjustteams.allies.propose", title = texts.get(player, "allies.add-title"),
            body = listOf(
                DialogTextLayout.modelBody(texts.get(player, "allies.propose-body", mapOf("team" to candidate.name))),
                DialogTables.factsBody(listOf(
                    texts.get(player, "settings.tag") to value(candidate.tag),
                    texts.get(player, "summary.tier") to value(candidate.tier),
                    texts.get(player, "summary.points") to value(numbers.format(candidate.points)),
                    texts.get(player, "summary.members") to value(candidate.members),
                ), DialogTables.FactsLayout.ROWS, DialogTables.PanelSpec(DialogTables.PanelSkin.COCOA, 320, 2, true)),
            ),
            buttons = listOf(button("send", player, "allies.send") {
                val fresh = teams.team(player.uniqueId)
                if (fresh?.id != sourceTeamId || !teams.elevated(fresh, player.uniqueId) || !allyCandidate(fresh, candidate)) {
                    openAllies(player, "allies.candidate-changed")
                    return@button
                }
                openAllyProposal(player, sourceTeamId, candidate, pending = true)
                val generation = visitGeneration(player)
                val token = generation.current()
                teams.browseTeamsAsync().thenApplyAsync({ all -> all.firstOrNull { it.id == candidate.id } }, worker)
                    .whenCompleteSync(tasks) { target, failure ->
                        if (player.isOnline) generation.ifCurrent(token) {
                            val current = teams.team(player.uniqueId)
                            when {
                                current?.id != sourceTeamId || !teams.elevated(current, player.uniqueId) -> open(player)
                                failure != null || target == null || target.name != candidate.name || !allyCandidate(current, target) ->
                                    openAddAlly(player, notice = "allies.candidate-changed")
                                else -> {
                                    teams.sendAllyRequest(player, target.name)
                                    runLaterOpen(player, 10L) {
                                        val updated = teams.team(player.uniqueId)
                                        openAllies(player, if (updated?.id == sourceTeamId && target.id in updated.sentAllyRequests)
                                            "allies.sent" else "allies.not-sent")
                                    }
                                }
                            }
                        }
                    }
            }), exitButton = footer(player), columns = 1,
        ), pending = pending)
    }

    private fun allyCandidate(team: TeamSnapshot, candidate: TeamBrowseSnapshot): Boolean =
        candidate.id != team.id && candidate.acceptsAllyRequests && candidate.id !in team.allies &&
            candidate.id !in team.sentAllyRequests && candidate.id !in team.receivedAllyRequests

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

    internal fun openQuests(player: Player) {
        if (ClanQuestMembershipBridge.openQuests(player)) return
        show(player, PaperDialogScreen(
            id = "arcjustteams.quests", title = texts.get(player, "quests.title"),
            body = listOf(DialogTextLayout.modelBody(texts.get(player, "quests.unavailable"))),
            buttons = emptyList(), exitButton = footer(player), columns = 1,
        ), reopen = { openQuests(player) })
    }

    private fun openBuffs(player: Player, requestedPage: Int = 0, notice: String? = null) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val buffs = teams.buffs(team)
        val (maximum, nearby, radius) = teams.buffLimits()
        val pages = maxOf(1, (buffs.size + 7) / 8)
        val page = requestedPage.coerceIn(0, pages - 1)
        val listed = buffs.drop(page * 8).take(8)
        show(player, PaperDialogScreen(
            id = "arcjustteams.buffs", title = texts.get(player, "buffs.title"),
            body = buildList {
                noticeBody(player, notice)?.let(::add)
                add(DialogTables.factsBody(listOf(
                    texts.get(player, "buffs.selected") to value("${buffs.count { it.active }} / $maximum"),
                    texts.get(player, "summary.points") to value(numbers.format(team.points)),
                ), DialogTables.FactsLayout.METRICS, DialogTables.PanelSpec(DialogTables.PanelSkin.PLUM, 320, 2, true)))
                add(DialogTextLayout.modelBody(texts.get(player, "buffs.intro", mapOf("maximum" to maximum, "nearby" to nearby, "radius" to radius))))
                if (buffs.isEmpty()) add(DialogTextLayout.modelBody(texts.get(player, "buffs.empty")))
                else if (!teams.buffEditingAvailable()) add(DialogTextLayout.modelBody(texts.get(player, "buffs.api-unavailable")))
                else if (!teams.elevated(team, player.uniqueId)) add(DialogTextLayout.modelBody(texts.get(player, "buffs.owner-only")))
            },
            buttons = buildList {
                listed.forEachIndexed { index, buff ->
                    add(button("buff_$index", player, if (buff.active) "buffs.selected-choice" else "buffs.choice",
                        if (buff.active) "buffs.selected-tooltip" else "buffs.choice-tooltip",
                        mapOf("buff" to buffName(player, buff), "cost" to numbers.format(buff.pointsCost))) { openBuff(player, buff.id) })
                }
                paging(player, page, pages, { openBuffs(player, it) }, this)
            }, exitButton = footer(player), columns = 2,
        ), reopen = { openBuffs(player, page) })
    }

    private fun openBuff(player: Player, buffId: String, notice: String? = null) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val buffs = teams.buffs(team)
        val buff = buffs.firstOrNull { it.id == buffId } ?: return openBuffs(player, notice = "buffs.result.changed")
        val (maximum, nearby, radius) = teams.buffLimits()
        val restriction = when {
            !teams.buffEditingAvailable() -> "buffs.api-unavailable"
            !teams.elevated(team, player.uniqueId) -> "buffs.owner-only"
            !buff.active && buffs.count { it.active } >= maximum -> "buffs.limit-reached"
            !buff.active && team.points < buff.pointsCost -> "buffs.need-points"
            else -> null
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.buff", title = texts.get(player, "buffs.detail-title", mapOf("buff" to buffName(player, buff))),
            body = buildList {
                noticeBody(player, notice)?.let(::add)
                buffDescription(player, buff)?.let { add(DialogTextLayout.modelBody(it)) }
                add(DialogTables.factsBody(listOf(
                    texts.get(player, "buffs.state") to texts.get(player, if (buff.active) "buffs.active" else "buffs.inactive"),
                    texts.get(player, "buffs.activation-cost") to texts.get(player, "buffs.available", mapOf("cost" to numbers.format(buff.pointsCost))),
                    texts.get(player, "summary.points") to value(numbers.format(team.points)),
                ), DialogTables.FactsLayout.ROWS, DialogTables.PanelSpec(DialogTables.PanelSkin.PLUM, 320, 1, true)))
                add(DialogTextLayout.modelBody(texts.get(player, "buffs.intro", mapOf("maximum" to maximum, "nearby" to nearby, "radius" to radius))))
                if (buff.pointsCost > 0) add(DialogTextLayout.modelBody(texts.get(player, "buffs.repeat-cost")))
                restriction?.let { add(DialogTextLayout.modelBody(texts.get(player, it))) }
            },
            buttons = if (restriction != null) emptyList() else listOf(
                button("configure", player, if (buff.active) "buffs.disable-open" else "buffs.enable-open") {
                    openBuffConfirm(player, team.id, buff)
                },
            ), exitButton = footer(player), columns = 1,
        ), reopen = { openBuff(player, buffId) })
    }

    private fun openBuffConfirm(player: Player, teamId: Int, buff: TeamBuffSnapshot, pending: Boolean = false) {
        show(player, PaperDialogScreen(
            id = "arcjustteams.buff.confirm", title = texts.get(player, "buffs.detail-title", mapOf("buff" to buffName(player, buff))),
            body = listOf(DialogTextLayout.modelBody(texts.get(player,
                if (buff.active) "buffs.disable-confirm" else "buffs.enable-confirm",
                mapOf("buff" to buffName(player, buff), "cost" to numbers.format(buff.pointsCost))))),
            buttons = listOf(button("apply", player, if (buff.active) "buffs.disable" else "buffs.enable") {
                val current = teams.team(player.uniqueId)
                val fresh = current?.takeIf { it.id == teamId }?.let { teams.buffs(it).firstOrNull { item -> item.id == buff.id } }
                if (fresh != buff) {
                    openBuffs(player, notice = "buffs.result.changed")
                    return@button
                }
                openBuffConfirm(player, teamId, buff, pending = true)
                val generation = visitGeneration(player)
                val token = generation.current()
                teams.setBuff(player, teamId, buff.id, !buff.active, buff.pointsCost).whenCompleteSync(tasks) { result, failure ->
                    if (player.isOnline) generation.ifCurrent(token) {
                        val key = if (failure != null || result == null) "failed" else result.name.lowercase()
                        openBuffs(player, notice = "buffs.result.$key")
                    }
                }
            }), exitButton = footer(player), columns = 1,
        ), pending = pending)
    }

    private fun buffName(player: Player, buff: TeamBuffSnapshot): String =
        if (buff.id in LOCALIZED_BUFFS) texts.getPlain(player, "buffs.names.${buff.id}") else buff.display

    private fun buffDescription(player: Player, buff: TeamBuffSnapshot): Component? =
        if (buff.id in LOCALIZED_BUFFS) texts.get(player, "buffs.descriptions.${buff.id}") else null

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
        }, presented.exitButton?.closeDialogBeforeAction == true)
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
    private fun field(player: Player, key: String, icon: DialogDecoration.FieldIcon) =
        DialogDecoration.fieldLabel(icon, texts.get(player, key))
    private fun stateKey(enabled: Boolean) = if (enabled) "state.on" else "state.off"
    private fun upgradeCost(player: Player, upgrade: TeamUpgradeSnapshot): Component {
        val parts = buildList {
            if (upgrade.moneyCost > 0) add(value("${numbers.format(upgrade.moneyCost)} 💰"))
            if (upgrade.pointsCost > 0) add(texts.get(player, "upgrades.points-value", mapOf("points" to numbers.format(upgrade.pointsCost))))
        }
        return if (parts.isEmpty()) texts.get(player, "upgrades.free")
        else parts.reduce { result, part -> result.append(value(" + ")).append(part) }
    }
    private fun value(value: Any): Component = Component.text(value.toString(), NamedTextColor.WHITE)

    companion object {
        private val LOCALIZED_BUFFS = setOf("regeneration", "speed", "haste", "resistance", "strength", "mining_xp", "fire_resistance")
        private val NAME_INPUT = PaperDialogInputId.of("name")
        private val TAG_INPUT = PaperDialogInputId.of("tag")
        private val PLAYER_INPUT = PaperDialogInputId.of("player")
        private val TEAM_INPUT = PaperDialogInputId.of("team")
        private val DESCRIPTION_INPUT = PaperDialogInputId.of("description")
    }
}

private fun Int?.orEmpty(): String = this?.toString().orEmpty()
