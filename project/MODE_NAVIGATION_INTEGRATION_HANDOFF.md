# Modes & Navigation integration handoff

The feature screen is registered in `BacklogFeatures.all` as `modesnavigation` and exposed as
`nx/modesnavigation`. Search also includes it in `DESTINATIONS`.

## App-shell work

- Host `AtlasCommandPalette` from `ui/screens/search/SearchScreen.kt` above the navigation content
  so it can open from any route. The Search text field currently handles Ctrl+K locally; when the
  root handler is added, remove that local handler or ensure the root consumes the event to avoid
  opening two dialogs.
- Add the root keyboard bindings for Ctrl+K (palette) and Ctrl+F (Search).
- Apply app-wide language and layout direction from `ModeNavigationStore.get(context).state`.
  `ModeNavigationScreen` handles its own Urdu RTL layout, and Today uses the selected locale,
  numeral, and Hijri-date preferences. Apply RTL across the main shell, and preserve the selected
  Urdu or Roman Urdu mode when displaying screen labels.
- Record navigation at the app-shell level if recent destinations should include routes outside
  Search and Modules. Those two screens already record their own opens.

## Existing behavior to preserve

- Preferences stay in the encrypted, atomic, feature-owned file; do not add shared database or
  settings schema fields.
- Mode notification overrides call the existing notifications setting and restore the previous
  value when leaving an override mode or deleting it.
- Offline selections mark locally stored modules as travel priorities; they do not download files.
- Mission intensity is passed to daily mission generation from the Missions screen.

## Verification

Run the Android build and route, palette, profile-switch, notification-restore, and saved-search
checks in the integration environment. The current container does not provide Java or Gradle.