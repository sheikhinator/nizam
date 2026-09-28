# Atlas design charter

Atlas has hundreds of features. This page is how it stays one calm, recognisable app.

## What never changes (the charm)
1. **Warm and alive.** The theme palettes, the living sky (time, weather, events) and the companion pet.
2. **Calm, not busy.** One main thing per screen. No badge spam, no endless feeds, no guilt notifications.
3. **Personal and local.** Urdu, English and Roman Urdu; prayer times shape the day; data stays on the phone.
4. **Quietly powerful.** Power is found by asking Atlas or searching, not by scrolling through icons.
5. **Tactile.** Haptics with meaning, spring motion, small celebrations for real milestones.

## Structure
- **Five tabs, forever:** Today · Life · Atlas · Search · Me. New features never add a tab.
- **Twelve Life hubs:** Money, Deen, Health, Learn, Home, Media, Memory, Work, Social, Tools, Create, Travel. Each hub is one screen: four big action tiles, a "More" grid, recent activity.
- **Four layers:** L1 surface (Today and tabs), L2 hub tiles, L3 tools (hub "More", search, Atlas), L4 invisible (runs in the background). Features rise or sink with your own use.
- **Everything is reachable by asking Atlas.**

## Building a screen
- Use `AtlasScreen` (large title, sticky header, loading skeleton, empty state) and the kit in `ui/design` (`Kit.kt`, `Atlas.kt`).
- Spacing, corners and motion come only from `Tokens.kt` (`Space`, `Radius`, `Motion`). No hard-coded colours: theme roles, or the hub's spine via `Hub.tint()`.
- A new component goes into the kit first, then gets used.
- One hero animation per screen at most. Everything respects "Reduce motion".
- Every destructive action offers Undo. Every list has an empty state. Nothing shows a bare spinner.

## Budgets
- Cold start under 1.2 s; scrolling at the display's refresh rate on the Infinix GT 50 Pro.
- Background work shares one scheduler.
- AI models download on demand; they are never bundled in the APK.

## Rollout
Foundation → the five tabs and twelve hubs → old screens migrated hub by hub → new features built only on the kit. Each release is checked against this page.
