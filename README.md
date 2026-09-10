# ArcJustTeams

Addon for justTeams 2.6.7 on RusCrafting. Bare `/team`, `/clan`, `/guild`, and `/clans` open a native Paper Dialog flow; existing explicit subcommands remain available as technical routes.

The hub adds:

- native creation, team catalog, member/invite/request management, settings, alliances, quests, buffs, and confirmed tier upgrades;
- multiple shared Lands settlements: every team member may link only a settlement they own, and team members are added to every linked settlement without removing existing residents;
- an EliteMobs group-run counter for teams;
- no hand-off from the team flow to justTeams chest menus.

EliteMobs tracking does not grant money, team points, or loot. Optional progress for an already configured justTeams custom quest can be enabled with `elite-mobs.quest-id` after an economy review.

Build with `../ArcGiveaways/gradlew check`. The shaded plugin is written to `build/libs/ArcJustTeams-0.2.0.jar`.
