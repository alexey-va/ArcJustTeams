# justTeams compatibility — 2026-10-27

The active `JustTeams-2.8.1.jar` on Spawn and Survival has SHA-256
`e8e420c69ef41cc8c775ed2b0c4ad1fc73b6e2af115fffc59713a3a545f946a8`.
The older local 2.6.9 artifact and the 2.6.7 contract note are historical; neither
is evidence for the current implementation. All integration stays in
`src/main/kotlin/ru/ruscrafting/justteams/JustTeamsGateway.kt`. Exact read-only
`javap -p -c` inspection of the active artifact established these boundaries:

- `TeamManager.getPlayerTeamCached(UUID)` supplies identity and relationships
  without storage loads. `Team.getPlainTag`, `isPvpEnabled` and `getAllies`
  provide the small immutable `TeamNameplateTeam` view on the primary thread.
- `PvPListener.isBlocked` denies same-clan hits while team PvP is disabled and
  denies allied teams if either cache lists the other. With friendly fire on,
  projectile/cloud and TNT switches are separate; nameplates do not infer
  universal damage permission from the clan switch.
- `TeamBuffManager` exposes pool/query/lifecycle methods only. The complete
  transaction remains in private `TeamBuffMenu.toggle` / `persist`: elevated
  rights, current pool, active limit and points check/charge precede selection
  and asynchronous native persistence. `Team.setActiveBuffs(Collection)` only
  changes memory. Calling it alone would lose the selection or diverge across
  servers. The addon neither invokes storage nor duplicates the transaction.
- `JustTeamsBuffBridge` reflects into the existing private `toggle`. A native
  `TeamBuffMenu(plugin, null, team)` keeps the viewer null, while its genuine
  `ClickContext` carries the real player. Exact 2.8.1 constructor/title handling
  permits null; `refresh -> open` returns before scheduling when viewer is null.
  No inventory is created or opened. Version/descriptor drift disables editing.
- The native `cacheLock` protects a confirmed `teamNameCache` identity hit for
  the duration of the synchronous action, preventing `getTeamPoints` from
  falling through to synchronous storage. The addon never waits for SQL while
  holding that lock or on the gameplay thread.
- Native charging and selected-buff persistence are separate tasks in a fixed
  pool. Its points update clamps with `GREATEST(0, points-cost)` and native
  wrappers swallow storage/task errors. Calling two paid actions before the
  first updates the cache could overspend the quoted balance. Addon mutations
  therefore share one gate until a timed fixed-pool rendezvous proves prior
  native tasks have returned. It also gates free/disable actions so old saves
  cannot overtake a newer selection. Unknown fence outcomes permit fence-only
  recovery, never automatic replay of the native mutation.
- The borrowed executor is read only, never replaced or shut down. The fence
  verifies the fixed FIFO/AbortPolicy contract, permits one epoch at a time and
  bounds sentinel waits. Startup fences prior-generation native work; shutdown
  releases markers and exposes their drain. This prevents overlapping addon
  actions, not other native/server surfaces. Native task return and cached
  selection are not a durable or atomic payment/save acknowledgement.

Nameplate combat reasons come from the optional typed `ArcPvpService`
(`arc-dungeons-api:0.1.2`, ARC 1.4.477), isolated in
`ArcPvpNameplateBridge.kt`. Its common policy owns `arc_no_pvp` settlement
protection and team/alliance rules; native Lands relationships are deliberately
bypassed under the owning network policy and must not become a second denial
in this addon. `ALLOWED` covers that common policy only. CMI, minigames and, on
Spawn, WorldGuard/Denizen may still deny damage, so it does not become a
universal green permission indicator. Missing/older API or provider, unavailable
state and failures remain unknown. The adapter neither calls a denial notifier
nor probes combat with synthetic events.

Both live buff pools are enabled, require three nearby members within 20 blocks,
check every 60 ticks and permit two selected effects. Activation costs in clan
points are regeneration 0, speed 0, haste 100, resistance 150, strength 200,
mining XP 150 and fire resistance 150. Potion amplifier is 0; mining XP is 10%.
No prices, effects, points, balances or existing selections were changed.

Expedition requests are handled in ProxyARC
`src/main/kotlin/ru/arc/dungeonparty/DungeonPartyGatherManager.kt`. The live
Velocity server key is `spawn`; `classic` is an ops mirror name and is not a
registered Velocity destination. Keep those namespaces separate. The native
EliteMobs assembly and actual cross-server player arrival require runtime
acceptance after activation.
