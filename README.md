# ArcJustTeams

Addon for justTeams 2.6.9 on RusCrafting. Bare `/team`, `/clan`, `/guild`, and `/clans` open a native Paper Dialog flow. `/team quests`, `/clan quests` and `/guild quests` open the shared ArcRanks clan quest board; other explicit subcommands retain their native routes.

The hub adds:

- native creation, team catalog, member/invite/request management, settings, alliances, quests, buffs, and confirmed tier upgrades;
- multiple shared Lands settlements: every team member may link only a settlement they own, and team members are added to every linked settlement without removing existing residents;
- an EliteMobs group-run counter for teams;
- no hand-off from the team flow to justTeams chest menus.

Clan quests belong to ArcRanks. The addon publishes immutable cached clan membership through `ClanMembershipProvider` and opens the same native flow through `ClanQuestMenuApi` (`arc-ranks-api:0.1.3`, compile-only). Shared dialogue history is retained. ArcRanks is optional; its absence produces an explicit unavailable state. Disable both `features.team_quests` in justTeams/config.yml and `settings.enabled` in justTeams/quests.yml to retire the old quest engine. Existing clan balances, points and native quest records are not rewritten.

The separate EliteMobs run counter remains observational and grants no money, team points or loot; it no longer forwards progress into native JustTeams quests.

The hub uses Russian for every client by default. Set `locale.default: en` for
English, or `locale.use-client-locale: true` to follow the client's language and
fall back to `locale.default` for unsupported languages. Both language catalogs
remain available. There is no plugin reload command; apply file changes with the
next server restart.

Build with `../ArcGiveaways/gradlew check`. The shaded plugin is written to
`build/libs/ArcJustTeams-0.3.21.jar`. Delayed post-action refreshes are bound to
the current native-dialog visit and are ignored after navigation or dismissal;
ordinary internal transitions keep the dialog window open.
