package com.grandparentai

/**
 * Parses the model's free-text reply into a [ParsedTurn] (narration + [Action]).
 *
 * The protocol the prompts teach Claude is:
 *   SAY: <one short friendly sentence to speak before acting>
 *   <DIRECTIVE>          // one of the action keywords below
 *
 * Directives:
 *   TAP: x y
 *   TYPE: <text...>
 *   SCROLL_DOWN | SCROLL_UP
 *   BACK | HOME | RECENTS
 *   WAIT
 *   DONE: <message to speak to user>     // narration is optional in this case — DONE itself is the spoken line
 *
 * We're lenient with the model: if it forgets SAY:, we synthesise a fallback narration from the
 * action so the user still hears progress.
 */
object ActionParser {

    private val SAY_REGEX = Regex("""SAY\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
    private val TAP_REGEX = Regex(
        """TAP\s*[:\(]?\s*(\d+(?:\.\d+)?)\s*[, ]\s*(\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )
    private val TYPE_REGEX = Regex("""TYPE\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
    private val DONE_REGEX = Regex(
        """DONE\s*:\s*(.+)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun parse(raw: String): ParsedTurn {
        val narration = extractNarration(raw)
        val action = extractAction(raw)
        return ParsedTurn(
            narration = narration ?: defaultNarrationFor(action),
            action = action,
            raw = raw,
        )
    }

    private fun extractNarration(raw: String): String? {
        // Prefer an explicit SAY: line.
        SAY_REGEX.find(raw)?.let { m ->
            return cleanLine(m.groupValues[1].lineSequence().first())
        }
        // Otherwise take the first non-blank line that doesn't start with a directive keyword.
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (looksLikeDirective(trimmed)) continue
            return cleanLine(trimmed)
        }
        return null
    }

    private fun extractAction(raw: String): Action {
        // DONE wins if present — model may narrate first, then declare completion.
        DONE_REGEX.find(raw)?.let { m ->
            return Action.Done(cleanLine(m.groupValues[1]))
        }
        TYPE_REGEX.find(raw)?.let { m ->
            val firstLine = m.groupValues[1].lineSequence().first()
            return Action.Type(stripQuotes(firstLine))
        }
        TAP_REGEX.find(raw)?.let { m ->
            val x = m.groupValues[1].toFloat()
            val y = m.groupValues[2].toFloat()
            return Action.Tap(x, y)
        }
        return when {
            raw.containsToken("SCROLL_DOWN") || raw.containsToken("SCROLL DOWN") -> Action.ScrollDown
            raw.containsToken("SCROLL_UP") || raw.containsToken("SCROLL UP") -> Action.ScrollUp
            raw.containsToken("BACK") -> Action.Back
            raw.containsToken("HOME") -> Action.Home
            raw.containsToken("RECENTS") -> Action.Recents
            raw.containsToken("WAIT") -> Action.Wait
            else -> Action.Unknown(raw)
        }
    }

    /** Friendly fallback when the model forgot the SAY: line. */
    private fun defaultNarrationFor(action: Action): String = when (action) {
        is Action.Tap -> "Tapping."
        is Action.Type -> "Typing your message."
        Action.ScrollDown -> "Scrolling down."
        Action.ScrollUp -> "Scrolling up."
        Action.Back -> "Going back."
        Action.Home -> "Going to home screen."
        Action.Recents -> "Opening recent apps."
        Action.Wait -> "Waiting for the screen."
        is Action.Done -> action.message
        is Action.Unknown -> "Thinking."
    }

    private fun looksLikeDirective(line: String): Boolean {
        val upper = line.uppercase()
        return upper.startsWith("TAP") ||
            upper.startsWith("TYPE:") ||
            upper.startsWith("SCROLL_DOWN") || upper.startsWith("SCROLL_UP") ||
            upper.startsWith("SCROLL DOWN") || upper.startsWith("SCROLL UP") ||
            upper.startsWith("BACK") || upper.startsWith("HOME") || upper.startsWith("RECENTS") ||
            upper.startsWith("WAIT") || upper.startsWith("DONE:") ||
            upper.startsWith("SAY:")
    }

    /** True if [token] appears as a standalone word (not inside another word). */
    private fun String.containsToken(token: String): Boolean {
        val pattern = Regex("\\b${Regex.escape(token)}\\b", RegexOption.IGNORE_CASE)
        return pattern.containsMatchIn(this)
    }

    private fun cleanLine(s: String): String =
        s.trim().trim('"', '\'').replace(Regex("\\s+"), " ")

    private fun stripQuotes(s: String): String {
        val t = s.trim()
        if (t.length >= 2) {
            val first = t.first(); val last = t.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return t.substring(1, t.length - 1)
            }
        }
        return t
    }
}
