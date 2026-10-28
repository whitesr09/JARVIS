package com.nshd.jarvis

/** Pure Kotlin (no Android types) so it can be unit-tested on the JVM. */
data class ActionRequest(val action: String, val target: String = "", val text: String = "", val extra: Map<String, String> = emptyMap())

sealed class Local {
    data class Do(val req: ActionRequest) : Local()
    data class Remember(val fact: String) : Local()
    object Recall : Local()
}

object Commands {
    private val settingsKeys = mapOf(
        "wifi" to "wifi", "wi-fi" to "wifi", "bluetooth" to "bluetooth", "display" to "display", "sound" to "sound",
        "accessibility" to "accessibility", "battery" to "battery", "location" to "location", "app" to "apps", "apps" to "apps")

    /** Words that make a tap potentially public, destructive or financial. */
    val RISKY = Regex("(?i)\\b(send|post|publish|share|delete|remove|uninstall|buy|purchase|pay|order|subscribe|confirm|submit|transfer|call|sign out|log out|logout|unfollow|block|reply|comment|upload|install|allow|grant)\\b")

    private val SENSITIVE = Regex("(?i)(password|passcode|\\botp\\b|one[- ]time (code|password)|\\bcvv\\b|card number|credit card|debit card|\\bpin\\b|api[ _-]?key|secret key|private key|\\btoken\\b|seed phrase)|\\b\\d{12,19}\\b")

    private val MEDIA_APPS = "spotify|youtube music|yt music|ytmusic|youtube|you tube|jio ?saavn|saavn|gaana|wynk|amazon music|apple music"
    private val NO_QUERY = setOf("something", "anything", "music", "some music", "songs", "some songs", "a song", "a random song", "random")

    /** Canonical id for well-known media apps; anything else is passed through as typed. */
    fun mediaApp(raw: String): String {
        val s = raw.trim().lowercase().replace(Regex("\\s+"), " ")
        return when (s) {
            "spotify" -> "spotify"
            "youtube", "you tube" -> "youtube"
            "youtube music", "yt music", "ytmusic" -> "ytmusic"
            else -> raw.trim()
        }
    }
    private fun query(q: String): String = q.trim().let { if (it.lowercase() in NO_QUERY) "" else it }

    private fun timerSeconds(n: Int, unit: String): Int = when {
        unit.startsWith("h") -> n * 3600
        unit.startsWith("m") -> n * 60
        else -> n
    }

    fun sensitive(text: String) = SENSITIVE.containsMatchIn(text)

    fun categorize(fact: String): String {
        val f = fact.lowercase()
        return when {
            Regex("\\b(prefer|like|love|favou?rite|dislike|hate|enjoy)\\b").containsMatchIn(f) -> "Preferences"
            Regex("\\b(mom|mother|dad|father|friend|wife|husband|brother|sister|colleague|boss|phone number|contact)\\b").containsMatchIn(f) -> "Contacts"
            Regex("\\b(every|daily|morning|evening|night|weekly|routine|usually|always)\\b").containsMatchIn(f) -> "Routines"
            Regex("\\b(my name|birthday|born|live in|live at|work at|work as|i am|i'm|my age)\\b").containsMatchIn(f) -> "Personal"
            else -> "Other"
        }
    }

    private fun clean(raw: String): String {
        var s = raw.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ',')
        val lead = Regex("(?i)^(?:hey |hi |ok |okay )?(?:zara|jarvis)[, ]+|^(?:please |can you |could you |would you |will you )+")
        var prev: String
        do { prev = s; s = s.replace(lead, "").trim() } while (s != prev)
        return s.trimEnd('.', '!', '?', ',')
    }

    fun parse(raw: String, a11yOn: Boolean = false): Local? {
        val s = clean(raw)
        if (s.isEmpty()) return null
        val opts = RegexOption.IGNORE_CASE

        Regex("^remember(?: that)?\\s+(.+)$", opts).find(s)?.let { return Local.Remember(it.groupValues[1].trim()) }
        if (Regex("^what (?:do|did) you (?:remember|know about me)$", opts).matches(s)) return Local.Recall

        Regex("^(?:open|launch|show|take me to|go to)\\s+(?:the\\s+)?(wi-?fi|bluetooth|display|sound|accessibility|battery|location|apps?)\\s+settings$", opts).find(s)?.let {
            return Local.Do(ActionRequest("open_settings", settingsKeys[it.groupValues[1].lowercase()] ?: "general"))
        }
        if (Regex("^(?:open|launch)\\s+settings$", opts).matches(s)) return Local.Do(ActionRequest("open_settings", "general"))

        // --- media: always carry the app the user named in THIS sentence ---
        Regex("^open\\s+(?:the\\s+)?(.+?)\\s+(?:app\\s+)?and\\s+(?:play|start|put on)\\s+(.+)$", opts).find(s)?.let {
            return Local.Do(ActionRequest("play_media", mediaApp(it.groupValues[1]), query(it.groupValues[2])))
        }
        Regex("^(?:play|start|put on)\\s+(.+?)\\s+(?:on|in|using|with|from)\\s+($MEDIA_APPS)$", opts).find(s)?.let {
            return Local.Do(ActionRequest("play_media", mediaApp(it.groupValues[2]), query(it.groupValues[1])))
        }
        Regex("^(?:open\\s+)?($MEDIA_APPS)\\s+(?:and\\s+)?(?:play|search for)\\s+(.+)$", opts).find(s)?.let {
            return Local.Do(ActionRequest("play_media", mediaApp(it.groupValues[1]), query(it.groupValues[2])))
        }

        // --- timers, alarms, web, maps, phone ---
        Regex("^(?:set|start)\\s+(?:a\\s+)?timer\\s+(?:for\\s+)?(\\d{1,4})\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)$", opts).find(s)?.let {
            val secs = timerSeconds(it.groupValues[1].toInt(), it.groupValues[2].lowercase())
            if (secs in 1..86400) return Local.Do(ActionRequest("set_timer", text = secs.toString()))
        }
        Regex("^(?:set|create)\\s+(?:an?\\s+)?alarm\\s+(?:for|at)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?$", opts).find(s)?.let {
            var h = it.groupValues[1].toInt(); val m = it.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = it.groupValues[3].lowercase().replace(".", "")
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (h in 0..23 && m in 0..59) return Local.Do(ActionRequest("set_alarm", text = "%02d:%02d".format(h, m)))
        }
        Regex("^(?:search|google|look up|search the web for|search google for)\\s+(?:for\\s+)?(.+)$", opts).find(s)?.let {
            val q = it.groupValues[1].trim()
            if (!q.contains("youtube", true) && q.split(" ").size <= 12) return Local.Do(ActionRequest("web_search", text = q))
        }
        Regex("^(?:navigate to|directions to|take me to|show me the way to|find)\\s+(.+)$", opts).find(s)?.let {
            val q = it.groupValues[1].trim()
            if (!q.contains("settings", true) && !q.contains("youtube", true) && q.split(" ").size in 1..8 && Regex("^navigate|^directions|^take me|^show me").containsMatchIn(s.lowercase()))
                return Local.Do(ActionRequest("navigate_to", text = q))
        }
        Regex("^(?:call|dial)\\s+(\\+?[0-9][0-9 \\-]{5,17}[0-9])$", opts).find(s)?.let {
            return Local.Do(ActionRequest("dial_number", text = it.groupValues[1].filter { c -> c.isDigit() || c == '+' }))
        }
        Regex("^(?:text|sms|message)\\s+(\\+?[0-9][0-9 \\-]{5,17}[0-9])\\s+(?:saying|that says|:)\\s*(.+)$", opts).find(s)?.let {
            return Local.Do(ActionRequest("sms_draft", it.groupValues[1].filter { c -> c.isDigit() || c == '+' }, it.groupValues[2].trim()))
        }

        Regex("^(?:search|find|look up)\\s+(?:on\\s+)?(?:youtube|you tube)\\s+(?:for\\s+)?(.+)$", opts).find(s)?.let { return Local.Do(ActionRequest("search_youtube", text = it.groupValues[1].trim())) }
        Regex("^(?:search|find)\\s+(.+?)\\s+(?:on|in)\\s+(?:youtube|you tube)$", opts).find(s)?.let { return Local.Do(ActionRequest("search_youtube", text = it.groupValues[1].trim())) }

        Regex("^(?:send|write)\\s+(?:a\\s+)?whatsapp(?:\\s+message)?(?:\\s+to\\s+(.+?))?\\s*(?:saying|that says|:)\\s*(.+)$", opts).find(s)?.let {
            return Local.Do(ActionRequest("whatsapp_draft", target = it.groupValues[1].trim(), text = it.groupValues[2].trim()))
        }

        if (Regex("^(?:go )?back$", opts).matches(s)) return Local.Do(ActionRequest("go_back"))
        if (Regex("^(?:go )?home$|^go to (?:the )?home ?screen$", opts).matches(s)) return Local.Do(ActionRequest("go_home"))
        if (Regex("^(?:show |open )?(?:recent apps|recents)$", opts).matches(s)) return Local.Do(ActionRequest("recent_apps"))
        if (Regex("^scroll down$", opts).matches(s)) return Local.Do(ActionRequest("scroll_down"))
        if (Regex("^scroll up$", opts).matches(s)) return Local.Do(ActionRequest("scroll_up"))

        Regex("^(?:tap|click|press|touch)(?: on)?\\s+(?:the\\s+)?(.+?)(?:\\s+button)?$", opts).find(s)?.let {
            val t = it.groupValues[1].trim()
            if (t.isNotEmpty() && t.split(" ").size <= 5) return Local.Do(ActionRequest("tap_text", target = t))
        }
        if (a11yOn) Regex("^type\\s+(.+)$", opts).find(s)?.let { return Local.Do(ActionRequest("type_text", text = it.groupValues[1].trim())) }

        Regex("^(?:open|launch|start)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$", opts).find(s)?.let {
            val name = it.groupValues[1].trim()
            val words = name.split(" ").size
            if (name.isNotEmpty() && words <= 4 && !name.contains(" and ", true) && !name.contains(" then ", true))
                return Local.Do(ActionRequest("open_app", target = name))
        }
        return null
    }
}

/** Matches the spoken wake phrase in recognizer text. Sensitivity: 0 strict, 1 balanced, 2 loose. */
object Wake {
    private val greetings = setOf("hey", "hay", "hi", "hello", "ok", "okay", "hei", "yo")
    private val strict = setOf("zara", "zaara", "zarah", "zaraa", "zahra")
    private val balanced = strict + setOf("sara", "zera", "zora", "zarra", "zaira")
    private val loose = balanced + setOf("sarah", "zarra", "jara", "zahara")

    fun normalize(s: String): List<String> = s.lowercase().replace(Regex("[^a-z\\u0900-\\u097f\\u0d00-\\u0d7f ]"), " ").split(" ").filter { it.isNotBlank() }

    fun matches(raw: String, sensitivity: Int = 1, phrase: String = "hey zara"): Boolean {
        val tokens = normalize(raw)
        if (tokens.isEmpty()) return false
        val p = normalize(phrase)
        if (p.joinToString(" ") != "hey zara") {      // custom phrase: plain contains
            return tokens.joinToString(" ").contains(p.joinToString(" "))
        }
        val names = when (sensitivity) { 0 -> strict; 1 -> balanced; else -> loose }
        for ((i, t) in tokens.withIndex()) {
            if (t in names && (sensitivity >= 2 || (i > 0 && tokens[i - 1] in greetings))) return true
            for (g in greetings) if (t.startsWith(g) && t.removePrefix(g) in names) return true   // "heyzara"
        }
        return false
    }
}

data class HItem(val role: String, val text: String, val status: String = "", val meta: String = "")

/**
 * Builds the conversation sent to the AI. Every earlier user request must be followed by an assistant turn
 * saying what happened, otherwise the model sees unanswered requests and tries to perform them again
 * (the "after YouTube, everything opens YouTube" bug). Pure Kotlin so it can be unit-tested.
 */
object HistoryBuilder {
    const val HANDLED = "(That request was already handled — do not repeat it.)"

    fun build(items: List<HItem>, limit: Int): List<Pair<String, String>> {
        val turns = ArrayList<Pair<String, String>>()
        fun push(role: String, text: String) {
            if (text.isBlank()) return
            if (turns.isNotEmpty() && turns.last().first == role) {
                if (role == "user") turns.add("assistant" to HANDLED)   // a user turn with no answer in between
                else { turns[turns.size - 1] = role to (turns.last().second + "\n" + text); return }
            }
            turns.add(role to text)
        }
        for (it in items) when (it.role) {
            "user" -> push("user", it.text)
            "assistant" -> push("assistant", it.text)
            "task" -> push("assistant", "[Device action already performed] ${it.meta}: ${it.status.lowercase().replace('_', ' ')}. ${it.text}".trim())
        }
        var out: List<Pair<String, String>> = turns
        if (out.size > limit) out = out.takeLast(limit)
        while (out.isNotEmpty() && out.first().first != "user") out = out.drop(1)
        return out
    }
}

/** Forgiving tool-argument handling: models often say "app"/"query"/"launch" instead of our exact names. Pure Kotlin. */
object ToolArgs {
    private val ACTION_ALIASES = mapOf(
        "open" to "open_app", "launch" to "open_app", "launch_app" to "open_app", "start_app" to "open_app", "open_application" to "open_app",
        "play" to "play_media", "play_music" to "play_media", "play_song" to "play_media", "play_video" to "play_media",
        "search" to "web_search", "google" to "web_search", "search_web" to "web_search", "web" to "web_search",
        "click" to "tap_text", "tap" to "tap_text", "press_button" to "tap_text", "click_text" to "tap_text",
        "type" to "type_text", "enter_text" to "type_text", "input_text" to "type_text", "write_text" to "type_text",
        "read_screen" to "get_screen", "screen" to "get_screen", "observe" to "get_screen", "look" to "get_screen", "screenshot" to "get_screen",
        "back" to "go_back", "home" to "go_home", "recents" to "recent_apps",
        "call" to "dial_number", "dial" to "dial_number", "navigate" to "navigate_to", "directions" to "navigate_to",
        "timer" to "set_timer", "alarm" to "set_alarm", "sms" to "sms_draft", "text_message" to "sms_draft",
        "whatsapp" to "whatsapp_draft", "settings" to "open_settings")
    private val TARGET_KEYS = listOf("target", "app", "app_name", "application", "name", "label", "element", "button", "platform", "service")
    private val TEXT_KEYS = listOf("text", "query", "message", "content", "song", "title", "value", "keyword", "input", "phrase", "artist", "video", "search_query", "goal")
    private val EXTRA_KEYS = listOf("id", "x", "y", "x2", "y2", "duration_ms", "key", "direction")

    private fun secondsOf(s: String): Int? {
        s.trim().toIntOrNull()?.let { return it }
        val m = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b").find(s) ?: return null
        val n = m.groupValues[1].toDouble(); val u = m.groupValues[2].lowercase()
        return (n * if (u.startsWith("h")) 3600 else if (u.startsWith("m")) 60 else 1).toInt()
    }

    private fun clockOf(s: String): String? {
        val m = Regex("(?i)(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(s.trim()) ?: return null
        var h = m.groupValues[1].toInt(); val mi = m.groupValues[2].ifEmpty { "0" }.toInt(); val ap = m.groupValues[3].lowercase()
        if (ap == "pm" && h < 12) h += 12; if (ap == "am" && h == 12) h = 0
        return if (h in 0..23 && mi in 0..59) "%02d:%02d".format(h, mi) else null
    }

    fun normalize(rawAction: String, args: Map<String, String>, allowed: Set<String>): ActionRequest? {
        val key = rawAction.trim().lowercase().replace(' ', '_').replace('-', '_')
        var action = if (key in allowed) key else ACTION_ALIASES[key]?.takeIf { it in allowed } ?: (if (key == "scroll") "scroll_down" else null) ?: return null
        val get = { k: String -> args[k]?.takeIf { it.isNotBlank() && it != "null" } }
        var target = TARGET_KEYS.firstNotNullOfOrNull { get(it) } ?: ""
        var text = TEXT_KEYS.firstNotNullOfOrNull { get(it) } ?: ""
        val extra = HashMap<String, String>()
        for (k in EXTRA_KEYS) get(k)?.let { extra[k] = if (k != "key" && k != "direction") it.removeSuffix(".0") else it }
        if (key == "scroll" && (extra["direction"] ?: target).lowercase().startsWith("up")) action = "scroll_up"
        if (action == "scroll_down" || action == "scroll_up") if ((extra["direction"] ?: "").lowercase().startsWith("up")) action = "scroll_up"
        if (action == "press_key" && !extra.containsKey("key") && target.isNotEmpty()) extra["key"] = target
        if (key in setOf("back", "home", "recents")) { /* handled by alias → go_back / go_home / recent_apps */ }
        if (action == "set_timer") text = secondsOf(text.ifEmpty { target })?.toString() ?: text
        if (action == "set_alarm") text = clockOf(text.ifEmpty { target }) ?: text
        if (action == "dial_number" || action == "sms_draft") { if (action == "dial_number" && text.isEmpty()) text = target }
        if (action == "web_search" || action == "navigate_to" || action == "type_text") { if (text.isEmpty()) text = target }
        return ActionRequest(action, target.take(120), text.take(600), extra)
    }
}
