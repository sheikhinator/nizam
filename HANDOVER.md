# Atlas: handover for the next engineer (human or AI)

Atlas (package `com.nizam.app`) is one person's personal Android app. This repo holds its source and its build.

## Layout
- `project/` is the whole Android project (Gradle). Edit here.
- `.github/workflows/build-apk.yml` builds `project/` on every push. A commit message containing `[release]` publishes `Atlas.apk` as a GitHub Release (`build-N`).
- `tools/syncdown.sh` merges changes made by Chief (see below) into your branch.

## Chief (the in-app engineer)
The app has a screen called Chief. The owner talks to Claude there (using their own Anthropic key). Claude reads and edits `project/` through a copy downloaded from GitHub, the owner approves, and the change is committed to branch `atlas/live` with `[release]` in the message. CI builds it, and the app offers "Update now".
- Always start from the newest of `atlas/live` and the working branch.
- When the working branch is pushed, `atlas/live` is moved forward to match it. If `atlas/live` has commits the working branch lacks, run `tools/syncdown.sh` first.

## Conventions
- Kotlin 2.1, Compose, Material 3. Compile errors only show up in CI, so check imports and braces by eye.
- Tools are specs in `project/app/src/main/java/com/nizam/app/feature/nx/` (AiSpec, LogSpec, CalcSpec, ChatSpec, LinkSpec, ScreenSpec) joined in `Catalog.kt`.
- Shared data layer: `Roads.kt`; learning: `Mind.kt`; hints: `Noticer.kt`; in-app engineer: `feature/chief/`.
- Never commit API keys. Joytime's age filter (`JoySafety` in `feature/joytime/JoyNet.kt`) must stay.
- Owner preferences: plain language, no auto-SMS, location only with consent, ask before big changes.
