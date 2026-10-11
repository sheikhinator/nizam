package com.nizam.app.feature.connect

/**
 * Every service Atlas can reach, in five kinds:
 *  - api:    Atlas calls the service directly with a key or token you paste (stored encrypted, only on this phone)
 *  - mcp:    a Model Context Protocol server — the service's own tools appear to the agent
 *  - hub:    services that only allow sign-in apps; reached through Zapier / Composio / Pipedream, which you connect once
 *  - app:    apps on your phone, opened straight to the right screen (and operated by the in-app agent if needed)
 *  - device: things already on the phone — calendar, contacts, messages, files — built in
 *
 * Auth is a small template: "H:Name=value" adds a header, "Q:a=b&c=d" adds query parameters, "B:user:pass" is basic auth.
 * {field} is filled from what you entered; {token} is the main secret.
 */
data class Field(val key: String, val label: String, val hint: String = "", val secret: Boolean = false, val default: String = "")

data class Service(
    val id: String, val name: String, val cat: String, val emoji: String, val kind: String,
    val base: String = "", val auth: String = "", val test: String = "", val tokenUrl: String = "", val notes: String = "",
    val fields: List<Field> = listOf(Field("token", "API token", secret = true)), val pkg: String = "",
    val links: Map<String, String> = emptyMap(), val popular: Boolean = false
)

object Catalog {
    private fun tok(label: String = "API token") = listOf(Field("token", label, secret = true))
    private fun host(h: String, d: String = "") = Field("host", h, d, default = d)

    private fun api(id: String, name: String, cat: String, emoji: String, base: String, auth: String, test: String, tokenUrl: String,
                    notes: String = "", fields: List<Field> = tok(), popular: Boolean = false) =
        Service(id, name, cat, emoji, "api", base, auth, test, tokenUrl, notes, fields, popular = popular)
    private fun hub(id: String, name: String, cat: String, emoji: String, popular: Boolean = false) =
        Service(id, name, cat, emoji, "hub", notes = "Sign-in only service", fields = emptyList(), popular = popular)
    private fun app(id: String, name: String, cat: String, emoji: String, pkg: String, links: Map<String, String>, popular: Boolean = false) =
        Service(id, name, cat, emoji, "app", pkg = pkg, links = links, fields = emptyList(), popular = popular)
    private fun mcp(id: String, name: String, cat: String, emoji: String, url: String, tokenUrl: String, notes: String, fields: List<Field>, popular: Boolean = false) =
        Service(id, name, cat, emoji, "mcp", base = url, tokenUrl = tokenUrl, notes = notes, fields = fields, popular = popular)
    private fun dev(id: String, name: String, emoji: String, notes: String) = Service(id, name, "On this phone", emoji, "device", notes = notes, fields = emptyList(), popular = true)

    const val BEARER = "H:Authorization=Bearer {token}"

    val all: List<Service> by lazy { automation + productivity + comms + dev + ai + money + crm + storage + home + health + media + info + web + learn + hubs + apps + device }

    // ───────────── automation hubs (thousands of apps each) ─────────────
    private val automation = listOf(
        mcp("zapier", "Zapier — 8,000+ apps", "Automation", "⚡", "{url}", "https://mcp.zapier.com",
            "Create a Zapier MCP server, add the actions you want (Gmail, Sheets, Calendar, Outlook, LinkedIn…) and paste its URL. Every action becomes an Atlas tool.",
            listOf(Field("url", "Your Zapier MCP URL", "https://mcp.zapier.com/api/mcp/s/…", secret = true)), true),
        mcp("composio", "Composio — 250+ apps", "Automation", "🧩", "{url}", "https://mcp.composio.dev",
            "Pick apps on Composio (Gmail, Google Drive, Notion, Slack, GitHub, HubSpot…), sign in there once, and paste the MCP URL.",
            listOf(Field("url", "Your Composio MCP URL", secret = true)), true),
        mcp("pipedream", "Pipedream — 2,800+ apps", "Automation", "🌊", "{url}", "https://mcp.pipedream.com",
            "Connect accounts on Pipedream and paste your MCP server URL. Their actions show up for the agent.", listOf(Field("url", "Pipedream MCP URL", secret = true)), true),
        mcp("make", "Make.com scenarios", "Automation", "🟣", "{url}", "https://www.make.com/en/help/ai/mcp-server",
            "Expose your Make scenarios as tools: copy the MCP server URL (with token) from Make.", listOf(Field("url", "Make MCP URL", secret = true))),
        mcp("n8nmcp", "n8n workflows (MCP)", "Automation", "🔗", "{url}", "https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-langchain.mcptrigger/",
            "Add an MCP Server Trigger in n8n and paste its production URL.", listOf(Field("url", "MCP trigger URL"), Field("token", "Bearer token (optional)", secret = true))),
        api("n8n", "n8n API", "Automation", "🔗", "{host}/api/v1", "H:X-N8N-API-KEY={token}", "/workflows?limit=1", "https://docs.n8n.io/api/authentication/",
            "GET /workflows, POST /workflows/{id}/activate, GET /executions", listOf(host("Your n8n address", "https://"), Field("token", "API key", secret = true))),
        api("zapierhook", "Zapier webhook", "Automation", "⚡", "{url}", "", "", "https://zapier.com/apps/webhook/integrations",
            "POST '' with a JSON body to trigger your Zap.", listOf(Field("url", "Catch Hook URL", secret = true))),
        api("makehook", "Make webhook", "Automation", "🟣", "{url}", "", "", "https://www.make.com/en/help/tools/webhooks", "POST '' with JSON.", listOf(Field("url", "Webhook URL", secret = true))),
        api("ifttt", "IFTTT", "Automation", "🔁", "https://maker.ifttt.com", "", "", "https://ifttt.com/maker_webhooks/settings",
            "POST /trigger/{event}/with/key/{token} with {\"value1\":…}", tok("Webhooks key")),
        api("pipedreamapi", "Pipedream API", "Automation", "🌊", "https://api.pipedream.com/v1", BEARER, "/users/me", "https://pipedream.com/settings/account"),
    )

    // ───────────── productivity ─────────────
    private val productivity = listOf(
        api("notion", "Notion", "Productivity", "📝", "https://api.notion.com/v1", "$BEARER;H:Notion-Version=2022-06-28", "/users/me",
            "https://www.notion.so/profile/integrations", "POST /search {query}, GET /pages/{id}, POST /pages {parent:{database_id},properties}, PATCH /pages/{id}, " +
                "GET /blocks/{id}/children, PATCH /blocks/{id}/children {children:[…]}, POST /databases/{id}/query. Share pages with the integration first.", popular = true),
        api("todoist", "Todoist", "Productivity", "✅", "https://api.todoist.com/rest/v2", BEARER, "/projects", "https://app.todoist.com/app/settings/integrations/developer",
            "GET /tasks?filter=today, POST /tasks {content,due_string,priority}, POST /tasks/{id}/close, GET /projects", popular = true),
        api("trello", "Trello", "Productivity", "📋", "https://api.trello.com/1", "Q:key={key}&token={token}", "/members/me", "https://trello.com/power-ups/admin",
            "GET /members/me/boards, GET /boards/{id}/lists, POST /cards?idList=…&name=…", listOf(Field("key", "API key"), Field("token", "Token", secret = true)), true),
        api("asana", "Asana", "Productivity", "🔺", "https://app.asana.com/api/1.0", BEARER, "/users/me", "https://app.asana.com/0/my-apps",
            "GET /workspaces, GET /tasks?assignee=me&workspace=…, POST /tasks {data:{name,workspace,assignee}}", popular = true),
        api("clickup", "ClickUp", "Productivity", "🟪", "https://api.clickup.com/api/v2", "H:Authorization={token}", "/user", "https://app.clickup.com/settings/apps",
            "GET /team, GET /list/{id}/task, POST /list/{id}/task {name}"),
        api("linear", "Linear", "Productivity", "📐", "https://api.linear.app", "H:Authorization={token}", "POST /graphql {\"query\":\"{ viewer { name } }\"}",
            "https://linear.app/settings/account/security", "GraphQL at POST /graphql: issues, createIssue, teams", popular = true),
        api("monday", "monday.com", "Productivity", "🟥", "https://api.monday.com/v2", "H:Authorization={token}", "POST  {\"query\":\"{ me { name } }\"}",
            "https://support.monday.com/hc/en-us/articles/360005144659", "GraphQL at POST '' : boards, items, create_item"),
        api("airtable", "Airtable", "Productivity", "🟨", "https://api.airtable.com/v0", BEARER, "/meta/whoami", "https://airtable.com/create/tokens",
            "GET /meta/bases, GET /{baseId}/{table}?maxRecords=20, POST /{baseId}/{table} {records:[{fields}]}", popular = true),
        api("coda", "Coda", "Productivity", "🟧", "https://coda.io/apis/v1", BEARER, "/whoami", "https://coda.io/account", "GET /docs, GET /docs/{id}/tables/{t}/rows"),
        api("jira", "Jira", "Productivity", "🔷", "https://{site}/rest/api/3", "B:{email}:{token}", "/myself", "https://id.atlassian.com/manage-profile/security/api-tokens",
            "GET /search?jql=assignee=currentUser(), POST /issue", listOf(Field("site", "Site", "yourteam.atlassian.net"), Field("email", "Email"), Field("token", "API token", secret = true)), true),
        api("confluence", "Confluence", "Productivity", "📘", "https://{site}/wiki/rest/api", "B:{email}:{token}", "/user/current", "https://id.atlassian.com/manage-profile/security/api-tokens",
            "GET /search?cql=…, GET /content/{id}?expand=body.storage", listOf(Field("site", "Site", "yourteam.atlassian.net"), Field("email", "Email"), Field("token", "API token", secret = true))),
        api("smartsheet", "Smartsheet", "Productivity", "📊", "https://api.smartsheet.com/2.0", BEARER, "/users/me", "https://app.smartsheet.com/b/home?routeTo=apiAccess"),
        api("wrike", "Wrike", "Productivity", "✔️", "https://www.wrike.com/api/v4", BEARER, "/contacts?me=true", "https://www.wrike.com/frontend/apps/index.html#/api"),
        api("miro", "Miro", "Productivity", "🟡", "https://api.miro.com/v2", BEARER, "/boards?limit=1", "https://miro.com/app/settings/user-profile/apps"),
        api("figma", "Figma", "Productivity", "🎨", "https://api.figma.com/v1", "H:X-Figma-Token={token}", "/me", "https://www.figma.com/developers/api#access-tokens",
            "GET /files/{key}, GET /files/{key}/comments, POST /files/{key}/comments"),
        api("obsidian", "Obsidian (Local REST API)", "Productivity", "💎", "{host}", BEARER, "/", "https://github.com/coddingtonbear/obsidian-local-rest-api",
            "GET /vault/, GET /vault/{path}, PUT /vault/{path} (text), POST /search/simple/?query=…", listOf(host("Address (http, same Wi-Fi)", "http://192.168.1.10:27123"), Field("token", "API key", secret = true))),
        api("anki", "Anki (AnkiConnect)", "Productivity", "🃏", "{host}", "", "POST  {\"action\":\"version\",\"version\":6}", "https://foosoft.net/projects/anki-connect/",
            "POST '' {action:addNote|findNotes|deckNames, version:6, params}", listOf(host("AnkiConnect address", "http://192.168.1.10:8765"))),
        api("raindrop", "Raindrop.io", "Productivity", "💧", "https://api.raindrop.io/rest/v1", BEARER, "/user", "https://app.raindrop.io/settings/integrations",
            "GET /raindrops/0?search=…, POST /raindrop {link,title}"),
        api("readwise", "Readwise", "Productivity", "📚", "https://readwise.io/api/v2", "H:Authorization=Token {token}", "/auth/", "https://readwise.io/access_token",
            "GET /highlights?page_size=20, GET /books"),
        api("toggl", "Toggl Track", "Productivity", "⏱️", "https://api.track.toggl.com/api/v9", "B:{token}:api_token", "/me", "https://track.toggl.com/profile",
            "GET /me/time_entries/current, POST /workspaces/{wid}/time_entries"),
        api("clockify", "Clockify", "Productivity", "🕒", "https://api.clockify.me/api/v1", "H:X-Api-Key={token}", "/user", "https://app.clockify.me/user/preferences#advanced"),
        api("harvest", "Harvest", "Productivity", "🌾", "https://api.harvestapp.com/v2", "$BEARER;H:Harvest-Account-Id={account}", "/users/me", "https://id.getharvest.com/developers",
            fields = listOf(Field("account", "Account ID"), Field("token", "Token", secret = true))),
        api("rescuetime", "RescueTime", "Productivity", "⏳", "https://www.rescuetime.com/anapi", "Q:key={token}", "/daily_summary_feed", "https://www.rescuetime.com/anapi/manage"),
        api("wakatime", "WakaTime", "Productivity", "⌨️", "https://wakatime.com/api/v1", "B:{token}:", "/users/current", "https://wakatime.com/settings/api-key"),
        api("habitica", "Habitica", "Productivity", "🗡️", "https://habitica.com/api/v3", "H:x-api-user={user};H:x-api-key={token};H:x-client={user}-Atlas", "/user",
            "https://habitica.com/user/settings/api", "GET /tasks/user, POST /tasks/{id}/score/up", listOf(Field("user", "User ID"), Field("token", "API token", secret = true))),
        api("calendly", "Calendly", "Productivity", "📅", "https://api.calendly.com", BEARER, "/users/me", "https://calendly.com/integrations/api_webhooks",
            "GET /scheduled_events?user={uri}&status=active"),
        api("calcom", "Cal.com", "Productivity", "🗓️", "https://api.cal.com/v2", "$BEARER;H:cal-api-version=2024-08-13", "/me", "https://app.cal.com/settings/developer/api-keys", "GET /bookings"),
        api("typeform", "Typeform", "Productivity", "📨", "https://api.typeform.com", BEARER, "/me", "https://admin.typeform.com/user/tokens", "GET /forms, GET /forms/{id}/responses"),
        api("jotform", "Jotform", "Productivity", "📄", "https://api.jotform.com", "H:APIKEY={token}", "/user", "https://www.jotform.com/myaccount/api"),
        api("surveymonkey", "SurveyMonkey", "Productivity", "🐒", "https://api.surveymonkey.com/v3", BEARER, "/users/me", "https://developer.surveymonkey.com/apps/"),
        api("tally", "Tally", "Productivity", "📝", "https://api.tally.so", BEARER, "/users/me", "https://tally.so/settings/api-keys"),
    )

    // ───────────── messaging & email ─────────────
    private val comms = listOf(
        api("slack", "Slack", "Messaging", "💬", "https://slack.com/api", BEARER, "/auth.test", "https://api.slack.com/apps",
            "POST /chat.postMessage {channel,text}, GET /conversations.list, GET /conversations.history?channel=…, GET /search.messages?query=… (user token)", popular = true),
        api("slackhook", "Slack webhook", "Messaging", "💬", "{url}", "", "", "https://api.slack.com/messaging/webhooks", "POST '' {\"text\":…}", listOf(Field("url", "Webhook URL", secret = true))),
        api("discord", "Discord bot", "Messaging", "🎮", "https://discord.com/api/v10", "H:Authorization=Bot {token}", "/users/@me", "https://discord.com/developers/applications",
            "POST /channels/{id}/messages {content}, GET /users/@me/guilds", popular = true),
        api("discordhook", "Discord webhook", "Messaging", "🎮", "{url}", "", "", "https://support.discord.com/hc/en-us/articles/228383668", "POST '' {\"content\":…}",
            listOf(Field("url", "Webhook URL", secret = true))),
        api("telegrambot", "Telegram bot", "Messaging", "✈️", "https://api.telegram.org/bot{token}", "", "/getMe", "https://t.me/BotFather",
            "POST /sendMessage {chat_id,text}, GET /getUpdates", tok("Bot token"), true),
        api("teamshook", "Microsoft Teams webhook", "Messaging", "🟦", "{url}", "", "", "https://learn.microsoft.com/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook",
            "POST '' {\"text\":…}", listOf(Field("url", "Webhook URL", secret = true))),
        api("whatsappcloud", "WhatsApp Business (Cloud API)", "Messaging", "🟢", "https://graph.facebook.com/v20.0", BEARER, "/{phone_id}", "https://developers.facebook.com/apps",
            "POST /{phone_id}/messages {messaging_product:'whatsapp',to,type:'text',text:{body}}", listOf(Field("phone_id", "Phone number ID"), Field("token", "Access token", secret = true))),
        api("mattermost", "Mattermost", "Messaging", "🔵", "{host}/api/v4", BEARER, "/users/me", "https://docs.mattermost.com/developer/personal-access-tokens.html",
            fields = listOf(host("Server", "https://"), Field("token", "Personal token", secret = true))),
        api("rocketchat", "Rocket.Chat", "Messaging", "🚀", "{host}/api/v1", "H:X-Auth-Token={token};H:X-User-Id={user}", "/me", "https://docs.rocket.chat/",
            fields = listOf(host("Server", "https://"), Field("user", "User ID"), Field("token", "Token", secret = true))),
        api("zulip", "Zulip", "Messaging", "💠", "{host}/api/v1", "B:{email}:{token}", "/users/me", "https://zulip.com/api/api-keys",
            fields = listOf(host("Server", "https://yourorg.zulipchat.com"), Field("email", "Bot email"), Field("token", "API key", secret = true))),
        api("twilio", "Twilio (SMS & calls)", "Messaging", "📞", "https://api.twilio.com/2010-04-01", "B:{sid}:{token}", "/Accounts/{sid}.json", "https://console.twilio.com",
            "POST /Accounts/{sid}/Messages.json (form: To, From, Body)", listOf(Field("sid", "Account SID"), Field("token", "Auth token", secret = true))),
        api("webex", "Webex", "Messaging", "🌀", "https://webexapis.com/v1", BEARER, "/people/me", "https://developer.webex.com/docs/getting-started"),
        api("sendgrid", "SendGrid", "Email", "✉️", "https://api.sendgrid.com/v3", BEARER, "/user/profile", "https://app.sendgrid.com/settings/api_keys", "POST /mail/send"),
        api("mailgun", "Mailgun", "Email", "📧", "https://api.mailgun.net/v3", "B:api:{token}", "/domains", "https://app.mailgun.com/settings/api_security"),
        api("postmark", "Postmark", "Email", "📮", "https://api.postmarkapp.com", "H:X-Postmark-Server-Token={token};H:Accept=application/json", "/server", "https://account.postmarkapp.com/servers"),
        api("resend", "Resend", "Email", "📤", "https://api.resend.com", BEARER, "/domains", "https://resend.com/api-keys", "POST /emails {from,to,subject,html}"),
        api("brevo", "Brevo", "Email", "💌", "https://api.brevo.com/v3", "H:api-key={token}", "/account", "https://app.brevo.com/settings/keys/api"),
        api("mailchimp", "Mailchimp", "Email", "🐵", "https://{dc}.api.mailchimp.com/3.0", "B:atlas:{token}", "/", "https://us1.admin.mailchimp.com/account/api/",
            "GET /lists, GET /campaigns?count=10", listOf(Field("dc", "Data centre (end of the key)", "us21"), Field("token", "API key", secret = true))),
        api("kit", "Kit (ConvertKit)", "Email", "📬", "https://api.convertkit.com/v3", "Q:api_secret={token}", "/account", "https://app.kit.com/account_settings/developer_settings"),
        api("beehiiv", "beehiiv", "Email", "🐝", "https://api.beehiiv.com/v2", BEARER, "/publications", "https://app.beehiiv.com/settings/workspace/api"),
        api("buttondown", "Buttondown", "Email", "🔘", "https://api.buttondown.email/v1", "H:Authorization=Token {token}", "/subscribers", "https://buttondown.email/settings"),
        api("pushbullet", "Pushbullet", "Messaging", "🔔", "https://api.pushbullet.com/v2", "H:Access-Token={token}", "/users/me", "https://www.pushbullet.com/#settings/account",
            "POST /pushes {type:'note',title,body}"),
        api("pushover", "Pushover", "Messaging", "🔔", "https://api.pushover.net/1", "", "", "https://pushover.net/apps/build",
            "POST /messages.json {token:'{token}',user:'{user}',message}", listOf(Field("user", "User key"), Field("token", "App token", secret = true))),
        api("ntfy", "ntfy", "Messaging", "📣", "{host}", "", "", "https://ntfy.sh", "POST /{topic} with the message as body", listOf(host("Server", "https://ntfy.sh"))),
        api("gotify", "Gotify", "Messaging", "📯", "{host}", "H:X-Gotify-Key={token}", "/application", "https://gotify.net/docs/",
            fields = listOf(host("Server", "https://"), Field("token", "Client token", secret = true))),
        api("mastodon", "Mastodon", "Social", "🐘", "{host}/api/v1", BEARER, "/accounts/verify_credentials", "https://docs.joinmastodon.org/client/token/",
            "POST /statuses {status}, GET /timelines/home", listOf(host("Instance", "https://mastodon.social"), Field("token", "Access token", secret = true))),
        api("intercom", "Intercom", "Support", "💭", "https://api.intercom.io", BEARER, "/me", "https://app.intercom.com/a/apps/_/developer-hub"),
        api("zendesk", "Zendesk", "Support", "🎧", "https://{sub}.zendesk.com/api/v2", "B:{email}/token:{token}", "/users/me", "https://support.zendesk.com/hc/en-us/articles/4408889192858",
            "GET /tickets?sort_order=desc, POST /tickets", listOf(Field("sub", "Subdomain"), Field("email", "Email"), Field("token", "API token", secret = true))),
        api("freshdesk", "Freshdesk", "Support", "🟩", "https://{sub}.freshdesk.com/api/v2", "B:{token}:X", "/agents/me", "https://support.freshdesk.com/support/solutions/articles/215517",
            fields = listOf(Field("sub", "Subdomain"), Field("token", "API key", secret = true))),
        api("front", "Front", "Support", "📥", "https://api2.frontapp.com", BEARER, "/me", "https://dev.frontapp.com/docs/create-and-revoke-api-tokens"),
    )

    // ───────────── developer ─────────────
    private val dev = listOf(
        api("github", "GitHub", "Developer", "🐙", "https://api.github.com", "$BEARER;H:Accept=application/vnd.github+json", "/user", "https://github.com/settings/tokens",
            "GET /user/repos?sort=updated, GET /repos/{o}/{r}/issues, POST /repos/{o}/{r}/issues {title,body}, GET /notifications, GET /search/issues?q=…", popular = true),
        mcp("githubmcp", "GitHub (MCP)", "Developer", "🐙", "https://api.githubcopilot.com/mcp/", "https://github.com/settings/tokens",
            "GitHub's own MCP server: issues, PRs, code search, Actions — as tools.", tok("Personal access token")),
        api("gitlab", "GitLab", "Developer", "🦊", "{host}/api/v4", "H:PRIVATE-TOKEN={token}", "/user", "https://gitlab.com/-/user_settings/personal_access_tokens",
            "GET /projects?membership=true, GET /issues?scope=assigned_to_me", listOf(host("Server", "https://gitlab.com"), Field("token", "Token", secret = true))),
        api("bitbucket", "Bitbucket", "Developer", "🪣", "https://api.bitbucket.org/2.0", "B:{user}:{token}", "/user", "https://bitbucket.org/account/settings/app-passwords/",
            fields = listOf(Field("user", "Username"), Field("token", "App password", secret = true))),
        api("gitea", "Gitea / Forgejo", "Developer", "🍵", "{host}/api/v1", "H:Authorization=token {token}", "/user", "https://docs.gitea.com/development/api-usage",
            fields = listOf(host("Server", "https://"), Field("token", "Token", secret = true))),
        api("vercel", "Vercel", "Developer", "▲", "https://api.vercel.com", BEARER, "/v2/user", "https://vercel.com/account/tokens", "GET /v6/deployments?limit=5, GET /v9/projects"),
        api("netlify", "Netlify", "Developer", "🌐", "https://api.netlify.com/api/v1", BEARER, "/user", "https://app.netlify.com/user/applications#personal-access-tokens"),
        api("cloudflare", "Cloudflare", "Developer", "☁️", "https://api.cloudflare.com/client/v4", BEARER, "/user/tokens/verify", "https://dash.cloudflare.com/profile/api-tokens",
            "GET /zones, GET /zones/{id}/dns_records"),
        api("digitalocean", "DigitalOcean", "Developer", "🌊", "https://api.digitalocean.com/v2", BEARER, "/account", "https://cloud.digitalocean.com/account/api/tokens",
            "GET /droplets, POST /droplets/{id}/actions {type:'reboot'}"),
        api("heroku", "Heroku", "Developer", "🟪", "https://api.heroku.com", "$BEARER;H:Accept=application/vnd.heroku+json; version=3", "/account", "https://dashboard.heroku.com/account/applications"),
        api("render", "Render", "Developer", "🎛️", "https://api.render.com/v1", BEARER, "/owners", "https://dashboard.render.com/u/settings#api-keys", "GET /services, POST /services/{id}/deploys"),
        api("railway", "Railway", "Developer", "🚂", "https://backboard.railway.com/graphql/v2", BEARER, "POST  {\"query\":\"{ me { name } }\"}", "https://railway.com/account/tokens"),
        api("flyio", "Fly.io", "Developer", "🎈", "https://api.machines.dev/v1", BEARER, "/apps?org_slug=personal", "https://fly.io/user/personal_access_tokens"),
        api("supabase", "Supabase", "Developer", "⚡", "https://api.supabase.com/v1", BEARER, "/projects", "https://supabase.com/dashboard/account/tokens"),
        api("sentry", "Sentry", "Developer", "🛡️", "https://sentry.io/api/0", BEARER, "/organizations/", "https://sentry.io/settings/account/api/auth-tokens/",
            "GET /projects/{org}/{proj}/issues/?query=is:unresolved"),
        api("datadog", "Datadog", "Developer", "🐶", "https://api.datadoghq.com/api/v1", "H:DD-API-KEY={token};H:DD-APPLICATION-KEY={app}", "/validate", "https://app.datadoghq.com/organization-settings/api-keys",
            fields = listOf(Field("app", "Application key", secret = true), Field("token", "API key", secret = true))),
        api("pagerduty", "PagerDuty", "Developer", "🚨", "https://api.pagerduty.com", "H:Authorization=Token token={token};H:Accept=application/vnd.pagerduty+json;version=2", "/abilities",
            "https://support.pagerduty.com/main/docs/api-access-keys", "GET /incidents?statuses[]=triggered"),
        api("grafana", "Grafana", "Developer", "📈", "{host}/api", BEARER, "/user", "https://grafana.com/docs/grafana/latest/administration/service-accounts/",
            fields = listOf(host("Server", "https://"), Field("token", "Service account token", secret = true))),
        api("betterstack", "Better Stack", "Developer", "🟢", "https://uptime.betterstack.com/api/v2", BEARER, "/monitors", "https://betterstack.com/docs/uptime/api/getting-started-with-uptime-api/"),
        api("postman", "Postman", "Developer", "📮", "https://api.getpostman.com", "H:X-Api-Key={token}", "/me", "https://web.postman.co/settings/me/api-keys"),
        api("circleci", "CircleCI", "Developer", "⭕", "https://circleci.com/api/v2", "H:Circle-Token={token}", "/me", "https://app.circleci.com/settings/user/tokens"),
        mcp("scrapling", "Scrapling web scraper", "Developer", "🕷️", "{url}", "https://github.com/D4Vinci/Scrapling",
            "Adaptive scraping on your PC: fast HTTP with browser fingerprints, full browser rendering for JavaScript sites, stealth fetches for protected pages, " +
                "persistent sessions and screenshots — 13 tools. On the PC: pip install \"scrapling[ai]\", scrapling install, then " +
                "scrapling-mcp --http --host 0.0.0.0 --port 8000 --auth-token YOUR_TOKEN. Paste http://PC-IP:8000/mcp and the token here.",
            listOf(Field("url", "Server URL", "http://192.168.1.10:8000/mcp", default = "http://192.168.1.10:8000/mcp"), Field("token", "Auth token (optional)", secret = true)), true),
        mcp("deepwiki", "DeepWiki (any GitHub repo explained)", "Developer", "📖", "https://mcp.deepwiki.com/mcp", "https://deepwiki.com", "Ask questions about any public repo.", emptyList()),
        mcp("context7", "Context7 (live library docs)", "Developer", "📚", "https://mcp.context7.com/mcp", "https://context7.com", "Up-to-date docs for thousands of libraries.", emptyList()),
    )

    // ───────────── AI services ─────────────
    private val ai = listOf(
        api("openai", "OpenAI", "AI", "🤖", "https://api.openai.com/v1", BEARER, "/models", "https://platform.openai.com/api-keys", "POST /images/generations, POST /audio/speech"),
        api("hermes", "Hermes Agent (Nous Research)", "AI", "☤", "{host}/v1", BEARER, "/models", "https://github.com/NousResearch/hermes-agent",
            "A full agent on your PC with terminal, files, browser, web search, memory and its own skills. Hand it computer work with ask_hermes {task}. " +
                "On the PC: API_SERVER_ENABLED=true, API_SERVER_HOST=0.0.0.0, API_SERVER_KEY=…; it listens on port 8642.",
            listOf(host("PC address", "http://192.168.1.10:8642"), Field("token", "API_SERVER_KEY", secret = true)), true),
        api("anthropic", "Anthropic", "AI", "🧠", "https://api.anthropic.com/v1", "H:x-api-key={token};H:anthropic-version=2023-06-01", "/models", "https://console.anthropic.com/settings/keys"),
        api("gemini", "Google Gemini", "AI", "✨", "https://generativelanguage.googleapis.com/v1beta", "Q:key={token}", "/models", "https://aistudio.google.com/apikey"),
        api("groq", "Groq", "AI", "⚡", "https://api.groq.com/openai/v1", BEARER, "/models", "https://console.groq.com/keys"),
        api("openrouter", "OpenRouter", "AI", "🔀", "https://openrouter.ai/api/v1", BEARER, "/auth/key", "https://openrouter.ai/keys"),
        api("mistral", "Mistral", "AI", "🌬️", "https://api.mistral.ai/v1", BEARER, "/models", "https://console.mistral.ai/api-keys"),
        api("huggingface", "Hugging Face", "AI", "🤗", "https://huggingface.co/api", BEARER, "/whoami-v2", "https://huggingface.co/settings/tokens"),
        mcp("hfmcp", "Hugging Face (MCP)", "AI", "🤗", "https://huggingface.co/mcp", "https://huggingface.co/settings/mcp", "Search models, datasets, papers and run Spaces as tools.", tok("HF token")),
        api("replicate", "Replicate", "AI", "🔁", "https://api.replicate.com/v1", BEARER, "/account", "https://replicate.com/account/api-tokens"),
        api("elevenlabs", "ElevenLabs", "AI", "🗣️", "https://api.elevenlabs.io/v1", "H:xi-api-key={token}", "/user", "https://elevenlabs.io/app/settings/api-keys"),
        api("deepgram", "Deepgram", "AI", "🎙️", "https://api.deepgram.com/v1", "H:Authorization=Token {token}", "/projects", "https://console.deepgram.com"),
        api("assemblyai", "AssemblyAI", "AI", "📝", "https://api.assemblyai.com/v2", "H:Authorization={token}", "/transcript?limit=1", "https://www.assemblyai.com/app/account"),
        api("stability", "Stability AI", "AI", "🖼️", "https://api.stability.ai/v1", BEARER, "/user/account", "https://platform.stability.ai/account/keys"),
        api("deepl", "DeepL", "AI", "🌍", "https://api-free.deepl.com/v2", "H:Authorization=DeepL-Auth-Key {token}", "/usage", "https://www.deepl.com/your-account/keys",
            "POST /translate {text:[…],target_lang:'UR'}"),
        api("pinecone", "Pinecone", "AI", "🌲", "https://api.pinecone.io", "H:Api-Key={token}", "/indexes", "https://app.pinecone.io"),
        mcp("exa", "Exa search (MCP)", "AI", "🔍", "https://mcp.exa.ai/mcp?exaApiKey={token}", "https://dashboard.exa.ai/api-keys", "Neural web search and page contents as tools.", tok("Exa key")),
        mcp("tavily", "Tavily search (MCP)", "AI", "🔎", "https://mcp.tavily.com/mcp/?tavilyApiKey={token}", "https://app.tavily.com", "Web search, extract and crawl tools.", tok("Tavily key")),
    )

    // ───────────── money & commerce ─────────────
    private val money = listOf(
        api("stripe", "Stripe", "Money", "💳", "https://api.stripe.com/v1", BEARER, "/balance", "https://dashboard.stripe.com/apikeys",
            "GET /charges?limit=10, GET /customers?email=…, GET /invoices?status=open. Bodies are form-encoded.", popular = true),
        mcp("stripemcp", "Stripe (MCP)", "Money", "💳", "https://mcp.stripe.com", "https://dashboard.stripe.com/apikeys", "Stripe's own tools. Use a restricted key.", tok("Restricted key")),
        api("lemonsqueezy", "Lemon Squeezy", "Money", "🍋", "https://api.lemonsqueezy.com/v1", "$BEARER;H:Accept=application/vnd.api+json", "/users/me", "https://app.lemonsqueezy.com/settings/api"),
        api("gumroad", "Gumroad", "Money", "🛍️", "https://api.gumroad.com/v2", "Q:access_token={token}", "/user", "https://app.gumroad.com/settings/advanced"),
        api("paddle", "Paddle", "Money", "🏓", "https://api.paddle.com", BEARER, "/event-types", "https://vendors.paddle.com/authentication-v2"),
        api("razorpay", "Razorpay", "Money", "💠", "https://api.razorpay.com/v1", "B:{key}:{token}", "/payments?count=1", "https://dashboard.razorpay.com/app/keys",
            fields = listOf(Field("key", "Key ID"), Field("token", "Key secret", secret = true))),
        api("square", "Square", "Money", "⬛", "https://connect.squareup.com/v2", BEARER, "/locations", "https://developer.squareup.com/apps"),
        api("shopify", "Shopify", "Commerce", "🛒", "https://{store}.myshopify.com/admin/api/2024-07", "H:X-Shopify-Access-Token={token}", "/shop.json",
            "https://help.shopify.com/en/manual/apps/app-types/custom-apps", "GET /orders.json?status=any&limit=10, GET /products.json",
            listOf(Field("store", "Store name", "mystore"), Field("token", "Admin API token", secret = true)), true),
        api("woocommerce", "WooCommerce", "Commerce", "🟣", "{host}/wp-json/wc/v3", "B:{key}:{token}", "/system_status", "https://woocommerce.com/document/woocommerce-rest-api/",
            "GET /orders?per_page=10, GET /products", listOf(host("Site", "https://"), Field("key", "Consumer key"), Field("token", "Consumer secret", secret = true))),
        api("wise", "Wise", "Money", "💸", "https://api.transferwise.com", BEARER, "/v1/profiles", "https://wise.com/settings/api-tokens", "GET /v4/profiles/{id}/balances?types=STANDARD"),
        api("splitwise", "Splitwise", "Money", "➗", "https://secure.splitwise.com/api/v3.0", BEARER, "/get_current_user", "https://secure.splitwise.com/apps",
            "GET /get_friends, GET /get_expenses?limit=20, POST /create_expense", popular = true),
        api("ynab", "YNAB", "Money", "💰", "https://api.ynab.com/v1", BEARER, "/user", "https://app.ynab.com/settings/developer", "GET /budgets, GET /budgets/last-used/transactions"),
        api("lunchmoney", "Lunch Money", "Money", "🥪", "https://dev.lunchmoney.app/v1", BEARER, "/me", "https://my.lunchmoney.app/developers"),
        api("coingecko", "CoinGecko", "Money", "🦎", "https://api.coingecko.com/api/v3", "", "/ping", "https://www.coingecko.com/en/api",
            "GET /simple/price?ids=bitcoin&vs_currencies=pkr,usd", emptyList()),
        api("binance", "Binance market data", "Money", "🟡", "https://api.binance.com", "", "/api/v3/ping", "https://www.binance.com/en/binance-api",
            "GET /api/v3/ticker/price?symbol=BTCUSDT (public, read-only)", emptyList()),
        api("alphavantage", "Alpha Vantage (stocks)", "Money", "📈", "https://www.alphavantage.co", "Q:apikey={token}", "/query?function=GLOBAL_QUOTE&symbol=IBM",
            "https://www.alphavantage.co/support/#api-key"),
        api("finnhub", "Finnhub (stocks)", "Money", "📊", "https://finnhub.io/api/v1", "Q:token={token}", "/quote?symbol=AAPL", "https://finnhub.io/dashboard"),
        api("fx", "Exchange rates", "Money", "💱", "https://open.er-api.com/v6", "", "/latest/USD", "https://www.exchangerate-api.com/docs/free", "GET /latest/PKR", emptyList()),
    )

    // ───────────── CRM & sales ─────────────
    private val crm = listOf(
        api("hubspot", "HubSpot", "CRM", "🧡", "https://api.hubapi.com", BEARER, "/account-info/v3/details", "https://developers.hubspot.com/docs/api/private-apps",
            "POST /crm/v3/objects/contacts/search, POST /crm/v3/objects/contacts {properties}", popular = true),
        api("pipedrive", "Pipedrive", "CRM", "🟢", "https://api.pipedrive.com/v1", "Q:api_token={token}", "/users/me", "https://app.pipedrive.com/settings/api", "GET /deals?status=open, POST /activities"),
        api("salesforce", "Salesforce", "CRM", "☁️", "{host}/services/data/v60.0", BEARER, "/", "https://developer.salesforce.com/docs/atlas.en-us.api_rest.meta/api_rest/quickstart_oauth.htm",
            "GET /query?q=SELECT+Name+FROM+Account+LIMIT+10", listOf(host("Instance URL", "https://yourorg.my.salesforce.com"), Field("token", "Access token", secret = true))),
        api("close", "Close", "CRM", "🔒", "https://api.close.com/api/v1", "B:{token}:", "/me/", "https://app.close.com/settings/developer/"),
        api("attio", "Attio", "CRM", "🅰️", "https://api.attio.com/v2", BEARER, "/self", "https://app.attio.com/settings/developers"),
    )

    // ───────────── files & storage ─────────────
    private val storage = listOf(
        api("dropbox", "Dropbox", "Files", "📦", "https://api.dropboxapi.com/2", BEARER, "POST /users/get_current_account null", "https://www.dropbox.com/developers/apps",
            "POST /files/list_folder {path:''}, POST /files/search_v2 {query}", popular = true),
        api("box", "Box", "Files", "📁", "https://api.box.com/2.0", BEARER, "/users/me", "https://app.box.com/developers/console", "GET /folders/0/items, GET /search?query=…"),
        api("nextcloud", "Nextcloud", "Files", "☁️", "{host}/ocs/v2.php", "B:{user}:{token};H:OCS-APIRequest=true", "/cloud/user?format=json", "https://docs.nextcloud.com/",
            fields = listOf(host("Server", "https://"), Field("user", "Username"), Field("token", "App password", secret = true))),
    )

    // ───────────── smart home ─────────────
    private val home = listOf(
        api("homeassistant", "Home Assistant", "Smart home", "🏠", "{host}/api", BEARER, "/", "https://www.home-assistant.io/docs/authentication/",
            "GET /states, GET /states/{entity_id}, POST /services/{domain}/{service} {entity_id} e.g. /services/light/turn_on",
            listOf(host("Address", "http://homeassistant.local:8123"), Field("token", "Long-lived token", secret = true)), true),
        api("hue", "Philips Hue", "Smart home", "💡", "http://{host}/api/{token}", "", "/lights", "https://developers.meethue.com/develop/get-started-2/",
            "GET /lights, PUT /lights/{id}/state {on:true,bri:200}", listOf(Field("host", "Bridge IP", "192.168.1.20"), Field("token", "Bridge username", secret = true))),
        api("smartthings", "SmartThings", "Smart home", "🔷", "https://api.smartthings.com/v1", BEARER, "/devices", "https://account.smartthings.com/tokens",
            "GET /devices, POST /devices/{id}/commands {commands:[{component:'main',capability:'switch',command:'on'}]}"),
    )

    // ───────────── health ─────────────
    private val health = listOf(
        api("oura", "Oura", "Health", "💍", "https://api.ouraring.com/v2", BEARER, "/usercollection/personal_info", "https://cloud.ouraring.com/personal-access-tokens",
            "GET /usercollection/daily_sleep?start_date=…, /daily_readiness, /daily_activity"),
    )

    // ───────────── media & fun ─────────────
    private val media = listOf(
        api("lastfm", "Last.fm", "Media", "🎵", "https://ws.audioscrobbler.com/2.0", "Q:api_key={token}&format=json", "/?method=chart.gettopartists&limit=1",
            "https://www.last.fm/api/account/create", "?method=user.getrecenttracks&user=…"),
        api("trakt", "Trakt", "Media", "🎬", "https://api.trakt.tv", "H:trakt-api-key={token};H:trakt-api-version=2", "/movies/trending?limit=1", "https://trakt.tv/oauth/applications"),
        api("tmdb", "TMDB", "Media", "🎞️", "https://api.themoviedb.org/3", BEARER, "/configuration", "https://www.themoviedb.org/settings/api", fields = tok("API read access token")),
        api("omdb", "OMDb", "Media", "🍿", "https://www.omdbapi.com", "Q:apikey={token}", "/?t=Inception", "https://www.omdbapi.com/apikey.aspx"),
        api("plex", "Plex", "Media", "🟠", "{host}", "H:X-Plex-Token={token};H:Accept=application/json", "/identity", "https://support.plex.tv/articles/204059436/",
            fields = listOf(host("Server", "http://192.168.1.10:32400"), Field("token", "X-Plex-Token", secret = true))),
        api("jellyfin", "Jellyfin", "Media", "🟣", "{host}", "H:X-Emby-Token={token}", "/System/Info", "https://jellyfin.org/docs/",
            fields = listOf(host("Server", "http://192.168.1.10:8096"), Field("token", "API key", secret = true))),
        api("sonarr", "Sonarr", "Media", "📺", "{host}/api/v3", "H:X-Api-Key={token}", "/system/status", "https://wiki.servarr.com/sonarr",
            fields = listOf(host("Server", "http://192.168.1.10:8989"), Field("token", "API key", secret = true))),
        api("radarr", "Radarr", "Media", "🎥", "{host}/api/v3", "H:X-Api-Key={token}", "/system/status", "https://wiki.servarr.com/radarr",
            fields = listOf(host("Server", "http://192.168.1.10:7878"), Field("token", "API key", secret = true))),
        api("overseerr", "Overseerr / Jellyseerr", "Media", "🍿", "{host}/api/v1", "H:X-Api-Key={token}", "/auth/me", "https://docs.overseerr.dev",
            fields = listOf(host("Server", "http://192.168.1.10:5055"), Field("token", "API key", secret = true))),
        api("twitch", "Twitch", "Media", "🟪", "https://api.twitch.tv/helix", "$BEARER;H:Client-Id={client}", "/users", "https://twitchtokengenerator.com",
            fields = listOf(Field("client", "Client ID"), Field("token", "User token", secret = true))),
        api("youtubedata", "YouTube Data (read)", "Media", "▶️", "https://www.googleapis.com/youtube/v3", "Q:key={token}", "/search?part=snippet&q=news&maxResults=1",
            "https://console.cloud.google.com/apis/credentials", "GET /search?part=snippet&q=…, GET /videos?part=statistics&id=…"),
        api("steam", "Steam", "Games", "🎮", "https://api.steampowered.com", "Q:key={token}", "/ISteamNews/GetNewsForApp/v2/?appid=440&count=1", "https://steamcommunity.com/dev/apikey"),
        api("lichess", "Lichess", "Games", "♟️", "https://lichess.org/api", BEARER, "/account", "https://lichess.org/account/oauth/token"),
        api("chesscom", "Chess.com", "Games", "♞", "https://api.chess.com/pub", "", "/player/hikaru", "https://www.chess.com/news/view/published-data-api", "GET /player/{user}/stats", emptyList()),
        api("unsplash", "Unsplash", "Media", "📷", "https://api.unsplash.com", "H:Authorization=Client-ID {token}", "/photos/random", "https://unsplash.com/oauth/applications"),
        api("pexels", "Pexels", "Media", "🖼️", "https://api.pexels.com/v1", "H:Authorization={token}", "/curated?per_page=1", "https://www.pexels.com/api/"),
        api("giphy", "GIPHY", "Media", "🎞️", "https://api.giphy.com/v1", "Q:api_key={token}", "/gifs/trending?limit=1", "https://developers.giphy.com/dashboard/"),
    )

    // ───────────── information ─────────────
    private val info = listOf(
        api("newsapi", "NewsAPI", "News", "📰", "https://newsapi.org/v2", "H:X-Api-Key={token}", "/top-headlines?country=us&pageSize=1", "https://newsapi.org/register"),
        api("guardian", "The Guardian", "News", "🗞️", "https://content.guardianapis.com", "Q:api-key={token}", "/search?page-size=1", "https://open-platform.theguardian.com/access/"),
        api("gnews", "GNews", "News", "🌐", "https://gnews.io/api/v4", "Q:apikey={token}", "/top-headlines?max=1&country=pk", "https://gnews.io/"),
        api("openweather", "OpenWeather", "Weather", "⛅", "https://api.openweathermap.org/data/2.5", "Q:appid={token}&units=metric", "/weather?q=Lahore", "https://home.openweathermap.org/api_keys"),
        api("weatherapi", "WeatherAPI", "Weather", "🌦️", "https://api.weatherapi.com/v1", "Q:key={token}", "/current.json?q=Lahore", "https://www.weatherapi.com/my/"),
        api("googlemaps", "Google Maps Platform", "Maps", "🗺️", "https://maps.googleapis.com/maps/api", "Q:key={token}", "/geocode/json?address=Lahore",
            "https://console.cloud.google.com/google/maps-apis/credentials", "GET /place/textsearch/json?query=…, GET /directions/json?origin=…&destination=…"),
        api("mapbox", "Mapbox", "Maps", "🧭", "https://api.mapbox.com", "Q:access_token={token}", "/geocoding/v5/mapbox.places/Lahore.json?limit=1", "https://account.mapbox.com/access-tokens/"),
        api("opencage", "OpenCage", "Maps", "📍", "https://api.opencagedata.com/geocode/v1", "Q:key={token}", "/json?q=Lahore&limit=1", "https://opencagedata.com/dashboard#api-keys"),
        api("wolfram", "Wolfram|Alpha", "Knowledge", "🐺", "https://api.wolframalpha.com/v1", "Q:appid={token}", "/result?i=2%2B2", "https://developer.wolframalpha.com/access",
            "GET /result?i=… (short answer)"),
        api("nasa", "NASA", "Knowledge", "🚀", "https://api.nasa.gov", "Q:api_key={token}", "/planetary/apod", "https://api.nasa.gov/#signUp"),
        api("wikipedia", "Wikipedia", "Knowledge", "📘", "https://en.wikipedia.org/api/rest_v1", "", "/page/summary/Pakistan", "https://en.wikipedia.org/api/rest_v1/", "GET /page/summary/{title}", emptyList()),
        api("openlibrary", "Open Library", "Knowledge", "📗", "https://openlibrary.org", "", "/search.json?q=iqbal&limit=1", "https://openlibrary.org/developers/api", "GET /search.json?q=…", emptyList()),
        api("quran", "Al-Quran Cloud", "Deen", "📖", "https://api.alquran.cloud/v1", "", "/ayah/262", "https://alquran.cloud/api", "GET /ayah/{n}/editions/quran-uthmani,en.asad,ur.jalandhry", emptyList()),
        api("aladhan", "AlAdhan prayer times", "Deen", "🕌", "https://api.aladhan.com/v1", "", "/timingsByCity?city=Lahore&country=Pakistan&method=1", "https://aladhan.com/prayer-times-api",
            "GET /timingsByCity?city=…&country=…, GET /gToH (Hijri date)", emptyList()),
    )

    // ───────────── websites & marketing ─────────────
    private val web = listOf(
        api("wordpress", "WordPress", "Websites", "📰", "{host}/wp-json/wp/v2", "B:{user}:{token}", "/users/me", "https://make.wordpress.org/core/2020/11/05/application-passwords-integration-guide/",
            "GET /posts?per_page=5, POST /posts {title,content,status:'draft'}", listOf(host("Site", "https://"), Field("user", "Username"), Field("token", "Application password", secret = true)), true),
        api("webflow", "Webflow", "Websites", "🌊", "https://api.webflow.com/v2", BEARER, "/token/authorized_by", "https://developers.webflow.com/data/reference/authentication"),
        api("contentful", "Contentful", "Websites", "📦", "https://api.contentful.com", BEARER, "/users/me", "https://app.contentful.com/account/profile/cma_tokens"),
        api("strapi", "Strapi", "Websites", "🟦", "{host}/api", BEARER, "/users/me", "https://docs.strapi.io/", fields = listOf(host("Server", "https://"), Field("token", "API token", secret = true))),
        api("plausible", "Plausible Analytics", "Websites", "📊", "https://plausible.io/api/v1", BEARER, "/stats/realtime/visitors?site_id={site}", "https://plausible.io/settings",
            fields = listOf(Field("site", "Site domain"), Field("token", "API key", secret = true))),
        api("posthog", "PostHog", "Websites", "🦔", "https://us.posthog.com/api", BEARER, "/users/@me", "https://us.posthog.com/settings/user-api-keys"),
        api("bitly", "Bitly", "Websites", "🔗", "https://api-ssl.bitly.com/v4", BEARER, "/user", "https://app.bitly.com/settings/api/", "POST /shorten {long_url}"),
    )

    // ───────────── learning ─────────────
    private val learn = listOf(
        api("canvas", "Canvas LMS", "Learning", "🎓", "{host}/api/v1", BEARER, "/users/self", "https://community.canvaslms.com/t5/Student-Guide/How-do-I-manage-API-access-tokens-as-a-student/ta-p/273",
            "GET /courses, GET /users/self/upcoming_events, GET /courses/{id}/assignments", listOf(host("School's Canvas", "https://"), Field("token", "Access token", secret = true))),
        api("moodle", "Moodle", "Learning", "🟠", "{host}/webservice/rest/server.php", "Q:wstoken={token}&moodlewsrestformat=json", "?wsfunction=core_webservice_get_site_info",
            "https://docs.moodle.org/en/Security_keys", fields = listOf(host("Moodle site", "https://"), Field("token", "Web service token", secret = true))),
    )

    // ───────────── sign-in-only services (through a hub) ─────────────
    private val hubs = listOf(
        hub("gmail", "Gmail", "Email", "📧", true), hub("gcal", "Google Calendar (online)", "Productivity", "📆", true), hub("gdrive", "Google Drive", "Files", "🗂️", true),
        hub("gsheets", "Google Sheets", "Productivity", "📗", true), hub("gdocs", "Google Docs", "Productivity", "📘", true), hub("gtasks", "Google Tasks", "Productivity", "☑️"),
        hub("gcontacts", "Google Contacts", "Productivity", "👥"), hub("ganalytics", "Google Analytics", "Websites", "📊"), hub("gads", "Google Ads", "Marketing", "📢"),
        hub("youtubepost", "YouTube (upload & manage)", "Social", "▶️"), hub("outlook", "Outlook Mail & Calendar", "Email", "📨", true),
        hub("teams", "Microsoft Teams", "Messaging", "🟦", true), hub("onedrive", "OneDrive", "Files", "☁️"), hub("excel", "Excel Online", "Productivity", "📗"),
        hub("mstodo", "Microsoft To Do", "Productivity", "✔️"), hub("onenote", "OneNote", "Productivity", "📓"), hub("sharepoint", "SharePoint", "Files", "🗃️"),
        hub("quickbooks", "QuickBooks", "Money", "📒"), hub("xero", "Xero", "Money", "🔵"), hub("freshbooks", "FreshBooks", "Money", "🧾"), hub("zohocrm", "Zoho CRM", "CRM", "🟥"),
        hub("zohomail", "Zoho Mail", "Email", "📧"), hub("paypal", "PayPal", "Money", "🅿️", true), hub("etsy", "Etsy", "Commerce", "🧶"), hub("ebay", "eBay", "Commerce", "🛍️"),
        hub("amazonseller", "Amazon Seller Central", "Commerce", "📦"), hub("fbpages", "Facebook Pages", "Social", "📘", true), hub("instagram", "Instagram for Business", "Social", "📸", true),
        hub("linkedin", "LinkedIn", "Social", "💼", true), hub("x", "X (Twitter)", "Social", "𝕏", true), hub("tiktokbiz", "TikTok for Business", "Social", "🎵"),
        hub("pinterest", "Pinterest", "Social", "📌"), hub("reddit", "Reddit", "Social", "👽"), hub("threads", "Threads", "Social", "🧵"), hub("bluesky", "Bluesky", "Social", "🦋"),
        hub("spotify", "Spotify", "Media", "🎧", true), hub("fitbit", "Fitbit", "Health", "⌚"), hub("strava", "Strava", "Health", "🏃"), hub("whoop", "WHOOP", "Health", "💪"),
        hub("garmin", "Garmin Connect", "Health", "⌚"), hub("evernote", "Evernote", "Productivity", "🐘"), hub("pocket", "Pocket", "Productivity", "👝"),
        hub("ticktick", "TickTick", "Productivity", "✅"), hub("basecamp", "Basecamp", "Productivity", "⛺"), hub("helpscout", "Help Scout", "Support", "🛟"),
        hub("canva", "Canva", "Websites", "🎨"), hub("buffer", "Buffer", "Marketing", "📤"), hub("hootsuite", "Hootsuite", "Marketing", "🦉"), hub("zoom", "Zoom", "Messaging", "🎥", true),
        hub("docusign", "DocuSign", "Productivity", "✍️"), hub("eventbrite", "Eventbrite", "Productivity", "🎟️"), hub("fbads", "Meta Ads", "Marketing", "📣"),
        hub("tripit", "TripIt", "Travel", "🧳"), hub("googlehome", "Google Home", "Smart home", "🏠"), hub("alexa", "Amazon Alexa", "Smart home", "🔵"),
        hub("tuya", "Tuya / Smart Life", "Smart home", "🔌"), hub("icloud", "iCloud Mail & Calendar", "Email", "☁️"), hub("gchat", "Google Chat", "Messaging", "💬"),
        hub("zoominfo", "Apollo / ZoomInfo", "CRM", "🎯"), hub("gmeet", "Google Meet", "Messaging", "📹"),
    )

    // ───────────── phone apps (deep links) ─────────────
    private val apps = listOf(
        app("whatsapp", "WhatsApp", "Messaging", "🟢", "com.whatsapp", mapOf("send" to "https://wa.me/{to}?text={text}", "open" to ""), true),
        app("whatsappbiz", "WhatsApp Business", "Messaging", "🟢", "com.whatsapp.w4b", mapOf("send" to "https://wa.me/{to}?text={text}", "open" to "")),
        app("telegram", "Telegram", "Messaging", "✈️", "org.telegram.messenger", mapOf("send" to "tg://msg?text={text}", "chat" to "https://t.me/{to}", "open" to ""), true),
        app("signal", "Signal", "Messaging", "🔵", "org.thoughtcrime.securesms", mapOf("open" to "")),
        app("messenger", "Messenger", "Messaging", "💬", "com.facebook.orca", mapOf("chat" to "https://m.me/{to}", "open" to "")),
        app("instagramapp", "Instagram", "Social", "📸", "com.instagram.android", mapOf("profile" to "https://instagram.com/{to}", "open" to ""), true),
        app("facebook", "Facebook", "Social", "📘", "com.facebook.katana", mapOf("search" to "https://www.facebook.com/search/top?q={query}", "open" to "")),
        app("tiktok", "TikTok", "Social", "🎵", "com.zhiliaoapp.musically", mapOf("open" to "", "profile" to "https://www.tiktok.com/@{to}")),
        app("snapchat", "Snapchat", "Social", "👻", "com.snapchat.android", mapOf("open" to "")),
        app("xapp", "X", "Social", "𝕏", "com.twitter.android", mapOf("post" to "https://x.com/intent/post?text={text}", "search" to "https://x.com/search?q={query}", "open" to "")),
        app("linkedinapp", "LinkedIn", "Social", "💼", "com.linkedin.android", mapOf("search" to "https://www.linkedin.com/search/results/all/?keywords={query}", "open" to "")),
        app("redditapp", "Reddit", "Social", "👽", "com.reddit.frontpage", mapOf("search" to "https://www.reddit.com/search/?q={query}", "open" to "")),
        app("pinterestapp", "Pinterest", "Social", "📌", "com.pinterest", mapOf("search" to "https://www.pinterest.com/search/pins/?q={query}", "open" to "")),
        app("youtube", "YouTube", "Media", "▶️", "com.google.android.youtube", mapOf("search" to "https://www.youtube.com/results?search_query={query}", "open" to ""), true),
        app("ytmusic", "YouTube Music", "Media", "🎶", "com.google.android.apps.youtube.music", mapOf("search" to "https://music.youtube.com/search?q={query}", "open" to "")),
        app("spotifyapp", "Spotify", "Media", "🎧", "com.spotify.music", mapOf("search" to "spotify:search:{query}", "open" to ""), true),
        app("netflix", "Netflix", "Media", "🎬", "com.netflix.mediaclient", mapOf("search" to "https://www.netflix.com/search?q={query}", "open" to "")),
        app("primevideo", "Prime Video", "Media", "📺", "com.amazon.avod.thirdpartyclient", mapOf("open" to "")),
        app("gmaps", "Google Maps", "Travel", "🗺️", "com.google.android.apps.maps", mapOf("directions" to "google.navigation:q={query}", "search" to "geo:0,0?q={query}", "open" to ""), true),
        app("waze", "Waze", "Travel", "🚙", "com.waze", mapOf("directions" to "https://waze.com/ul?q={query}&navigate=yes", "open" to "")),
        app("uber", "Uber", "Travel", "🚗", "com.ubercab", mapOf("ride" to "uber://?action=setPickup&pickup=my_location&dropoff[formatted_address]={query}", "open" to ""), true),
        app("careem", "Careem", "Travel", "🟢", "com.careem.acma", mapOf("open" to ""), true),
        app("indrive", "inDrive", "Travel", "🚕", "sinet.startup.inDriver", mapOf("open" to "")),
        app("bykea", "Bykea", "Travel", "🛵", "com.bykea.pk", mapOf("open" to "")),
        app("yango", "Yango", "Travel", "🟡", "com.yandex.yango", mapOf("open" to "")),
        app("foodpanda", "foodpanda", "Food", "🐼", "com.global.foodpanda.android", mapOf("open" to ""), true),
        app("daraz", "Daraz", "Shopping", "🛍️", "com.daraz.android", mapOf("search" to "https://www.daraz.pk/catalog/?q={query}", "open" to ""), true),
        app("olx", "OLX", "Shopping", "🏷️", "com.olx.pk", mapOf("search" to "https://www.olx.com.pk/items/q-{query}", "open" to "")),
        app("amazon", "Amazon", "Shopping", "📦", "com.amazon.mShop.android.shopping", mapOf("search" to "https://www.amazon.com/s?k={query}", "open" to "")),
        app("aliexpress", "AliExpress", "Shopping", "🧧", "com.alibaba.aliexpresshd", mapOf("search" to "https://www.aliexpress.com/wholesale?SearchText={query}", "open" to "")),
        app("temu", "Temu", "Shopping", "🟠", "com.einnovation.temu", mapOf("open" to "")),
        app("gmailapp", "Gmail", "Email", "📧", "com.google.android.gm", mapOf("compose" to "mailto:{to}?subject={subject}&body={text}", "open" to ""), true),
        app("outlookapp", "Outlook", "Email", "📨", "com.microsoft.office.outlook", mapOf("compose" to "mailto:{to}?subject={subject}&body={text}", "open" to "")),
        app("phone", "Phone", "Messaging", "📞", "", mapOf("call" to "tel:{to}"), true),
        app("sms", "Messages", "Messaging", "💬", "", mapOf("send" to "smsto:{to}?body={text}"), true),
        app("chrome", "Chrome", "Web", "🌐", "com.android.chrome", mapOf("search" to "https://www.google.com/search?q={query}", "open_url" to "{query}")),
        app("playstore", "Play Store", "Web", "▶️", "com.android.vending", mapOf("search" to "market://search?q={query}", "open" to "")),
        app("gtranslate", "Google Translate", "Web", "🌍", "com.google.android.apps.translate", mapOf("translate" to "https://translate.google.com/?sl=auto&tl=ur&text={text}", "open" to "")),
        app("zoomapp", "Zoom", "Messaging", "🎥", "us.zoom.videomeetings", mapOf("join" to "zoomus://zoom.us/join?confno={to}", "open" to "")),
        app("meet", "Google Meet", "Messaging", "📹", "com.google.android.apps.tachyon", mapOf("join" to "https://meet.google.com/{to}", "open" to "")),
        app("teamsapp", "Microsoft Teams", "Messaging", "🟦", "com.microsoft.teams", mapOf("open" to "")),
        app("truecaller", "Truecaller", "Messaging", "📱", "com.truecaller", mapOf("lookup" to "https://www.truecaller.com/search/pk/{to}", "open" to "")),
        app("booking", "Booking.com", "Travel", "🏨", "com.booking", mapOf("search" to "https://www.booking.com/searchresults.html?ss={query}", "open" to "")),
        app("airbnb", "Airbnb", "Travel", "🏡", "com.airbnb.android", mapOf("search" to "https://www.airbnb.com/s/{query}/homes", "open" to "")),
        app("skyscanner", "Skyscanner", "Travel", "✈️", "net.skyscanner.android.main", mapOf("open" to "")),
        app("easypaisa", "Easypaisa", "Money", "🟩", "pk.com.telenor.phoenix", mapOf("open" to "")),
        app("jazzcash", "JazzCash", "Money", "🟥", "com.techlogix.mobilinkcustomer", mapOf("open" to "")),
        app("sadapay", "SadaPay", "Money", "🟦", "com.sadapay.app", mapOf("open" to "")),
        app("gphotos", "Google Photos", "Media", "🖼️", "com.google.android.apps.photos", mapOf("open" to "")),
        app("canvaapp", "Canva", "Media", "🎨", "com.canva.editor", mapOf("open" to "")),
        app("capcut", "CapCut", "Media", "✂️", "com.lemon.lvoverseas", mapOf("open" to "")),
        app("chatgpt", "ChatGPT", "AI", "🤖", "com.openai.chatgpt", mapOf("open" to "")),
        app("notionapp", "Notion", "Productivity", "📝", "notion.id", mapOf("open" to "")),
        app("keep", "Google Keep", "Productivity", "💡", "com.google.android.keep", mapOf("open" to "")),
    )

    // ───────────── built into the phone ─────────────
    private val device = listOf(
        dev("calendar", "Calendar (incl. Google Calendar)", "📅", "Reads and adds events in every calendar synced to this phone — Google, Outlook, Samsung — no sign-in needed."),
        dev("contacts", "Contacts", "👥", "Finds people and their numbers for calls, messages and birthdays."),
        dev("messages", "SMS & bank alerts", "💬", "Reads bank and wallet texts for the expense tracker; sends texts with your OK."),
        dev("calls", "Calls", "📞", "Places calls with your OK; call brief and call coach."),
        dev("mail", "Email (IMAP)", "📧", "Atlas's Mail app reads any inbox with an app password — Gmail, Outlook, Yahoo."),
        dev("files", "Files, Drive, OneDrive, Dropbox", "🗂️", "Any file from any storage app through Android's file picker."),
        dev("photos", "Photos & camera", "🖼️", "Photo search, face groups, time-lapse, scanning."),
        dev("notifications", "Notifications", "🔔", "Standing orders react to notifications from any app."),
        dev("screen", "Screen control", "🤖", "Operates any app on your phone for you — the fallback for apps with no API."),
        dev("location", "Location & maps", "📍", "Nearby places, directions, prayer times where you are."),
    )
}
