package com.nizam.app.feature.agent

import com.nizam.app.AppContainer
import com.nizam.app.core.today
import com.nizam.app.data.prefs.ThemeMode
import com.nizam.app.feature.dynamic.ModuleSpec
import com.nizam.app.feature.phone.AtlasAccessibilityService
import com.nizam.app.feature.phone.AtlasNotificationListener
import com.nizam.app.feature.phone.PhoneActions
import com.nizam.app.feature.phone.PhoneActions2
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * The assistant's tool interface.
 *
 * It cannot compile code — Android has no runtime compiler and Play forbids loading any. What it
 * can do is call these actions, which reach into the real repositories. Between creating modules
 * (which are data, not code) and rewriting layout and theme preferences, that covers almost
 * everything you would otherwise rebuild the app for.
 */
object Actions {

    data class Result(val ok: Boolean, val summary: String)

    /** Given to the model so it knows exactly what it can do. */
    val CATALOGUE = """
        calculate          {expr}         exact arithmetic on the phone; use for ANY sum, percent, EMI
                           (emi(principal, rate%, months)), lakh/crore; lines "a = 5" allowed, \n between
        remember_place     {item, place}  note where the user put something ("keys are in the blue bowl")
        where_is           {item}         look up where the user said they put something
        fact_check         {claim}        check a claim or forwarded message against current sources
        run_skill          {name, params?}  run a SKILL the user saved (steps in Atlas and in other apps, e.g. "morning routine").
                           params = {input: value} for its inputs. Steps marked for approval wait in the approval inbox
        start_team         {goal}   hand a big, multi-part goal to the user's AGENT TEAM (a lead that delegates to a researcher,
                           planner, writer, operator, analyst, critic — and hires specialists). Use for research + plan + do jobs
        api_call           {service, method, path, query?, body?}   call a CONNECTED SERVICE's API (Notion, GitHub, Slack, Todoist,
                           Home Assistant…). Atlas adds the login; path is relative to the service's base URL. GET reads;
                           anything else changes data and is reviewed first. list_connectors shows what's connected
        list_connectors    {}   connected services with their base URLs and notes
        send_to_pc         {task}   hand a long job to Hermes on the user's PC in the background; they get a notification when it's done
        dry_run            {what}   simulate an automation, skill or plan and say exactly what it would do, without doing it
        open_in_app        {app, action, text?, to?, query?}   deep-link straight into an installed app: WhatsApp message, Maps
                           directions, YouTube/Spotify search, Uber/Careem ride, Gmail compose, dialer, browser…
        connector_help     {service}   how to connect a service the user asks about
        ask_hermes         {task}   hand real computer work to Hermes Agent on the user's PC (terminal, files, coding, browsing, long
                           research). Only when Hermes is connected. It can take minutes; give it a complete, self-contained task
        create_order       {text, auto}   a STANDING ORDER that fires on events from now on: "whenever X
                           happens, do Y" (bank texts, a person messaging, opening an app, email
                           about Z). text = their sentence; auto = true only if they said to act
                           without asking. Use this — not a scheduled task — for anything event-based.
        start_autopilot    {goal}   hand a multi-step outcome to Autopilot, which plans it and works
                           it step by step in the background ("plan my trip", "find me a used car
                           under X", "research and shortlist 3 gyms near me")
        replay_day         {date?, question?}   what happened on a day — messages, apps, money,
                           prayers, tasks — from their private timeline. date as YYYY-MM-DD, default today
        open_copilot       {}   opens the screen co-pilot over the current app
        briefing           {}   today's morning briefing (writes it if it isn't ready yet)
        find_bills         {}   recurring bills and subscriptions found in their spending, with next dates
        open_loops         {person}   what's still open with someone — promises, tasks
        family_status      {}   when they last heard from each family member they listed
        zakat_due          {}   their zakat year, due date and saved holdings
        make_file          {request, kind?}  your company makes a real file: image, document, thesis, slides, sheet, app, audio or code (kind optional)
        open_screen        {screen}   opens any part of Atlas on screen — e.g. "Music", "Cinema", "Vault", "Finance",
                           "Settings", "Offline models", "Prayer", "Gym". list_screens gives every name
        list_screens       {}   every screen and app inside Atlas
        vault_read         {title}   the full Markdown of a note in the Notes vault (Obsidian-style)
        vault_write        {title, body, mode?}   writes a vault note; mode "append" (default) or "replace".
                           Use Folder/Title to file it, [[Other note]] to link, #tag to tag
        vault_search       {query}   notes whose title or text match, with snippets
        vault_links        {title}   what a note links to and which notes link back to it
        daily_note         {text?}   today's daily note (Daily/YYYY-MM-DD); appends text when given
        do_in_app          {goal}   operates the user's OTHER apps to do it (WhatsApp, YouTube, Chrome, Careem, Daraz…) — e.g.
                           "send Ammi 'on my way' on WhatsApp". Asks the user before sending/ordering/paying
        check_scam         {text}   judges whether a message or link is a scam
        money_outlook      {}   this month's spending, forecast to month end, subscriptions and bill jumps
        catch_up           {}   the latest messages from each chat (for "what did I miss?")
        make_image         {prompt}   paints a picture from words (free) and opens it in Imagine; returns the image link
        summarise_link     {url, question?}   summary of a YouTube video or web page (or an answer about it)
        pakistan_rates     {}   today's dollar, riyal, dirham, pound and gold rates in rupees
        cricket_scores     {}   live cricket scores, Pakistan first
        map_search         {query}   opens Atlas Maps searching for a place or category ("pharmacy", "Badshahi Mosque")
        api_keys           {}   which API keys are set and on
        api_key_switch     {name, on}   switches an API key on or off (Gemini, OpenRouter, TMDB, …)
        ask_folax          {question}   asks Folax (the phone's built-in Infinix assistant) and returns its answer
        ask_brain          {question}   answers from EVERYTHING they've saved or shared — notes, diary, decisions,
                           files and PDFs they sent, chats, bot threads, receipts, recent messages —
                           with sources. Use for "what did X say about…", "when did I decide…", "find that…"
        log_receipt        {merchant, date, total, category, items[], warrantyMonths, returnDays, splitWith[]}
                           files a bill completely: expense, return/warranty reminders, split with people
        create_module      {name, emoji, tagline, colorHex, fields:[{key,label,type,options[],suffix}]}
                           type is TEXT | LONGTEXT | NUMBER | RATING | CHOICE | DATE | BOOL |
                           FILE | COMPUTED
                           COMPUTED carries a "formula" over other field keys and is calculated,
                           never typed in: {"key":"perKm","label":"Cost per km","type":"COMPUTED",
                           "formula":"cost / distance"}. Operators + - * / % ^ and the functions
                           min max abs round sqrt floor ceil.
                           FILE lets the module hold real files — mp3, photos, video, PDFs
        add_entry          {moduleKey, title, values:{fieldKey: value}}
        disable_module     {moduleKey}
        enable_module      {moduleKey}
        add_task           {title, priority}
        complete_task      {title}
        add_habit          {name, targetPerWeek}
        log_expense        {amount, category, note}
        log_income         {amount, category, note}
        log_set            {exercise, weight, reps}
        log_food           {name, kcal, protein, slot}   ONE call per food (6 nuggets = one call, name "6 chicken nuggets",
                           kcal and protein for all of them). kcal and protein are plain numbers (280, not "~280").
                           slot: Breakfast | Lunch | Dinner | Snack
        log_water          {ml}   a glass is 250
        log_prayer         {prayer, status}   prayer: fajr|dhuhr|asr|maghrib|isha
                                              status: ON_TIME|LATE|JAMAAH|QADA|NOT_PRAYED
        log_study          {trackId, minutes}
        add_note           {title, body}
        add_quote          {text, source}
        add_diary          {body, mood}
        add_identity       {text}
        create_goal        {title, why, deadline}       builds a full dated mission campaign
        generate_missions  {}                           today's missions
        create_course      {subject, level, weeklyHours, weeks}
        set_targets        {kcal, waterMl}
        set_theme          {mode}                       SYSTEM | LIGHT | DARK
        set_icon           {variant}                    app icon colour: default, gold, crimson,
                                                        mono, emerald, ivory, violet — plain colour
                                                        words work too ("make the icon red"). For a
                                                        two-colour combo pass "<mark> on <background>",
                                                        e.g. "gold on black", "red on navy", "white on wine"
        set_home_order     {sections:[...]}             reorders the home screen
        refresh_curator    {}                           regenerate suggestions
        set_palette        {key}     ONLY when the user names one of these built-in themes exactly:
                           void | ember | moss | ink | paper | steel | ocean | royal | noir |
                           crimson | rose | dusk | matrix | coffee | snow | sand | sakura | mint
        create_palette     {label, surface, card, ink, inkMuted, primary, accent}
                           USE THIS whenever the user describes colours in any other way —
                           "yellow and white", "deep blue and gold", "something warmer", "like
                           old paper". You invent the hex values yourself. Never answer a colour
                           description by picking a built-in theme; build the palette they asked for.
                           surface = page background, card = raised surfaces, ink = main text,
                           inkMuted = secondary text, primary = buttons and progress,
                           accent = highlights. Contrast matters: ink must be readable on surface.
        rename_module      {moduleKey, name, tagline}
        restyle_module     {moduleKey, emoji, colorHex}
        clear_module_entries {moduleKey}
        delete_module      {moduleKey}
        generate_entries   {moduleKey, instruction, count}   AI writes real entries into a module
        generate_workout   {instruction}                     writes a full session into Gym
        read_module        {moduleKey}                       returns recent entries so you can answer
        read_finance       {days}                            returns recent transactions
        add_computed_field {moduleKey, key, label, formula, suffix}
                           Adds a calculated field to an existing module — cost per km, volume,
                           pace, whatever the numbers support. Runs over past entries too.
        set_prayer_method  {method, hanafiAsr}  KARACHI|MWL|ISNA|EGYPT|UMM_AL_QURA
        log_expense_on     {date, amount, category, note}   an expense on another day; date YYYY-MM-DD
        list_tasks         {}   open tasks with their #id
        rename_task        {id, title}
        delete_task        {id}
        list_transactions  {days}   recent money entries with their #id
        edit_transaction   {id, amount?, category?, note?, date?}
        delete_transaction {id}
        list_goals         {}            delete_goal {id}
        list_missions      {}            edit_mission {id, title, detail}   delete_mission {id}
        list_entries       {moduleKey}   delete_entry {id}
        read_notes         {query}       read_courses {}
        set_alarm          {hour, minute, label, days[]}   hour 0-23; days 1=Sunday … 7=Saturday for repeating
        set_timer          {seconds, label}
        add_calendar_event {title, date, time, durationMinutes, location}   date YYYY-MM-DD, time HH:mm (24h)
        read_calendar      {days}
        delete_event       {title}       rename_event {title, newTitle}
        call               {who}         a contact name or number
        send_sms           {who, body}   opens the message ready to send; the user sends it
        whatsapp           {who, body}   opens WhatsApp with the message ready; the user sends it
        email              {to, subject, body}
        open_app           {name}        list_apps {}
        navigate           {destination, mode}   mode: driving | walking | transit
        open_url           {url}         share_text {text}   copy_text {text}   read_clipboard {}
        flashlight         {on}          do_not_disturb {on}
        set_volume         {stream, percent}   stream: media | ring | alarm
        set_brightness     {percent}     open_settings {page}   e.g. wifi, bluetooth, battery, display
        media              {command}     play | pause | toggle | next | previous
        screen_time        {days}        battery {}   open_camera {}
        open_in            {service, query}   e.g. youtube, spotify, maps, daraz, foodpanda
        plan_day           {note}        builds today's plan around prayers and tasks
        shield_on          {minutes}     shield_off {}   focus shield that holds distracting apps
        search_web         {query}       read_link {url}
        use_pc             {task}        does a task on Atlas's own Linux desktop computer (browsing, apps, files) while the user watches
        run_command        {command, cwd, timeout}   runs in Atlas Terminal's Linux (user approves first); files in /root/projects
        read_notifications {limit}       reply_notification {match, message}
    """.trimIndent()

    suspend fun run(container: AppContainer, action: String, given: JSONObject): Result {
        // what the model wrote, mapped onto exactly what each action expects (names, numbers, enums, dates, module keys)
        val args = runCatching { ArgFix.fix(container, action, given) }.getOrDefault(given)
        // the firewall is checked here, below the AI: a blocked category can't be talked around
        com.nizam.app.feature.brain2.Firewall.check(container.appContext, action)?.let { reason ->
            runCatching { com.nizam.app.feature.next.Activity.log(container.appContext, action, reason, false) }
            return Result(false, reason)
        }
        val r = try {
            ctx = container.appContext
            if (action == "flow_continue") {
                val msg = com.nizam.app.feature.flows.FlowEngine.continueRun(container.appContext, args.optString("run"))
                Result(true, "Flow: $msg")
            } else if (action == "calculate") {
                val lines = com.nizam.app.feature.brain2.Calc.sheet(args.optString("expr"))
                val last = lines.lastOrNull { it.result != null }
                if (last == null || last.error) Result(false, "Couldn't calculate: ${last?.result ?: "empty"}")
                else Result(true, lines.filter { it.result != null }.joinToString("; ") { "${it.src.trim()} = ${it.result}" })
            } else if (action == "remember_place") {
                com.nizam.app.feature.brain2.Places.remember(container.appContext, args.optString("item"), args.optString("place"))
                Result(true, "Noted: ${args.optString("item")} → ${args.optString("place")}")
            } else if (action == "where_is") {
                Result(true, com.nizam.app.feature.brain2.Places.answer(container.appContext, args.optString("item")))
            } else if (action == "fact_check") {
                val v = com.nizam.app.feature.brain2.FactCheck.check(container.appContext, container, args.optString("claim"))
                val confidence = v.confidence?.let { " ($it% model-reported)" }.orEmpty()
                Result(true, "${v.verdict}$confidence: ${v.summary} ${v.detail}".take(900))
            } else if (action == "run_skill" || action == "run_skill_from") {
                val sk = SkillsPro.find(container.appContext, args.optString("name"))
                if (sk == null) Result(false, "No saved skill called \"${args.optString("name")}\".")
                else {
                    val p = args.optJSONObject("params") ?: JSONObject()
                    val r = SkillsPro.runBackground(container, sk, p.keys().asSequence().associateWith { p.optString(it) },
                        if (action == "run_skill_from") args.optInt("from") else 0, "Skill · ${sk.optString("name")}", "ask")
                    Result(r.ok || r.log.lastOrNull()?.startsWith("⏸") == true, "${sk.optString("name")}: " + r.log.joinToString(" · ").take(700) +
                        if (!r.ok && r.log.lastOrNull()?.startsWith("⏸") == true) " — waiting for your OK in the approval inbox" else "")
                }
            } else if (action == "send_to_pc") {
                if (com.nizam.app.feature.connect.Connectors.values(container.appContext, "hermes") == null) Result(false, "Hermes Agent isn't connected — Connectors → Hermes Agent.")
                else { com.nizam.app.feature.wild.Handoff.send(container.appContext, container, args.optString("task")); Result(true, "Sent to your PC. You'll get a notification when it's done.") }
            } else if (action == "dry_run") {
                Result(true, com.nizam.app.feature.wild.DryRun.explain(container, args.optString("what")))
            } else if (action == "start_team") {
                val id = Crew.start(container, args.optString("goal"))
                Result(true, "Your agent team is on it (thread $id). Open Agent teams to watch or steer.")
            } else if (action == "api_call" || action == "list_connectors" || action == "open_in_app" || action == "connector_help" || action == "ask_hermes") {
                com.nizam.app.feature.connect.Connectors.act(container, action, args).let { Result(it.first, it.second) }
            } else runAction(container, action, args)
        } catch (e: Exception) {
            Result(false, "$action failed: ${com.nizam.app.ui.components.Feedback.friendly(e)}")
        }
        // every action Atlas takes leaves a receipt in the activity feed
        runCatching { com.nizam.app.feature.next.Activity.log(container.appContext, action, r.summary, r.ok) }
        runCatching { com.nizam.app.feature.brain2.Skills.record(container.appContext, action, args, r.ok) }
        return r
    }

    private lateinit var ctx: android.content.Context

    private inline fun phone(block: () -> PhoneActions.Outcome): Result {
        val o = block()
        return Result(o.ok, o.message)
    }

    private suspend fun screen(block: suspend (AtlasAccessibilityService) -> String): Result {
        AtlasAccessibilityService.ready()?.let { return Result(false, it) }
        val service = AtlasAccessibilityService.instance ?: return Result(false, "Screen control unavailable.")
        val message = block(service)
        val refused = message.startsWith("I won't") || message.startsWith("That's a password")
        return Result(!refused, message)
    }

    private suspend fun runAction(
        container: AppContainer,
        action: String,
        args: JSONObject
    ): Result {
        return when (action) {
            "create_module" -> {
                val key = args.optString("key").ifBlank {
                    args.optString("name").lowercase().replace(Regex("[^a-z0-9]"), "").take(16)
                }
                container.dynamicRepository.addSpec(
                    ModuleSpec(
                        key = key.ifBlank { "mod${System.currentTimeMillis() % 100000}" },
                        name = args.optString("name", "New module"),
                        emoji = args.optString("emoji", "✨"),
                        tagline = args.optString("tagline", ""),
                        colorHex = args.optString("colorHex", "#4FA694"),
                        fieldsJson = (args.optJSONArray("fields") ?: JSONArray()).toString(),
                        sortIndex = 100
                    )
                )
                Result(true, "Created the ${args.optString("name")} module")
            }

            "add_entry" -> {
                val values = args.optJSONObject("values") ?: JSONObject()
                val map = values.keys().asSequence().associateWith { values.optString(it) }
                container.dynamicRepository.addEntry(
                    args.optString("moduleKey"), args.optString("title"), map
                )
                Result(true, "Added to ${args.optString("moduleKey")}")
            }

            "disable_module", "enable_module" -> {
                val spec = container.dynamicRepository.spec(args.optString("moduleKey"))
                    ?: return Result(false, "No module with that key")
                container.dynamicRepository.setEnabled(spec, action == "enable_module")
                Result(true, "${spec.name} ${if (action == "enable_module") "enabled" else "hidden"}")
            }

            "add_task" -> {
                container.taskRepository.add(
                    args.optString("title"), null, args.optInt("priority", 1)
                )
                Result(true, "Task added: ${args.optString("title")}")
            }

            "complete_task" -> {
                val target = args.optString("title")
                val task = container.taskRepository.all().first()
                    .firstOrNull { it.completedAt == null && it.title.contains(target, true) }
                    ?: return Result(false, "No open task matching \"$target\"")
                container.taskRepository.toggleComplete(task)
                Result(true, "Completed: ${task.title}")
            }

            "add_habit" -> {
                container.habitRepository.addHabit(
                    args.optString("name"), args.optInt("targetPerWeek", 7)
                )
                Result(true, "Habit added: ${args.optString("name")}")
            }

            "log_expense", "log_income" -> {
                container.financeRepository.add(
                    args.optDouble("amount", 0.0),
                    if (action == "log_income") "INCOME" else "EXPENSE",
                    args.optString("category", "Other"),
                    args.optString("note")
                )
                Result(true, "Logged ${args.optDouble("amount", 0.0).toInt()} PKR")
            }

            "log_set" -> {
                container.gymRepository.addSet(
                    args.optString("exercise"),
                    args.optDouble("weight", 0.0),
                    args.optInt("reps", 0)
                )
                Result(true, "Set logged: ${args.optString("exercise")}")
            }

            "log_food" -> {
                container.dietRepository.add(
                    args.optString("slot", "Snack"),
                    args.optString("name"),
                    args.optDouble("kcal", 0.0),
                    args.optDouble("protein", 0.0)
                )
                Result(true, "Food logged: ${args.optString("name")}")
            }

            "log_water" -> {
                container.dietRepository.addWater(args.optInt("ml", 250))
                Result(true, "Water logged")
            }

            "log_prayer" -> {
                if (args.optString("prayer") !in listOf("fajr", "dhuhr", "asr", "maghrib", "isha"))
                    return Result(false, "Which prayer? (Fajr, Dhuhr, Asr, Maghrib or Isha)")
                val day = container.prayerRepository.day(today()).first()
                container.prayerRepository.setStatus(
                    today(), args.optString("prayer"), args.optString("status", "ON_TIME"), day
                )
                Result(true, "${args.optString("prayer")} marked ${args.optString("status")}")
            }

            "log_study" -> {
                val trackId = args.optLong("trackId", 0L).takeIf { it > 0 }
                    ?: container.learningRepository.tracks().first().firstOrNull()?.id
                    ?: return Result(false, "No learning track to log against")
                container.learningRepository.logSession(trackId, args.optInt("minutes", 30))
                Result(true, "${args.optInt("minutes", 30)} minutes logged")
            }

            "add_note" -> {
                val id = container.noteRepository.create(args.optString("title"))
                val body = args.optString("body")
                if (body.isNotBlank()) {
                    container.noteRepository.all().first().firstOrNull { it.id == id }?.let {
                        container.noteRepository.save(it.copy(body = body))
                    }
                }
                Result(true, "Note created: ${args.optString("title")}")
            }

            "add_quote" -> {
                container.quoteRepository.add(args.optString("text"), args.optString("source"))
                Result(true, "Quote saved")
            }

            "add_diary" -> {
                container.diaryRepository.addEntry(
                    args.optString("body"), args.optInt("mood", 3), emptyList()
                )
                Result(true, "Diary entry saved")
            }

            "add_identity" -> {
                container.selfRepository.addIdentity(args.optString("text"))
                Result(true, "Identity statement committed")
            }

            "create_goal" -> {
                container.missionRepository.createGoal(
                    args.optString("title"), args.optString("why"), args.optString("deadline")
                )
                Result(true, "Goal planned with a full mission campaign")
            }

            "generate_missions" -> {
                val n = container.missionRepository.generateDaily(force = true)
                Result(true, "$n missions generated for today")
            }

            "create_course" -> {
                container.courseRepository.generateCourse(
                    args.optString("subject"),
                    args.optString("level", "Beginner"),
                    args.optInt("weeklyHours", 6),
                    args.optInt("weeks", 12),
                    args.optString("goal")
                )
                Result(true, "Course built: ${args.optString("subject")}")
            }

            "set_targets" -> {
                container.settingsRepository.setTargets(
                    args.optInt("kcal", 2200), args.optInt("waterMl", 3000)
                )
                Result(true, "Targets updated")
            }

            "set_icon" -> {
                val wanted = args.optString("variant")
                val variant = com.nizam.app.feature.icon.AppIcons.match(wanted)
                    ?: return Result(false, "No icon matches \"$wanted\". Presets: " +
                        com.nizam.app.feature.icon.AppIcons.VARIANTS.joinToString { it.name } +
                        ". Or a combo like \"gold on black\" — marks: " +
                        com.nizam.app.feature.icon.IconCombos.INKS.joinToString { it.name.lowercase() } +
                        "; backgrounds: " + com.nizam.app.feature.icon.IconCombos.BACKGROUNDS.joinToString { it.name.lowercase() })
                if (com.nizam.app.feature.icon.AppIcons.apply(ctx, variant.key))
                    Result(true, "App icon set to ${variant.name}. Your launcher may take a few seconds to redraw it.")
                else Result(false, "Couldn't change the icon on this launcher")
            }

            "set_theme" -> {
                val mode = runCatching { ThemeMode.valueOf(args.optString("mode", "SYSTEM")) }
                    .getOrElse { ThemeMode.SYSTEM }
                container.settingsRepository.setThemeMode(mode)
                Result(true, "Theme set to ${mode.name.lowercase()}")
            }

            "set_home_order" -> {
                val array = args.optJSONArray("sections") ?: JSONArray()
                val list = (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
                if (list.isEmpty()) return Result(false, "No sections given")
                container.settingsRepository.setSectionOrder(list)
                Result(true, "Home screen reordered")
            }

            "refresh_curator" -> {
                val n = container.curatorRepository.refreshSuggestions()
                Result(true, "$n suggestions refreshed")
            }

            "set_palette" -> {
                container.settingsRepository.setAccent(args.optString("key"))
                Result(true, "Theme set to ${args.optString("key")}")
            }

            "create_palette" -> {
                // Some models nest the colours under "colors" — accept either shape.
                val payload = args.optJSONObject("colors")?.also { nested ->
                    args.keys().forEach { k -> if (!nested.has(k)) nested.put(k, args.get(k)) }
                } ?: args
                container.settingsRepository.setCustomPalette(payload.toString())
                container.settingsRepository.setAccent("custom")
                Result(true, "Custom theme \"${args.optString("label", "custom")}\" applied")
            }

            "rename_module" -> {
                val spec = container.dynamicRepository.spec(args.optString("moduleKey"))
                    ?: return Result(false, "No module with that key")
                container.dynamicRepository.updateSpec(
                    spec.copy(
                        name = args.optString("name", spec.name),
                        tagline = args.optString("tagline", spec.tagline)
                    )
                )
                Result(true, "Renamed to ${args.optString("name", spec.name)}")
            }

            "restyle_module" -> {
                val spec = container.dynamicRepository.spec(args.optString("moduleKey"))
                    ?: return Result(false, "No module with that key")
                container.dynamicRepository.updateSpec(
                    spec.copy(
                        emoji = args.optString("emoji", spec.emoji),
                        colorHex = args.optString("colorHex", spec.colorHex)
                    )
                )
                Result(true, "${spec.name} restyled")
            }

            "clear_module_entries" -> {
                val spec = container.dynamicRepository.spec(args.optString("moduleKey"))
                    ?: return Result(false, "No module with that key")
                container.dynamicRepository.clearEntries(spec)
                Result(true, "Cleared every entry in ${spec.name}")
            }

            "delete_module" -> {
                val spec = container.dynamicRepository.spec(args.optString("moduleKey"))
                    ?: return Result(false, "No module with that key")
                container.dynamicRepository.deleteModule(spec)
                Result(true, "Deleted ${spec.name} and its entries")
            }

            "generate_entries" -> {
                val spec = container.dynamicRepository.spec(args.optString("moduleKey"))
                    ?: return Result(false, "No module with that key")
                val n = container.moduleGenerator.generate(
                    spec, args.optString("instruction"), args.optInt("count", 3)
                )
                Result(true, "Wrote $n entries into ${spec.name}")
            }

            "generate_workout" ->
                Result(true, container.moduleGenerator.generateWorkout(args.optString("instruction")))

            "read_module" -> {
                val key = args.optString("moduleKey")
                val entries = container.dynamicRepository.entries(key).first().take(25)
                if (entries.isEmpty()) return Result(true, "$key is empty")
                Result(true, entries.joinToString(" | ") { e ->
                    e.title + " (" + e.values().entries.joinToString(", ") { "${it.key}=${it.value}" } + ")"
                }.take(2500))
            }

            "read_finance" -> {
                val days = args.optInt("days", 30).coerceIn(1, 365)
                val from = java.time.LocalDate.now().minusDays(days.toLong()).toString()
                val txns = container.financeRepository.since(from).first()
                if (txns.isEmpty()) return Result(true, "No transactions in that window")
                Result(true, txns.take(60).joinToString(" | ") {
                    "${it.localDate} ${it.kind} ${it.amount.toInt()} ${it.category} ${it.note}"
                }.take(2500))
            }

            "add_computed_field" -> {
                val spec = container.dynamicRepository.spec(args.optString("moduleKey"))
                    ?: return Result(false, "No module with that key")
                val formula = args.optString("formula")
                if (formula.isBlank()) return Result(false, "No formula given")
                val existing = org.json.JSONArray(spec.fieldsJson)
                existing.put(
                    JSONObject()
                        .put("key", args.optString("key", "computed"))
                        .put("label", args.optString("label", "Computed"))
                        .put("type", "COMPUTED")
                        .put("formula", formula)
                        .put("suffix", args.optString("suffix"))
                )
                container.dynamicRepository.updateSpec(spec.copy(fieldsJson = existing.toString()))
                Result(true, "Added \"${args.optString("label")}\" = $formula to ${spec.name}")
            }

            "set_prayer_method" -> {
                container.settingsRepository.setPrayerMethod(args.optString("method", "KARACHI"))
                container.settingsRepository.setHanafiAsr(args.optBoolean("hanafiAsr", true))
                Result(true, "Prayer calculation updated")
            }

            // ── Manage ─────────────────────────────────────────────────────────────────
            "list_missions" -> {
                val list = container.missionRepository.openMissions()
                Result(true, if (list.isEmpty()) "No open missions." else list.joinToString(" | ") {
                    "#${it.id} ${it.localDate} ${it.title}"
                }.take(2500))
            }
            "delete_mission" -> {
                val id = args.optLong("id")
                val m = container.missionRepository.openMissions().firstOrNull { it.id == id }
                    ?: container.missionRepository.today().first().firstOrNull { it.id == id }
                    ?: return Result(false, "No mission #$id")
                container.missionRepository.deleteMission(m)
                Result(true, "Deleted mission: ${m.title}")
            }
            "edit_mission" -> {
                val id = args.optLong("id")
                val m = container.missionRepository.openMissions().firstOrNull { it.id == id }
                    ?: return Result(false, "No open mission #$id")
                container.missionRepository.renameMission(
                    m, args.optString("title", m.title), args.optString("detail", m.detail)
                )
                Result(true, "Mission updated")
            }
            "list_goals" -> {
                val goals = container.missionRepository.goals().first()
                Result(true, if (goals.isEmpty()) "No active goals." else goals.joinToString(" | ") {
                    "#${it.id} ${it.title}"
                })
            }
            "delete_goal" -> {
                val g = container.missionRepository.goals().first().firstOrNull { it.id == args.optLong("id") }
                    ?: return Result(false, "No goal with that id")
                container.missionRepository.deleteGoal(g)
                Result(true, "Deleted goal \"${g.title}\" and its missions")
            }
            "list_tasks" -> {
                val tasks = container.taskRepository.all().first().filter { it.completedAt == null }
                Result(true, if (tasks.isEmpty()) "No open tasks." else tasks.joinToString(" | ") {
                    "#${it.id} ${it.title}"
                }.take(2500))
            }
            "delete_task" -> {
                val t = container.taskRepository.all().first().firstOrNull { it.id == args.optLong("id") }
                    ?: return Result(false, "No task with that id")
                container.taskRepository.hardDelete(t)
                Result(true, "Deleted task: ${t.title}")
            }
            "rename_task" -> {
                val t = container.taskRepository.all().first().firstOrNull { it.id == args.optLong("id") }
                    ?: return Result(false, "No task with that id")
                container.taskRepository.rename(t, args.optString("title"))
                Result(true, "Task renamed")
            }
            "list_transactions" -> {
                val from = java.time.LocalDate.now().minusDays(args.optInt("days", 30).toLong()).toString()
                val txns = container.financeRepository.since(from).first()
                Result(true, if (txns.isEmpty()) "No transactions." else txns.take(50).joinToString(" | ") {
                    "#${it.id} ${it.localDate} ${it.kind} ${it.amount.toInt()} ${it.category} ${it.note}"
                }.take(3000))
            }
            "delete_transaction" -> {
                val t = container.financeRepository.recent().first().firstOrNull { it.id == args.optLong("id") }
                    ?: return Result(false, "No transaction with that id")
                container.financeRepository.remove(t)
                Result(true, "Deleted ${t.amount.toInt()} PKR ${t.category}")
            }
            "edit_transaction" -> {
                val t = container.financeRepository.recent().first().firstOrNull { it.id == args.optLong("id") }
                    ?: return Result(false, "No transaction with that id")
                container.financeRepository.edit(
                    t.copy(
                        amount = if (args.has("amount")) args.optDouble("amount") else t.amount,
                        category = args.optString("category", t.category),
                        note = args.optString("note", t.note),
                        localDate = args.optString("date", t.localDate).ifBlank { t.localDate }
                    )
                )
                Result(true, "Transaction updated")
            }
            "log_expense_on" -> {
                container.financeRepository.addOn(
                    args.optString("date"), args.optDouble("amount", 0.0), "EXPENSE",
                    args.optString("category", "Other"), args.optString("note")
                )
                Result(true, "Logged ${args.optDouble("amount", 0.0).toInt()} PKR on ${args.optString("date")}")
            }
            "list_entries" -> {
                val entries = container.dynamicRepository.entries(args.optString("moduleKey")).first()
                Result(true, if (entries.isEmpty()) "Empty." else entries.take(40).joinToString(" | ") {
                    "#${it.id} ${it.localDate} ${it.title}"
                }.take(3000))
            }
            "delete_entry" -> {
                container.dynamicRepository.deleteEntry(args.optLong("id"))
                Result(true, "Entry #${args.optLong("id")} deleted")
            }
            "read_notes" -> {
                val q = args.optString("query")
                val notes = container.noteRepository.all().first()
                    .filter { q.isBlank() || it.title.contains(q, true) || it.body.contains(q, true) }
                Result(true, if (notes.isEmpty()) "No matching notes." else notes.take(8).joinToString(" || ") {
                    "${it.title}: ${it.body.take(400)}"
                }.take(3500))
            }
            "read_courses" -> {
                val courses = container.courseRepository.courses().first()
                if (courses.isEmpty()) return Result(true, "No courses yet.")
                val lines = courses.map { course ->
                    val lessons = container.courseRepository.lessons(course.id).first()
                    "${course.title}: ${lessons.count { it.completedAt != null }}/${lessons.size} lessons done"
                }
                Result(true, lines.joinToString(" | "))
            }

            // ── Phone ──────────────────────────────────────────────────────────────────
            "set_alarm" -> phone {
                val days = args.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
                    ?: emptyList()
                PhoneActions.setAlarm(ctx, args.optInt("hour", 7), args.optInt("minute", 0),
                    args.optString("label"), days)
            }
            "set_timer" -> phone { PhoneActions.setTimer(ctx, args.optInt("seconds", 60), args.optString("label")) }
            "add_calendar_event" -> {
                // Salah-aware: an event that lands on a prayer moves to just after it
                var time = args.optString("time")
                var moved = ""
                if (com.nizam.app.feature.jarvis.SalahGuard.shiftOn(ctx) && !args.optBoolean("keepTime", false)) {
                    val day = runCatching { java.time.LocalDate.parse(args.optString("date")) }.getOrElse { java.time.LocalDate.now() }
                    runCatching { java.time.LocalTime.parse(time) }.getOrNull()?.let { at ->
                        com.nizam.app.feature.jarvis.SalahGuard.clash(container, day, at, args.optInt("durationMinutes", 60))?.let { (prayer, after) ->
                            moved = " (moved from $at to after $prayer — pass keepTime to override)"
                            time = after.withSecond(0).withNano(0).toString()
                        }
                    }
                }
                phone {
                    PhoneActions.addEvent(ctx, args.optString("title"), args.optString("date"),
                        time, args.optInt("durationMinutes", 60), args.optString("location"))
                }.let { r -> Result(r.ok, r.summary + moved) }
            }
            "read_calendar" -> phone { PhoneActions.readEvents(ctx, args.optInt("days", 7)) }
            "call" -> phone { PhoneActions.call(ctx, args.optString("who")) }
            "send_sms" -> phone {
                PhoneActions.sms(ctx, args.optString("who"), args.optString("body"), args.optBoolean("send", false))
            }
            "whatsapp" -> phone { PhoneActions.whatsapp(ctx, args.optString("who"), args.optString("body")) }
            "open_app" -> phone { PhoneActions.openApp(ctx, args.optString("name")) }
            "list_apps" -> phone { PhoneActions.listApps(ctx) }
            "navigate" -> phone { PhoneActions.navigate(ctx, args.optString("destination"), args.optString("mode")) }
            "open_url" -> phone { PhoneActions.openUrl(ctx, args.optString("url")) }
            "share_text" -> phone { PhoneActions.share(ctx, args.optString("text")) }
            "flashlight" -> phone { PhoneActions.flashlight(ctx, args.optBoolean("on", true)) }
            "set_volume" -> phone { PhoneActions.volume(ctx, args.optString("stream"), args.optInt("percent", 50)) }
            "do_not_disturb" -> phone { PhoneActions.doNotDisturb(ctx, args.optBoolean("on", true)) }
            "set_brightness" -> phone { PhoneActions.brightness(ctx, args.optInt("percent", 50)) }
            "open_settings" -> phone { PhoneActions.openSettings(ctx, args.optString("page")) }

            "screen_time" -> phone { PhoneActions2.screenTime(ctx, args.optInt("days", 1)) }
            "media" -> phone { PhoneActions2.media(ctx, args.optString("command", "toggle")) }
            "copy_text" -> phone { PhoneActions2.copy(ctx, args.optString("text")) }
            "read_clipboard" -> phone { PhoneActions2.paste(ctx) }
            "battery" -> phone { PhoneActions2.battery(ctx) }
            "open_camera" -> phone { PhoneActions2.camera(ctx) }
            "open_in" -> phone { PhoneActions2.deepLink(ctx, args.optString("service"), args.optString("query")) }
            "email" -> phone {
                PhoneActions2.email(ctx, args.optString("to"), args.optString("subject"), args.optString("body"))
            }
            "delete_event" -> phone { PhoneActions2.deleteEvent(ctx, args.optString("title")) }
            "rename_event" -> phone {
                PhoneActions2.renameEvent(ctx, args.optString("title"), args.optString("newTitle"))
            }

            "plan_day" -> {
                val blocks = com.nizam.app.feature.planner.Planner.plan(container, ctx, args.optString("note"))
                Result(true, "Planned ${blocks.size} blocks: " + blocks.take(8).joinToString(" | ") { "${it.start} ${it.title}" })
            }
            "shield_on" -> {
                com.nizam.app.feature.shield.Shield.activate(ctx, args.optInt("minutes", 30))
                Result(true, "Focus shield on for ${args.optInt("minutes", 30)} min")
            }
            "shield_off" -> { com.nizam.app.feature.shield.Shield.stop(ctx); Result(true, "Focus shield off") }

            "create_order" -> {
                val text = args.optString("text").ifBlank { return Result(false, "No order given") }
                val order = com.nizam.app.feature.jarvis.StandingOrders.compile(ctx, text, args.optBoolean("auto", false))
                com.nizam.app.feature.jarvis.StandingOrders.save(ctx, com.nizam.app.feature.jarvis.StandingOrders.all(ctx) + order)
                val on = if (order.event == "app_open") "opening " + order.apps.joinToString()
                    else "notifications" + (if (order.keywords.isNotEmpty()) " mentioning " + order.keywords.take(3).joinToString("/") else "")
                Result(true, "Standing order live: \"" + order.text + "\" — fires on " + on)
            }

            "start_autopilot" -> {
                val goal = args.optString("goal").ifBlank { return Result(false, "No goal given") }
                val p = com.nizam.app.feature.jarvis.Autopilot.start(ctx, goal)
                Result(true, "Autopilot started with ${p.steps.size} steps: " + p.steps.joinToString(" → ") { it.title } +
                    ". It works in the background and pings you when it needs you — see Atlas → Jarvis.")
            }

            "replay_day" -> {
                val d = runCatching { java.time.LocalDate.parse(args.optString("date")) }.getOrElse { java.time.LocalDate.now() }
                val moments = com.nizam.app.feature.jarvis.Replay.moments(container, d)
                val q = args.optString("question")
                val older = if (q.isBlank()) "" else com.nizam.app.feature.jarvis.Timeline.search(ctx, q, 20)
                    .joinToString("\n") { (day, m) -> "$day ${m.time} ${m.title}" }
                Result(true, com.nizam.app.feature.jarvis.Replay.text(d, moments).take(5000) +
                    (if (older.isNotBlank()) "\nMatches on other days:\n$older" else ""))
            }

            "ask_brain" -> {
                val q = args.optString("question").ifBlank { return Result(false, "No question") }
                val docs = com.nizam.app.feature.brain.Brain.corpus(container, ctx)
                Result(true, com.nizam.app.feature.brain.Brain.ask(container, q, com.nizam.app.feature.brain.Brain.rankSearch(q, docs)))
            }

            "log_receipt" -> {
                fun list(k: String) = args.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                val r = com.nizam.app.feature.jarvis.Receipt(args.optString("merchant", "Receipt"),
                    args.optString("date").takeIf { runCatching { java.time.LocalDate.parse(it) }.isSuccess } ?: java.time.LocalDate.now().toString(),
                    args.optDouble("total", 0.0), args.optString("currency", "PKR"), args.optString("category", "Other"), list("items"),
                    args.optInt("warrantyMonths", 0), args.optInt("returnDays", 0))
                Result(true, com.nizam.app.feature.jarvis.Receipts.file(container, r, list("splitWith")).joinToString("; "))
            }

            "briefing" -> {
                val text = com.nizam.app.feature.jarvis.Briefing.today(ctx)
                    ?: com.nizam.app.feature.jarvis.Briefing.prepare(ctx, container)
                Result(true, text)
            }
            "find_bills" -> {
                val list = com.nizam.app.feature.jarvis.BillRadar.find(container)
                Result(true, if (list.isEmpty()) "No recurring charges found yet." else list.joinToString("; ") {
                    "${it.name}: ${com.nizam.app.core.fmtAmount(it.amount)} every ${it.everyDays} days, next ${it.next}" })
            }
            "open_loops" -> {
                val who = args.optString("person").ifBlank { return Result(false, "Who?") }
                val loops = com.nizam.app.feature.jarvis.Recall.loops(container, who)
                Result(true, if (loops.isEmpty()) "Nothing open with $who." else loops.joinToString("; "))
            }
            "family_status" -> {
                val st = com.nizam.app.feature.jarvis.Silah.status(ctx)
                Result(true, if (st.isEmpty()) "No family listed yet — add names in Atlas → Jarvis → Radar." else
                    st.joinToString("; ") { (n, d) -> "$n: " + (d?.let { "$it days ago" } ?: "no recent messages") })
            }
            "do_in_app" -> {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { com.nizam.app.feature.future.AppAgent.start(ctx, container, args.optString("goal")) }
                Result(true, "Working on it in your apps — watch the Atlas panel at the bottom of the screen")
            }
            "check_scam" -> com.nizam.app.feature.future.Guardian.check(args.optString("text")).let { v ->
                Result(true, if (v.score >= 60) "Likely a scam: ${v.reasons.joinToString("; ")}" else if (v.reasons.isEmpty()) "No common scam signs" else "Be careful: ${v.reasons.joinToString("; ")}") }
            "money_outlook" -> com.nizam.app.feature.future.MoneyPilot.outlook(container).let { o ->
                Result(true, "Spent Rs %,.0f, income Rs %,.0f, projected Rs %,.0f by month end (%s). Top: %s. Subscriptions: %s. Bill jumps: %s".format(
                    o.spent, o.income, o.projected, if (o.endBalance >= 0) "Rs %,.0f ahead".format(o.endBalance) else "Rs %,.0f short".format(-o.endBalance),
                    o.top.take(4).joinToString { "${it.first} Rs %,.0f".format(it.second) }, o.subscriptions.joinToString { it.first }.ifBlank { "none found" }, o.jumps.joinToString().ifBlank { "none" })) }
            "catch_up" -> Result(true, com.nizam.app.feature.future.Twin.recentChats().take(12).joinToString("\n\n") { ch ->
                ch.title + ":\n" + ch.lines.takeLast(5).joinToString("\n") { "${it.first}: ${it.second}" } }.ifBlank { "No new chats seen yet (needs notification access)" })
            "make_image" -> {
                val url = com.nizam.app.feature.future.Imagine.url(args.optString("prompt"), "Square", (1..999_999).random())
                com.nizam.app.feature.future.Imagine.remember(ctx, url)
                com.nizam.app.ui.screens.search.AtlasNav.go?.let { go -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { go("power/image") } }
                Result(true, "Made it — it's first in Imagine › Recent: $url")
            }
            "summarise_link" -> {
                val src = com.nizam.app.feature.future.Summariser.fetch(args.optString("url"))
                Result(true, com.nizam.app.feature.future.Summariser.summarise(container.settingsRepository, src, "Key points", args.optString("question").ifBlank { null }))
            }
            "pakistan_rates" -> {
                val r = com.nizam.app.feature.future.PakistanData.rates()
                Result(true, r.pkrPer.entries.joinToString(", ") { (k, v) -> "$k Rs %.2f".format(v) } +
                    (r.goldTola?.let { "; gold 24k Rs %,.0f per tola (international)".format(it) } ?: ""))
            }
            "cricket_scores" -> Result(true, com.nizam.app.feature.future.PakistanData.cricket().take(6).joinToString("\n") { it.title }.ifBlank { "No live matches right now" })
            "map_search" -> {
                com.nizam.app.feature.maps.MapsNav.query = args.optString("query")
                val go = com.nizam.app.ui.screens.search.AtlasNav.go ?: return Result(false, "Atlas isn't open on screen right now")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { go(com.nizam.app.ui.nav.Routes.MAPS) }
                Result(true, "Showing ${args.optString("query")} on the map")
            }
            "api_keys" -> Result(true, com.nizam.app.net.ApiKeys.SLOTS.joinToString("\n") { sl ->
                "${sl.label}: " + if (!com.nizam.app.net.ApiKeys.enabled(ctx, sl.id)) "off" else "on" })
            "api_key_switch" -> {
                val want = args.optString("name")
                val sl = com.nizam.app.net.ApiKeys.SLOTS.firstOrNull { it.label.contains(want, true) || it.id.equals(want, true) }
                    ?: return Result(false, "No key called $want")
                com.nizam.app.net.ApiKeys.setEnabled(ctx, container.settingsRepository, sl, args.optBoolean("on", true))
                Result(true, "${sl.label} key ${if (args.optBoolean("on", true)) "on" else "off"}")
            }
            "make_file" -> {
                val req = args.optString("request").trim().ifBlank { return Result(false, "Say what to make") }
                val kind = com.nizam.app.feature.studio.Deliverables.Kind.entries.firstOrNull { it.name.equals(args.optString("kind"), true) || it.label.equals(args.optString("kind"), true) }
                    ?: com.nizam.app.feature.studio.Deliverables.Kind.AUTO
                com.nizam.app.feature.studio.Deliverables.order(container, req, kind)
                Result(true, "Your company is making it now. It will be on the Deliverables shelf when ready (you'll get a notification).")
            }
            "list_screens" -> Result(true, com.nizam.app.ui.screens.search.DESTINATIONS.joinToString { it.name })
            "use_pc" -> {
                // hands the task to Atlas's own Linux computer and shows it working
                val t = args.optString("task").ifBlank { args.optString("text") }
                if (t.isBlank()) return Result(false, "What should I do on the PC?")
                com.nizam.app.feature.pc.PcAgent.queued = t
                val go = com.nizam.app.ui.screens.search.AtlasNav.go ?: return Result(false, "Atlas isn't open on screen right now")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { go(com.nizam.app.feature.nx.Nx.route("pc")) }
                return Result(true, "Working on it on Atlas's PC — you can watch it there.")
            }
            "run_command" -> {
                // a shell command in Atlas Terminal's Linux (always asked for first, see AgentLoop)
                val cmd = args.optString("command").ifBlank { args.optString("cmd") }
                if (cmd.isBlank()) return Result(false, "Which command?")
                val r = com.nizam.app.feature.linux.LinuxEnv.run(container.appContext, cmd, args.optString("cwd").ifBlank { "/root/projects" }, args.optLong("timeout", 120).coerceIn(5, 900))
                return Result(r.code == 0, "\$ $cmd → exit ${r.code}" + (if (r.timedOut) " (timed out)" else "") + "\n" + r.out.takeLast(3000))
            }
            "open_screen" -> {
                val want = args.optString("screen").ifBlank { args.optString("name") }.trim()
                if (want.isBlank()) return Result(false, "Which screen?")
                // every tool in the catalogue too (Oracle, Forge, Life Compass…), not only the main screens
                val tool = com.nizam.app.feature.nx.Nx.all.let { all -> all.firstOrNull { it.title.equals(want, true) || it.id.equals(want, true) }
                    ?: all.firstOrNull { want.length >= 4 && it.title.contains(want, true) } }
                val exact = com.nizam.app.ui.screens.search.DESTINATIONS.firstOrNull { it.name.equals(want, true) }
                if (exact == null && tool != null) {
                    val go = com.nizam.app.ui.screens.search.AtlasNav.go ?: return Result(false, "Atlas isn't open on screen right now")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { go(com.nizam.app.feature.nx.Nx.route(tool.id)) }
                    return Result(true, "Opened ${tool.title}")
                }
                val d = com.nizam.app.ui.screens.search.DESTINATIONS.let { all ->
                    all.firstOrNull { it.name.equals(want, true) } ?: all.firstOrNull { it.name.contains(want, true) || want.contains(it.name, true) }
                        ?: all.firstOrNull { it.words.contains(want, true) } }
                    ?: (if (want.equals("vault", true) || want.contains("note", true)) com.nizam.app.ui.screens.search.Dest("", "Notes", com.nizam.app.ui.nav.Routes.NOTES) else null)
                    ?: return Result(false, "No screen called \"$want\". Screens: " + com.nizam.app.ui.screens.search.DESTINATIONS.joinToString { it.name })
                val go = com.nizam.app.ui.screens.search.AtlasNav.go ?: return Result(false, "Atlas isn't open on screen right now")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { go(d.route) }
                Result(true, "Opened ${d.name}")
            }
            "vault_read" -> {
                val t = args.optString("title").trim()
                val all = container.noteRepository.everything()
                val n = all.firstOrNull { it.title.equals(t, true) } ?: all.firstOrNull { it.title.substringAfterLast('/').equals(t, true) }
                    ?: all.firstOrNull { it.title.contains(t, true) } ?: return Result(false, "No note called \"$t\"")
                Result(true, "# ${n.title}\n${n.body.take(6000)}")
            }
            "vault_write" -> {
                val t = args.optString("title").trim().ifBlank { return Result(false, "No title") }
                val body = args.optString("body")
                val all = container.noteRepository.everything()
                val n = all.firstOrNull { it.title.equals(t, true) }
                if (n == null) { container.noteRepository.createFull(t, body); Result(true, "Created note $t") }
                else {
                    container.noteRepository.save(n.copy(body = if (args.optString("mode") == "replace") body else n.body.trimEnd() + "\n\n" + body))
                    Result(true, "Updated note ${n.title}")
                }
            }
            "vault_search" -> {
                val q = args.optString("query").trim()
                val hits = container.noteRepository.everything().filter { it.title.contains(q, true) || it.body.contains(q, true) }.take(12)
                Result(true, if (hits.isEmpty()) "No notes match \"$q\"" else hits.joinToString("\n") { n ->
                    val at = n.body.indexOf(q, ignoreCase = true).coerceAtLeast(0)
                    "• ${n.title}: " + n.body.substring((at - 40).coerceAtLeast(0), (at + 80).coerceAtMost(n.body.length)).replace("\n", " ") })
            }
            "vault_links" -> {
                val t = args.optString("title").trim()
                val all = container.noteRepository.everything()
                val n = all.firstOrNull { it.title.equals(t, true) } ?: return Result(false, "No note called \"$t\"")
                val out = com.nizam.app.feature.notes.Md.WIKI.findAll(n.body).map { it.groupValues[1].trim() }.distinct().toList()
                val back = all.filter { o -> o.id != n.id && com.nizam.app.feature.notes.Md.WIKI.findAll(o.body).any { it.groupValues[1].trim().equals(n.title, true) } }.map { it.title }
                Result(true, "Links to: ${out.joinToString().ifBlank { "nothing" }}\nLinked from: ${back.joinToString().ifBlank { "nothing" }}")
            }
            "daily_note" -> {
                val title = "Daily/" + java.time.LocalDate.now()
                val all = container.noteRepository.everything()
                val n = all.firstOrNull { it.title == title }
                val text = args.optString("text")
                when {
                    n == null -> { container.noteRepository.createFull(title, "# ${java.time.LocalDate.now()}\n\n$text".trim()); Result(true, "Started today's daily note") }
                    text.isNotBlank() -> { container.noteRepository.save(n.copy(body = n.body.trimEnd() + "\n" + text)); Result(true, "Added to today's daily note") }
                    else -> Result(true, n.body.take(4000))
                }
            }
            "ask_folax" -> {
                val q = args.optString("question").ifBlank { return Result(false, "No question") }
                runCatching { com.nizam.app.feature.jarvis.Folax.ask(ctx, q) }
                    .fold({ Result(true, "Folax says: $it") }, { Result(false, it.message ?: "Folax didn't answer") })
            }

            "zakat_due" -> Result(true, com.nizam.app.feature.deen.Zakat.dueDate(ctx)?.let { "Zakat year ends $it." }
                ?: "No zakat year set — add the start date in Deen → Zakat.")

            "open_copilot" -> { com.nizam.app.feature.jarvis.CopilotActivity.open(ctx); Result(true, "Co-pilot opened") }

            "search_web" -> {
                val q = args.optString("query").ifBlank { return Result(false, "No search query given") }
                val found = com.nizam.app.net.Ai.generate(
                    settings = container.settingsRepository,
                    prompt = "Search the web and answer with what you find. Be specific, include dates, numbers and sources. " +
                        "If nothing current comes back, say so plainly.\n\nSearch: $q",
                    system = "You search the web and report findings factually.",
                    maxOutputTokens = 1600, useWeb = true
                )
                Result(true, found.take(3000))
            }

            "read_link" -> {
                val url = args.optString("url").ifBlank { return Result(false, "No link given") }
                val (title, text) = com.nizam.app.feature.memory.Readable.fetch(url)
                if (text.length < 120) {
                    // TikTok, Instagram and YouTube pages are mostly script — their oEmbed endpoint has the real caption
                    val o = runCatching {
                        val api = when {
                            url.contains("tiktok") -> "https://www.tiktok.com/oembed?url=$url"
                            url.contains("youtu") -> "https://www.youtube.com/oembed?format=json&url=$url"
                            else -> null
                        } ?: return@runCatching null
                        JSONObject(com.nizam.app.net.Http.getJson(api))
                    }.getOrNull()
                    if (o != null) return Result(true, "\"${o.optString("title")}\" by ${o.optString("author_name")} ($url). " +
                        "That's all the page itself exposes — video contents can't be read without opening it.")
                }
                Result(true, "$title — ${text.take(4000)}")
            }

            "mcp_tools" -> {
                val list = com.nizam.app.feature.mcp.Mcp.allTools(ctx)
                Result(true, if (list.isEmpty()) "No MCP servers connected — add one in the Playground."
                    else list.joinToString("\n") { "${it.name} (${it.server}): ${it.description.take(120)}" +
                        (it.schema?.optJSONObject("properties")?.keys()?.asSequence()?.joinToString(",")?.let { p -> " [args: $p]" } ?: "") })
            }
            "mcp_schema" -> Result(true, com.nizam.app.feature.mcp.Mcp.schemaOf(ctx, args.optString("tool")))

            "mcp_call" -> {
                val tool = args.optString("tool").ifBlank { return Result(false, "No tool named") }
                val payload = args.optJSONObject("args") ?: runCatching { JSONObject(args.optString("args")) }.getOrElse { JSONObject() }
                runCatching { com.nizam.app.feature.mcp.Mcp.findAndCall(ctx, tool, payload) }
                    .fold({ Result(true, it.take(3000)) },
                        { Result(false, "$tool failed: ${it.message}. Check the schema with mcp_schema and retry with corrected arguments.") })
            }

            "cinema_search" -> {
                val r = com.nizam.app.feature.cinema.Tmdb.search(ctx, args.optString("query")).take(8)
                Result(true, if (r.isEmpty()) "No films found." else r.joinToString(" | ") { "#${it.id} ${it.title} (${it.year}) ★${"%.1f".format(it.rating)}" })
            }
            "cinema_discover" -> {
                val genres = com.nizam.app.feature.cinema.Tmdb.genres(ctx)
                val g = genres.entries.firstOrNull { it.value.equals(args.optString("genre"), true) }?.key
                val r = com.nizam.app.feature.cinema.Tmdb.discover(ctx, g,
                    args.optInt("from_year", 0).takeIf { it > 0 }, args.optInt("to_year", 0).takeIf { it > 0 },
                    args.optDouble("min_rating", 6.5), "vote_average.desc", args.optString("language").ifBlank { null }).take(10)
                Result(true, if (r.isEmpty()) "Nothing matched." else r.joinToString(" | ") { "#${it.id} ${it.title} (${it.year}) ★${"%.1f".format(it.rating)}" })
            }
            "cinema_details" -> {
                val d = com.nizam.app.feature.cinema.Tmdb.detail(ctx, args.optInt("id"))
                Result(true, "${d.film.title} (${d.film.year}), ${d.runtime} min, ${d.genres.joinToString()}, dir. ${d.director}. " +
                    "Cast: ${d.cast.take(5).joinToString { it.name }}. ${d.film.overview.take(400)} " +
                    "Where to watch: ${d.providers.joinToString { "${it.name} (${it.kind})" }.ifBlank { "not streaming in their region" }}")
            }
            "cinema_free" -> {
                val r = com.nizam.app.feature.cinema.Archive.search(args.optString("query")).take(10)
                Result(true, if (r.isEmpty()) "No free films found." else r.joinToString(" | ") { "${it.title} (${it.year})" } +
                    " — these play in the Cinema tab under Free to watch.")
            }
            "cinema_watchlist_add" -> {
                val d = com.nizam.app.feature.cinema.Tmdb.detail(ctx, args.optInt("id"))
                val store = com.nizam.app.feature.cinema.CinemaStore(ctx)
                if (!store.inWatchlist(d.film.id)) store.toggleWatchlist(d.film)
                Result(true, "Added ${d.film.title} to your watchlist")
            }

            "read_notifications" -> {
                if (!AtlasNotificationListener.connected) {
                    return Result(false, "Notification access is off. Grant it in Settings > Phone control.")
                }
                val items = AtlasNotificationListener.recent(args.optInt("limit", 15))
                if (items.isEmpty()) Result(true, "No new notifications.")
                else Result(true, items.joinToString(" | ") {
                    "${it.app}: ${it.title} — ${it.text}" + if (it.canReply) " [can reply]" else ""
                }.take(3000))
            }
            "reply_notification" -> Result(
                true,
                AtlasNotificationListener.reply(ctx, args.optString("match"), args.optString("message"))
            )

            "read_screen" -> screen { it.readScreen() }
            "tap" -> screen { svc -> svc.tap(args.optString("text")).also { svc.settle() } }
            "type_text" -> screen { it.type(args.optString("text")) }
            "scroll" -> screen { it.scroll(!args.optString("direction").equals("up", true)) }
            "phone_nav" -> screen { svc -> svc.global(args.optString("action")).also { svc.settle() } }

            else -> Result(false, "Unknown action: $action")
        }
    }
}
