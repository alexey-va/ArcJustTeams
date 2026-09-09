# ArcJustTeams

Addon for justTeams 2.6.7 on RusCrafting. It replaces only the empty `/team`, `/clan`, and `/guild` commands with a native Paper Dialog hub; all existing subcommands continue to be handled by justTeams.

The hub adds:

- team overview, member list, and a confirmed tier-upgrade flow;
- an owner-selected link to a Lands settlement;
- an EliteMobs group-run counter for teams;
- safe hand-offs to the existing justTeams quest, buff, settings, and management screens.

EliteMobs tracking does not grant money, team points, or loot. Optional progress for an already configured justTeams custom quest can be enabled with `elite-mobs.quest-id` after an economy review.

Build with `./gradlew check`. The shaded plugin is written to `build/libs/ArcJustTeams-0.1.0.jar`.
