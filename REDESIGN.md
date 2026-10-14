# Atlas UI redesign — the plan to make it perfect for anyone

Atlas has strong bones (the `AtlasScreen` scaffold, design tokens, the living sky, the
twelve hubs). This is the plan to make the *whole* app feel like one calm, obvious,
billion-dollar product — without removing a single feature, and verified in CI at every step
(this is done as graded steps, never one blind rewrite).

## Principles (the "proper logic for anyone")
1. **One way to reach a thing.** No feature is browsable from three different screens.
2. **Search / ask first.** The fastest path is typing or asking Atlas, not hunting a grid.
3. **One main thing per screen.** Calm, not busy. Advanced tucked behind "More".
4. **One visual system.** Every screen inherits the look from the shared kit, so the app
   reads as one product. Calm by default; the charm (living sky, companion, warmth) stays.
5. **Earned depth.** Common things are surfaced; power is found by asking or searching.

## Information architecture
- **Four tabs, one job each:**
  - **Today** — your *now*. A glanceable scene: what matters today, nothing to hunt.
  - **Ask** — the agent and voice. One field to say what you want.
  - **Life** — *everything*, found by search or **one** clean browse.
  - **You** — identity, trust ("What Atlas did"), settings.
- **Kill the duplicate browse.** `Life`, `Spaces`, and `Apps` are three taxonomies of the
  same tools. Target: **Life is the one browse**; "spaces/modes" becomes a *view toggle*
  inside Life (hubs ⇄ spaces), not a separate screen; `Apps` is the media/utility shelf
  reached from Life. No tool is reachable from two parallel screens.
- **Each hub = one screen:** four big action tiles, a "More" grid, recent activity (per the
  existing charter).

## Visual system (change once, propagates everywhere)
- Refine the shared kit (`ui/design`): `AtlasScreen`, `HubCard`, `ListRow`, `SectionHeader`,
  `Group`, tiles. Calm default via `Motion.calm`; one accent + the hub spine; hairline
  separation (no heavy shadows); one hero animation per screen; spacing/corners/motion only
  from `Tokens.kt`. Inter type scale already set — keep it.

## Execution order (each step = one CI-verified `[release]`)
1. **Nav + browse de-dup.** Four-tab logic; Life shows each hub once. *(done / in progress)*
2. **Today → intention-first home.** Lead with the living "now" + one ask field; group the
   day's cards under clear sections; demote setup/what's-new behind a single "new here" row.
3. **Shared kit refresh.** Tighten `AtlasScreen` header, `HubCard`, `ListRow`, tiles to the
   calm language — lifts every screen at once.
4. **Life browse.** Merge Spaces in as a view toggle; one clean hub + trackers layout.
5. **Hub screens, then feature screens**, hub by hub, each against this charter.

## Rules
- Additive — **remove nothing**. Every destination stays reachable.
- Verify each step in CI (this container can't compile the full module). Ship as `[release]`
  so each step is installable and reviewable.
- Owner prefs: plain language; ask before big/irreversible changes.
