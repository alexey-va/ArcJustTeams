# ArcJustTeams

- This is a standalone addon. Never patch or access justTeams storage directly.
- The runtime contract is justTeams 2.6.7 (`c5c866d276de01e723035aaecead8a4c6d5fe01dca00c1228c4b200f7f8637c5`). Keep its integration behind `JustTeamsGateway` because the public source tag is stale.
- Player-facing navigation uses Paper Dialog through ARC Core. Keep Russian and English locale keys in parity.
- EliteMobs 10.8 party calls stay behind the one-time cached `EliteMobsPartyBridge` until upstream issue #217 provides a result-based API. Never reflect into ARC or arc-core classes.
- Lands links are owner-selected and additive. Never remove land members automatically.
- EliteMobs completions may record activity, but must not grant money, points, or loot without a separate economy balance review.
- Focused verification: `../ArcGiveaways/gradlew check` from this directory.
