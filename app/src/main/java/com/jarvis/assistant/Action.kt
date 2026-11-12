package com.jarvis.assistant

/**
 * Fixed whitelist of executable operations.
 *
 * Only the types declared here may be executed by [ActionExecutor]. The
 * LLM (Step 5+) returns text only and cannot add, modify, or name an
 * action outside this sealed hierarchy.
 */
sealed class Action {
    data class LaunchApp(val app: String) : Action()
    data class SearchInApp(val app: String, val query: String) : Action()
    data class SearchWeb(val query: String) : Action()
    data class OpenSettings(val page: String) : Action()
    data class Global(val type: GlobalType) : Action()
    data class Scroll(val forward: Boolean) : Action()
    data class ClickText(val text: String) : Action()
    data class EnterText(val text: String) : Action()
    data class Ask(val question: String) : Action()
    data object Cancel : Action()
}

enum class GlobalType { BACK, HOME, RECENTS }
