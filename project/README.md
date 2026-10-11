# Nizam

A private, offline-first personal companion app for Android. Kotlin + Jetpack Compose + Room.
No accounts, no cloud, no analytics. Everything lives on the device.

**Version 0.6 — a personal development app, not a planner.** All 13 modules plus AI courses, an assistant, and Dawn news. Tasks, Habits, Prayer, Finance, Diet, Gym, Notes, Library, Diary,
Quran, Hadith, Learning, Survival and Quotes, plus the Day Sheet, cross-module search and settings.

---

## Getting an APK without a PC

1. Create a new **public or private repo** on GitHub (e.g. `nizam`).
2. Upload the **contents** of this zip to the repo root. The repo root must contain
   `settings.gradle.kts`, `build.gradle.kts`, the `app/` folder and the `.github/` folder.
   (If you drag the zip into GitHub's web uploader, unzip it first — GitHub does not unzip for you.)
3. Go to the **Actions** tab and enable workflows if prompted.
4. The `Build APK` workflow runs on every push. You can also start it manually:
   Actions → Build APK → **Run workflow**.
5. When it finishes (about 4–8 minutes on the first run), open the run and download the
   **nizam-debug-apk** artifact. Unzip it to get `app-debug.apk`.
6. On your phone: open the APK, allow "install from unknown sources" for your browser or file
   manager when prompted, and install.

The APK is a **debug build**, signed with the standard Android debug key. That is fine for
installing on your own phone. It is not suitable for the Play Store — that needs a release build
signed with your own keystore.

## Building on a computer instead

Open the folder in Android Studio (Koala or newer), let it sync, then Run. Or from a terminal with
the Android SDK installed:

```
gradle wrapper          # once, to generate ./gradlew
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

---

## If the first build fails

This scaffold was written without a compiler to test against, so expect one or two fixes on the
first run. The workflow log tells you exactly what broke — scroll to the first line containing
`error:`.

The usual suspects, and what to do:

| Symptom | Fix |
|---|---|
| `Unsupported class file major version` | JDK mismatch — the workflow pins 17, don't change it |
| AGP / Gradle version complaint | bump `gradle-version` in `.github/workflows/build-apk.yml` to match what AGP 8.5.2 asks for |
| Unresolved reference in a Compose call | a library version moved; check the exact import named in the error |
| Room "cannot find implementation" | KSP didn't run — confirm the `com.google.devtools.ksp` plugin line survived the upload |

Paste the failing lines into Claude or the AI Studio agent and ask for a fix to that one file.

## Project layout

```
app/src/main/java/com/nizam/app/
├─ NizamApplication.kt      Application + AppContainer (manual DI)
├─ MainActivity.kt          single Activity, Compose host
├─ ui/
│  ├─ theme/                colours, type scale, module spine colours
│  ├─ nav/                  bottom nav + NavHost
│  ├─ components/           SpineSection, StatTile, StreakDot, EmptyState
│  └─ screens/              today · modules · tasks · search · settings
└─ data/
   ├─ db/                   Room entities, DAOs, database
   ├─ prefs/                DataStore settings
   └─ repo/                 repositories
```

## Adding the next module

Each module is a phase in the spec document. Open the project in Google AI Studio Build mode, or
Android Studio with an AI assistant, and paste one phase prompt at a time. Always start the prompt
with:

> Keep the existing architecture, theme tokens, navigation, database conventions and component
> library exactly as they are. Add a Room migration rather than destroying existing data. Build only
> what I describe below.

Recommended order: Prayer → Finance → Diary → Diet → Gym → Notes → Library → Quran → Hadith →
Learning → Survival → Quotes.

---

## What each module does

| Module | Works today | Needs something from you |
|---|---|---|
| Tasks & Habits | add, complete, delete tasks; habits with daily dots | — |
| Prayer | five prayers with on-time / late / jamaah / qada, offline prayer time calculation, 30-day stats | set your latitude and longitude once (defaults to Lahore) |
| Finance | income and expenses, categories, monthly totals, top categories, daily spend | — |
| Diet | meals by slot, calories and protein, water, weight log | targets are hardcoded at 2200 kcal / 3000 ml; change them in `DietScreen` |
| Gym | log sets, previous-session reference, estimated 1RM, volume | — |
| Notes | markdown notes, `[[wikilinks]]`, backlinks, search | — |
| Library | import books via storage picker, built-in reader for TXT and MD | PDF and EPUB hand off to your device's reader app |
| Diary | entries, 1–5 mood, emotion chips, rotating prompts, mood average | — |
| Quran | full surah list, Arabic + English, cached offline after first open | internet once per surah |
| Hadith | six major collections, Arabic + English with grading and reference | free API key from hadithapi.com, entered in Settings |
| Learning | tracks, study minutes, SM-2 spaced repetition flashcards | — |
| Survival | offline topics, seeded checklists, one-tap emergency numbers | — |
| Quotes | your own collection, deterministic quote of the day | you add the quotes |

### Two deliberate design decisions

**Scripture is never generated.** Quran text comes from the Al Quran Cloud API and hadith from
hadithapi.com, both cached into the local database. If a source is unreachable or a key is missing,
the module says so rather than displaying anything unsourced.

**First aid is not generated either.** The Survival module ships practical non-medical guidance and
checklists. The first aid topic tells you to get trained and to keep a printed manual from a
recognised body. Add your own vetted material.

### Prayer times

Calculated on-device from solar geometry — no network, no API. Accurate to roughly a minute.
Check them against your local masjid once; if they are consistently off, switch the calculation
method (Karachi, MWL, ISNA, Egyptian, Umm al-Qura) and the Asr madhab in the Prayer module.

### Database note

The database is at version 2 and this jump uses a destructive migration, because version 1 only
existed in the earlier scaffold. **From now on, write real migrations** — the app is where your
data lives.

---

## AI features (need a Gemini key in Settings)

Get a free key at aistudio.google.com, paste it into Settings → API keys. Then:

**Courses** — the big one. Give it a subject, your level, hours per week and a deadline; it designs
a full syllabus of units and lessons, schedules every lesson across your calendar at five study days
a week, and writes each lecture in full on demand — proper teaching material with worked examples,
common mistakes, a summary and practice questions, not bullet-point summaries. Today's scheduled
lessons appear on the Day Sheet. Every lesson has an Ask panel for follow-up questions.

**Assistant** — a chat tab that can read your own tracked data (open tasks, this month's spending by
category, training in the last week, study hours, prayers logged, recent mood) and answer questions
grounded in it. Toggle "Using my data" off for a plain chat.

**Ask panels** — in Diet (what to eat to hit protein), Gym (analyse my progression), Finance (where
am I overspending), Diary (patterns across my entries), Survival (detailed step-by-step on any
topic), News (background on a story), and every course lesson.

**Model** — defaults to `gemini-2.5-flash`. Switch to Pro in Settings for better lectures and slower
generation. If Google renames its models, edit the name in Settings and the app keeps working.

A note on cost: it's your key and your quota. Generating a full lecture is a large request, and
generating a whole course is many of them.

## News

Dawn's RSS feeds — Home, Latest, Pakistan, World, Business, Sport, Opinion. Headlines and summaries
are cached locally so the last fetch is readable offline; the full story opens on dawn.com. Article
text belongs to Dawn, so the app links out rather than reproducing it.

## Hadith search

Type in the search box to search everything already downloaded. "Search all" queries every
collection through the API and caches the results. Hadith text still comes only from the API — the
AI never writes hadith, and it never writes Quranic text either. Fabricating something and
attributing it to the Prophet is the one thing worth being strict about, and it costs you nothing
elsewhere in the app.

---

## What 0.4 changed

**Lectures are typed content, not markdown.** The AI now returns structured blocks — headings,
paragraphs, worked examples, numbered procedures, formulas, takeaway panels, tap-to-reveal practice
questions, citations — and the app renders each with its own typography and colour. Every lesson
gets a pull quote and a tone/accent pair derived from its title, so no two lessons look identical
and nothing is hand-picked.

**XP, levels and streaks, derived not stored.** Your score is computed from what you actually did —
tasks completed, habits, prayers logged, sets, study minutes, lessons finished, diary entries, food
logged. Nothing to migrate, nothing that can drift out of sync. Levels need 25% more each time.
The home screen shows level, streak, XP today and a 30-day activity bar.

**Insights fire from real patterns.** Templates with selectors, filled with your own numbers: your
strongest weekday and by what percentage, whether prayer holds up better on training days, your
typical study block length, whether this week is lighter than last. The Insights screen has an Ask
panel that hands your last 30 days to the AI for analysis.

**Onboarding survey.** Four questions about what you're fixing, when your day starts, how much you
can honestly give, and what usually breaks it — then your calorie and water targets. No more
hardcoded 2200 kcal. Re-runnable from Settings.

**Home screen rebuilt** around greeting, level card, top insight, and a single scannable list of
today with a coloured spine per module.

## Gemini models

Defaults to **`gemini-flash-latest`** — an alias Google repoints at the current Flash model, so the
app doesn't break when models are renamed or retired. (`gemini-2.5-flash`, the old default, is being
wound down; 2.5 Pro shuts down in October 2026.)

Settings has **Fetch models for my key**, which calls the API's list endpoint and shows every model
your key can actually use with `generateContent`. Tap one to switch.

---

## Missions and goals

Tell it what you want. It writes the campaign.

**Goals → dated campaigns.** Type a goal ("earn PKR 30,000 this week", "bench 90kg by December",
"finish the statistics course"), optionally why it matters and a deadline. The planner reads your
actual history — training numbers, study minutes, income and spend this month, mission completion
rate over the last two weeks, your streak — and returns a strategy plus 6 to 20 dated missions,
each one a single action with a number attached and a finish line. Every mission carries a
rationale: one sentence on why it earns its place.

**Daily missions.** Four to six each day, tuned to your own numbers rather than an average
person's. If you normally log 12 sets, 15 is medium. If your streak is zero, day one is winnable.
At least one mission targets whatever looks weakest in your data.

**Auto-verification.** Each mission carries a metric the app can measure — sets logged, study
minutes, prayers on time, water, calories under a ceiling, spend under a ceiling, income recorded,
lessons finished, diary written, notes created. The app checks your real data and completes the
mission itself. Progress bars show how far along you are. `MANUAL` exists for things the app
genuinely can't see, and the generator is told to avoid it where a real metric fits.

**Difficulty and XP.** easy 10, medium 20, hard 35, very_hard 60 — feeding the same level and
streak system as everything else.

The Assistant sees your goals and today's missions too, so "what should I focus on" answers against
the actual plan. Missions is now a bottom tab.

---

## Self — the development layer

Tracking tells you what you did. This tells you who you're becoming.

**Character.** Six attributes that grow from different kinds of day: Discipline, Health, Knowledge,
Faith, Wealth, Mind. Each levels independently off your real logs, so the shape of the profile shows
what you've actually been building and what you've been avoiding. "Read me honestly" hands the
profile, your identity statements and your recent reviews to the AI and asks for a portrait — what
the shape says, the gap between who you say you want to be and what the data shows, the strength
you're underusing, and one change for the next seven days.

**Review.** An evening review — what went well, what didn't, one change — which is then read back
against the same day's data. Where your account and the numbers disagree, it says so. The weekly
review compares this week to last, names the pattern that matters most and the thing you're
avoiding, and ends with three changes each carrying a number and a day.

**Identity.** Not goals — statements of who you are deciding to be. "I am someone who trains whether
or not I feel like it." They feed the character reading, so the app can hold the gap between the
claim and the evidence.

**Growth courses.** The course engine pointed at how you work rather than what you know — discipline
that survives bad days, procrastination at the trigger, focus, sleep, anger, patience, money
psychology, recovering after you break a streak. Same full lectures, generated for you.

## Conventions to keep

- Every entity: `id: Long` autogenerate, `createdAt`, `updatedAt`, `deletedAt: Long?` for soft delete.
- Anything belonging to a day carries `localDate: String` in `YYYY-MM-DD`.
- Bump the database version and write a real migration. Never `fallbackToDestructiveMigration()`.
- Numbers, amounts and times render in the mono type style (`MonoNumber`).
- Each module gets one spine colour, used only in its rail and header.
- No network call is ever required for the app to open and work.
