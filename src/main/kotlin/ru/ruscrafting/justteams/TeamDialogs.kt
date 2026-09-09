package ru.ruscrafting.justteams

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen

internal class TeamDialogs(
    private val runtime: PaperDialogRuntime,
    private val texts: Texts,
    private val teams: JustTeamsGateway,
    private val lands: LandsBridge,
    private val minimumDungeonMembers: Int,
) {
    fun open(player: Player, notice: String? = null) {
        val team = teams.team(player.uniqueId)
        if (team == null) return show(player, PaperDialogScreen(
            id = "arcjustteams.empty",
            title = texts.get(player, "title"),
            body = listOfNotNull(notice?.let { PaperDialogBody(texts.get(player, it), 468) }, PaperDialogBody(texts.get(player, "no-team"), 468)),
            buttons = listOf(
                native(player, null, "buttons.create"),
                native(player, "top", "buttons.browse"),
            ),
            exitButton = close(player),
            columns = 2,
        ))

        val landName = lands.name(team.landId)?.let { Component.text(it, NamedTextColor.WHITE) }
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
            body = listOfNotNull(notice?.let { PaperDialogBody(texts.get(player, it), 468) }, PaperDialogBody(texts.get(player, "intro"), 468), DialogTables.body(rows)),
            buttons = listOf(
                button("members", player, "buttons.members") { openMembers(player) },
                native(player, "quests", "buttons.quests"),
                button("upgrades", player, "buttons.upgrades") { openUpgrades(player) },
                native(player, "buffs", "buttons.buffs"),
                button("land", player, "buttons.land") { openLands(player) },
                button("dungeons", player, "buttons.dungeons") { openDungeons(player) },
                native(player, "settings", "buttons.social"),
                native(player, null, "buttons.native"),
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
            add(native(player, null, "members.manage"))
            if (pages > 1) {
                add(button("members_previous", player, "members.previous") { openMembers(player, page - 1) })
                add(button("members_next", player, "members.next") { openMembers(player, page + 1) })
            }
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.members",
            title = texts.get(player, "members.title"),
            body = listOf(PaperDialogBody(texts.get(player, "members.intro", mapOf("page" to page + 1, "pages" to pages)), 468), DialogTables.body(rows)),
            buttons = buttons,
            exitButton = back(player),
            columns = 2,
        ))
    }

    private fun openUpgrades(player: Player) {
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
            body = listOf(PaperDialogBody(texts.get(player, if (upgrade.nextTier == null) "upgrades.maximum" else "upgrades.intro"), 468), DialogTables.body(rows)),
            buttons = buttons,
            exitButton = back(player),
            columns = 1,
        ))
    }

    private fun openUpgradeConfirm(player: Player, team: TeamSnapshot, upgrade: TeamUpgradeSnapshot) {
        show(player, PaperDialogScreen(
            id = "arcjustteams.upgrades.confirm",
            title = texts.get(player, "upgrades.confirm-title"),
            body = listOf(
                PaperDialogBody(texts.get(player, "upgrades.confirm", mapOf("tier" to upgrade.nextTier.orEmpty(), "cost" to upgrade.cost.orEmpty())), 468),
            ),
            buttons = listOf(button("upgrade_buy", player, "upgrades.buy") {
                if (teams.team(player.uniqueId)?.id == team.id) teams.tryUpgrade(player)
                open(player)
            }),
            exitButton = backTo(player, "upgrades.back") { openUpgrades(player) },
            columns = 1,
        ))
    }

    private fun openLands(player: Player) {
        val team = teams.team(player.uniqueId) ?: return open(player)
        if (team.ownerId != player.uniqueId) {
            open(player, "land.forbidden")
            return
        }
        val owned = lands.ownedLands(player)
        val body = buildList {
            add(PaperDialogBody(texts.get(player, "land.intro"), 468))
            if (owned.isEmpty()) add(PaperDialogBody(texts.get(player, if (lands.available()) "land.empty" else "notice.integration-missing"), 468))
        }
        val buttons = owned.mapIndexed { index, land ->
            val selected = land.id == team.landId
            button("land_$index", player, if (selected) "buttons.bound" else "buttons.bind", mapOf("land" to land.name)) {
                teams.bindLand(team.raw, land.id)
                open(player, "notice.bound")
            }
        }
        show(player, PaperDialogScreen(
            id = "arcjustteams.land",
            title = texts.get(player, "land.title"),
            body = body,
            buttons = buttons,
            exitButton = back(player),
            columns = 2,
        ))
    }

    private fun openDungeons(player: Player) {
        show(player, PaperDialogScreen(
            id = "arcjustteams.dungeons",
            title = texts.get(player, "dungeons.title"),
            body = listOf(PaperDialogBody(texts.get(player, "dungeons.body", mapOf("minimum" to minimumDungeonMembers)), 468)),
            buttons = listOf(button("open_dungeons", player, "dungeons.open", close = true) { player.performCommand("dungeon") }),
            exitButton = back(player),
            columns = 1,
        ))
    }

    private fun show(player: Player, screen: PaperDialogScreen) =
        runtime.open(player, screen, { open(player) }, {}, true)

    private fun native(player: Player, section: String?, key: String) =
        button("native_${section ?: "root"}", player, key, close = true) { teams.openNative(player, section) }

    private fun button(
        id: String,
        player: Player,
        key: String,
        values: Map<String, Any> = emptyMap(),
        close: Boolean = false,
        action: () -> Unit,
    ) = PaperDialogButton(
        id = PaperDialogActionId.of(id),
        label = texts.get(player, key, values),
        tooltip = Component.empty(),
        width = 230,
        closeDialogBeforeAction = close,
        onClick = { action() },
    )

    private fun back(player: Player) = button("back", player, "buttons.back") { open(player) }.copy(width = 200)
    private fun backTo(player: Player, id: String, action: () -> Unit) = button(id, player, "buttons.back", action = action).copy(width = 200)
    private fun close(player: Player) = button("close", player, "buttons.close", close = true) {}.copy(width = 200)
}

private fun Int?.orEmpty(): String = this?.toString().orEmpty()
