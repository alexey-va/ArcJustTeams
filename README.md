# ArcJustTeams

Addon for justTeams 2.8.1 on RusCrafting. Bare `/team`, `/clan`, `/guild`, and `/clans` open a native Paper Dialog flow. `/team quests`, `/clan quests` and `/guild quests` open the shared ArcRanks clan quest board; other explicit subcommands retain their native routes.

The hub adds:

- native creation, a ranked team catalog, member/invite/request management, settings, ranked alliance suggestions, quests, localized buff details, and confirmed tier upgrades;
- named shared Lands settlements on the root with native spawn travel and a paginated settlement browser;
- multiple shared Lands settlements: every team member may link only a settlement they own, and team members are added to every linked settlement without removing existing residents;
- an EliteMobs group-run counter for teams;
- one multiline overhead display with rank, optional clan, nickname and health/absorption, with a viewer-specific PvP icon and exclusive ArcEvents target ownership;
- no hand-off from the team flow to justTeams chest menus.

Clan quests belong to ArcRanks. The addon publishes immutable cached clan membership through `ClanMembershipProvider` and opens the same native flow through `ClanQuestMenuApi` (`arc-ranks-api:0.1.3`, compile-only). Shared dialogue history is retained. ArcRanks is optional; its absence produces an explicit unavailable state. Disable both `features.team_quests` in justTeams/config.yml and `settings.enabled` in justTeams/quests.yml to retire the old quest engine. Existing clan balances, points and native quest records are not rewritten.

The separate EliteMobs run counter remains observational and grants no money, team points or loot; it no longer forwards progress into native JustTeams quests.

The hub uses Russian for every client by default. Set `locale.default: en` for
English, or `locale.use-client-locale: true` to follow the client's language and
fall back to `locale.default` for unsupported languages. Both language catalogs
remain available. There is no plugin reload command; apply file changes with the
next server restart.

Build with `../ArcGiveaways/gradlew check`. The shaded plugin is written to
`build/libs/ArcJustTeams-0.3.27.jar`. Delayed post-action refreshes are bound to
the current native-dialog visit and are ignored after navigation or dismissal;
ordinary internal transitions keep the dialog window open.

Nameplates bundle core 2.7.77 and require ArcCore with the `ArcNameplateClaim` API (
ArcCore 0.1.24), PacketEvents and PlaceholderAPI. ArcEvents 0.5.28 publishes exclusive ownership
for participants, escrow recovery and preparing phases. Known native world/protection
denials are shown per viewer. The optional
`ArcPvpService` (`arc-dungeons-api:0.1.2`, ARC 1.4.477) owns settlement and
clan denials; `ArcPvpNameplateBridge` queries it without sending denial messages.
Older providers safely show an unknown state. Native Lands relationships are
not used as a second policy where ARC intentionally bypasses them. The health row
shows red ⚔ when native checks and ARC permit PvP, green ⛨ for a known denial,
and ? when the ARC query is unavailable. Other combat listeners may still veto
a hit; the icon reflects the native and ARC checks. The rank comes from the
LuckPerms prefix; the real nickname is literal white text. AFK is omitted.
The ops TAB configuration hides its native identity only on Spawn/Survival
when `%arcjustteams_nameplate_ready%` reports ownership for that target. Event
claims and unavailable capture restore the native identity. Source-backed
previews do not establish client appearance.

The complete display follows the native nickname's world anchor with one-tick
position interpolation. Rank, optional clan, nickname and compact health row
share its camera plane, full text brightness, shadow and background. Rank is
on its own top row; the lower edge clears the projected head by 0.30 blocks
at a horizontal view. This follower does not inherit passenger movement prediction.
Identity updates remain active for idle viewers and third-person camera views;
normal packet budgets and privacy checks still apply.

Buff details and controls use the unchanged native pool and prices. The addon
reflects into the verified justTeams 2.8.1 headless native action; it never
opens its chest menu or accesses storage. Mutations serialize through a bounded
native queue fence, including startup and interrupted operations. The native
API still supplies no durable payment/save receipt; see
[the exact runtime compatibility evidence](docs/justteams-compatibility.md).
Settlement travel delegates to `lands:lands spawn <name>` as the player and
therefore retains Lands fees, combat gates, warmup and destination checks. An
accepted command is not a confirmed arrival.
