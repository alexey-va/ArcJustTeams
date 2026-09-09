package ru.ruscrafting.justteams

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.DialogTextLayout

internal class TeamDialogs(
    private val runtime: PaperDialogRuntime,
    private val texts: Texts,
    private val teams: JustTeamsGateway,
    private val lands: LandsBridge?,
    private val minimumDungeonMembers: Int,
) {
    fun begin(player: Player) {
        runtime.beginFlow(player)
        open(player)
    }

    fun open(player: Player, notice: String? = null) {
        val team = teams.team(player.uniqueId)
        if (team == null) return show(player, PaperDialogScreen(
            id = "arcjustteams.empty",
            title = texts.get(player, "title"),
            body = listOfNotNull(notice?.let { DialogTextLayout.modelBody(texts.get(player, it)) }, DialogTextLayout.modelBody(texts.get(player, "no-team"))),
            buttons = listOf(
                native(player, null, "buttons.create", "buttons.create-tooltip"),
                native(player, "top", "buttons.browse", "buttons.browse-tooltip"),
            ),
            exitButton = close(player),
            columns = 2,
        ))

        val landName = lands?.name(team.landId)?.let { Component.text(it, NamedTextColor.WHITE) }
            ?: texts.get(player, "summary.none")
        val rows = listOf(
            texts.get(player, "summary.name") to Component.text("[${team.tag}] ${team.name}", NamedTextColor.WHITE),
            texts.get(player, "summary.tier") to Component.text(team.tier, NamedTextColor.WHITE),
            texts.get(player, "summary.points") to Component.text(team.points, NamedTextColor.WHITE),
            texts.get(player, "summary.members") to Component.text("${team.onlineCount}/${team.memberCount}", NamedTextColor.WHITE),
            texts.get(player, "summary.land") to landName,
            texts.get(player, "summary.dungeons") to Component.text(team.dungeonRuns, NamedTextColor.WHITE),
        )
        show(player, PaperDialogScreen(
            id = "arcjustteams.root",
            title = texts.get(player, "title"),
            body = listOfNotNull(notice?.let { DialogTextLayout.modelBody(texts.get(player, it)) }, DialogTextLayout.modelBody(texts.get(player, "intro")), DialogTables.body(rows)),
            buttons = listOf(
                button("members", player, "buttons.members", tooltipKey = "buttons.members-tooltip") { openMembers(player) },
                native(player, "quests", "buttons.quests", "buttons.quests-tooltip"),
                button("upgrades", player, "buttons.upgrades", tooltipKey = "buttons.upgrades-tooltip") { openUpgrades(player) },
                native(player, "buffs", "buttons.buffs", "buttons.buffs-tooltip"),
                button("land", player, "buttons.land", tooltipKey = "buttons.land-tooltip") { openLands(player) },
                button("dungeons", player, "buttons.dungeons", tooltipKey = "buttons.dungeons-tooltip") { openDungeons(player) },
                native(player, "settings", "buttons.social", "buttons.social-tooltip"),
                native(player, null, "buttons.native", "buttons.native-tooltip"),
            ),
            exitButton = close(player),
            columns = 2,
        ))
    }

    private fun openMembers(player: Player, requestedPage: Int = 0) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val members = teams.members(team.raw)
        val pages = maxOf(1, (members.size + 9) / 10)
        val page = requestedPage.coerceIn(0, pages - 1)
        val rows = members.drop(page * 10).take(10).map { member ->
            val state = if (member.online) "●" else "○"
            Component.text("$state ${member.name}", if (member.online) NamedTextColor.GREEN else NamedTextColor.GRAY) to
                texts.get(player, "members.roles.${member.role}")
        }
        val buttons = buildList {
            add(native(player, null, "members.manage", "members.manage-tooltip"))
            if (pages > 1) {
                if (page > 0) add(button("members_previous", player, "members.previous") { openMembers(player, page - 1) })
                if (page < pages - 1) add(button("members_next", player, "members.next") { openMembers(player, page + 1) })
            }
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.members",
            title = texts.get(player, "members.title"),
            body = listOf(DialogTextLayout.modelBody(texts.get(player, "members.intro", mapOf("page" to page + 1, "pages" to pages))), DialogTables.body(rows)),
            buttons = buttons,
            exitButton = footer(player),
            columns = 2,
        ))
    }

    private fun openUpgrades(player: Player, notice: String? = null) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val upgrade = teams.upgrade(team.raw)
        val rows = buildList {
            add(texts.get(player, "upgrades.level") to Component.text(upgrade.currentTier, NamedTextColor.WHITE))
            add(texts.get(player, "upgrades.members") to Component.text(upgrade.maxMembers, NamedTextColor.WHITE))
            if (upgrade.nextTier != null) {
                add(texts.get(player, "upgrades.next") to Component.text(upgrade.nextTier, NamedTextColor.WHITE))
                add(texts.get(player, "upgrades.next-members") to Component.text(upgrade.nextMaxMembers ?: upgrade.maxMembers, NamedTextColor.WHITE))
                add(texts.get(player, "upgrades.cost") to Component.text(upgrade.cost.orEmpty(), NamedTextColor.WHITE))
            }
        }
        val buttons = if (upgrade.nextTier == null) emptyList() else listOf(
            button("upgrade_confirm", player, "upgrades.continue") { openUpgradeConfirm(player, team, upgrade) },
        )
        show(player, PaperDialogScreen(
            id = "arcjustteams.upgrades",
            title = texts.get(player, "upgrades.title"),
            body = listOfNotNull(notice?.let { DialogTextLayout.modelBody(texts.get(player, it)) }, DialogTextLayout.modelBody(texts.get(player, if (upgrade.nextTier == null) "upgrades.maximum" else "upgrades.intro")), DialogTables.body(rows)),
            buttons = buttons,
            exitButton = footer(player),
            columns = 1,
        ))
    }

    private fun openUpgradeConfirm(player: Player, team: TeamSnapshot, upgrade: TeamUpgradeSnapshot) {
        show(player, PaperDialogScreen(
            id = "arcjustteams.upgrades.confirm",
            title = texts.get(player, "upgrades.confirm-title"),
            body = listOf(
                DialogTextLayout.modelBody(texts.get(player, "upgrades.confirm", mapOf("tier" to upgrade.nextTier.orEmpty(), "cost" to upgrade.cost.orEmpty()))),
            ),
            buttons = listOf(button("upgrade_buy", player, "upgrades.buy") {
                val freshTeam = teams.team(player.uniqueId)
                val freshUpgrade = freshTeam?.takeIf { it.id == team.id }?.let { teams.upgrade(it.raw) }
                if (freshUpgrade == null || freshUpgrade.currentTier != upgrade.currentTier || freshUpgrade.nextTier != upgrade.nextTier || freshUpgrade.cost != upgrade.cost) {
                    openUpgrades(player, "upgrades.changed")
                } else {
                    teams.tryUpgrade(player)
                    open(player)
                }
            }),
            exitButton = footer(player),
            columns = 1,
        ))
    }

    private fun openLands(player: Player, notice: String? = null) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        val owned = lands?.ownedLands(player).orEmpty()
        val body = buildList {
            add(DialogTextLayout.modelBody(texts.get(player, "land.intro")))
            notice?.let { add(DialogTextLayout.modelBody(texts.get(player, it))) }
            if (team.ownerId != player.uniqueId) add(DialogTextLayout.modelBody(texts.get(player, "land.forbidden")))
            else if (owned.isEmpty()) add(DialogTextLayout.modelBody(texts.get(player, if (lands == null) "notice.integration-missing" else "land.empty")))
            owned.firstOrNull { it.id == team.landId }?.let { selected ->
                add(DialogTables.body(listOf(
                    texts.get(player, "land.name") to Component.text(selected.name),
                    texts.get(player, "land.chunks") to Component.text("${selected.chunks}/${selected.maxChunks}"),
                ), frame = DialogTables.Frame.LEGENDARY))
            }
        }
        val buttons = if (team.ownerId != player.uniqueId) emptyList() else owned.mapIndexed { index, land ->
            val selected = land.id == team.landId
            if (selected) button("land_$index", player, "buttons.bound", mapOf("land" to land.name)) {}
            else button("land_$index", player, "buttons.bind", mapOf("land" to land.name), "buttons.bind-tooltip") {
                val fresh = teams.team(player.uniqueId)
                val stillOwned = lands?.ownedLands(player)?.any { it.id == land.id } == true
                if (fresh?.id != team.id || fresh.ownerId != player.uniqueId || !stillOwned) openLands(player, "land.changed")
                else {
                    teams.bindLand(fresh.raw, land.id)
                    openLands(player, "notice.bound")
                }
            }
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.land",
            title = texts.get(player, "land.title"),
            body = body,
            buttons = buttons,
            exitButton = footer(player),
            columns = 2,
        ))
    }

    private fun openDungeons(player: Player) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        show(player, PaperDialogScreen(
            id = "arcjustteams.dungeons",
            title = texts.get(player, "dungeons.title"),
            body = listOf(
                DialogTextLayout.modelBody(texts.get(player, "dungeons.body", mapOf("minimum" to minimumDungeonMembers))),
                DialogTables.body(listOf(
                    texts.get(player, "summary.dungeons") to Component.text(team.dungeonRuns),
                    texts.get(player, "dungeons.minimum") to Component.text(minimumDungeonMembers),
                ), frame = DialogTables.Frame.ARTIFACT),
            ),
            buttons = listOf(button("open_dungeons", player, "dungeons.open", tooltipKey = "dungeons.open-tooltip", close = true) { player.performCommand("dungeon") }),
            exitButton = footer(player),
            columns = 1,
        ))
    }

    private fun show(player: Player, screen: PaperDialogScreen) =
        runtime.open(player, screen, null, {}, false)

    private fun native(player: Player, section: String?, key: String, tooltipKey: String? = null) =
        button("native_${section ?: "root"}", player, key, tooltipKey = tooltipKey, close = true) { teams.openNative(player, section) }

    private fun button(
        id: String,
        player: Player,
        key: String,
        values: Map<String, Any> = emptyMap(),
        tooltipKey: String? = null,
        close: Boolean = false,
        action: () -> Unit,
    ) = PaperDialogButton(
        id = PaperDialogActionId.of(id),
        label = texts.get(player, key, values),
        tooltip = tooltipKey?.let { texts.get(player, it, values) } ?: Component.empty(),
        width = 230,
        closeDialogBeforeAction = close,
        onClick = { action() },
    )

    private fun footer(player: Player) = button("back", player, "buttons.back") {}.copy(width = 200)
    private fun close(player: Player) = button("close", player, "buttons.close", close = true) {}.copy(width = 200)
}

private fun Int?.orEmpty(): String = this?.toString().orEmpty()
