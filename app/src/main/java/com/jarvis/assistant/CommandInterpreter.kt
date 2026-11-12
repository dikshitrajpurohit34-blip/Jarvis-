package com.jarvis.assistant

/**
 * Pure-Kotlin text interpreter for JARVIS commands.
 *
 * This object:
 *   - does not touch Android APIs, SharedPreferences, the network, or Groq;
 *   - does not perform any side effect;
 *   - returns an [Action] from the fixed whitelist, or [Action.Ask] for
 *     anything that looks like a question or an unrecognised phrase.
 *
 * Pattern priority (most-specific first):
 *   1. "open X and search for Y"
 *   2. "<page> settings" / "open <page> settings"
 *   3. "search / google [for] Q"
 *   4. "open / launch / start APP"
 *   5. global navigation (back / home / recents)
 *   6. scroll
 *   7. click
 *   8. enter / type
 *   9. cancel
 *  10. otherwise -> Action.Ask(originalText)
 */
object CommandInterpreter {
    private val openAndSearch = Regex(
        """^(?:open|launch|start)\s+(.+?)\s+and\s+search(?:\s+for)?\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val settingsPage = Regex(
        """^(?:open\s+)?(wi-?fi|bluetooth|display|sound|battery|location)\s+settings$""",
        RegexOption.IGNORE_CASE
    )
    private val searchWeb = Regex(
        """^(?:search|google)(?:\s+for)?\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val openApp = Regex(
        """^(?:open|launch|start)\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val click = Regex(
        """^click(?:\s+on)?(?:\s+the)?\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val enterText = Regex(
        """^(?:enter|type)\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val backCommands = setOf("go back", "back", "navigate back")
    private val homeCommands = setOf("go home", "home", "go to home")
    private val recentsCommands =
        setOf("recents", "show recents", "show recent apps", "recent apps")
    private val cancelCommands =
        setOf("cancel", "never mind", "nevermind", "stop", "abort", "forget it")

    fun interpret(raw: String): Action {
        val original = raw.trim()
        val normalized = original.trimEnd('.', '?', '!', '…', ',', ';').trim()
        if (normalized.isEmpty()) return Action.Ask(original)

        openAndSearch.matchEntire(normalized)?.let { m ->
            val app = m.groupValues[1].trim()
            val query = m.groupValues[2].trim()
            if (app.isNotEmpty() && query.isNotEmpty()) {
                return Action.SearchInApp(app, query)
            }
        }
        settingsPage.matchEntire(normalized)?.let { m ->
            val page = m.groupValues[1].lowercase().replace("-", "")
            if (page.isNotEmpty()) return Action.OpenSettings(page)
        }
        searchWeb.matchEntire(normalized)?.let { m ->
            val query = m.groupValues[1].trim()
            if (query.isNotEmpty()) return Action.SearchWeb(query)
        }
        openApp.matchEntire(normalized)?.let { m ->
            val app = m.groupValues[1].trim()
            if (app.isNotEmpty()) return Action.LaunchApp(app)
        }
        val lower = normalized.lowercase()
        when (lower) {
            in backCommands -> return Action.Global(GlobalType.BACK)
            in homeCommands -> return Action.Global(GlobalType.HOME)
            in recentsCommands -> return Action.Global(GlobalType.RECENTS)
        }
        when (lower) {
            "scroll", "scroll down", "scroll forward" -> return Action.Scroll(forward = true)
            "scroll up", "scroll backward", "scroll back" -> return Action.Scroll(forward = false)
        }
        click.matchEntire(normalized)?.let { m ->
            val text = m.groupValues[1].trim()
            if (text.isNotEmpty()) return Action.ClickText(text)
        }
        enterText.matchEntire(normalized)?.let { m ->
            val text = m.groupValues[1].trim()
            if (text.isNotEmpty()) return Action.EnterText(text)
        }
        if (lower in cancelCommands) return Action.Cancel
        return Action.Ask(original)
    }
}
