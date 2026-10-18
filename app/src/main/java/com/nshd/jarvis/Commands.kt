package com.nshd.jarvis

/** Pure Kotlin (no Android types) so it can be unit-tested on the JVM. */
data class ActionRequest(val action: String, val target: String = "", val text: String = "")

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

        Regex("^(?:search|find|look up)\\s+(?:on\\s+)?(?:youtube|you tube)\\s+(?:for\\s+)?(.+)$", opts).find(s)?.let { return Local.Do(ActionRequest("search_youtube", text = it.groupValues[1].trim())) }
        Regex("^(?:search|find|play)\\s+(.+?)\\s+(?:on|in)\\s+(?:youtube|you tube)$", opts).find(s)?.let { return Local.Do(ActionRequest("search_youtube", text = it.groupValues[1].trim())) }
        Regex("^open (?:youtube|you tube) and (?:play|search(?: for)?)\\s+(.+)$", opts).find(s)?.let { return Local.Do(ActionRequest("search_youtube", text = it.groupValues[1].trim())) }

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
