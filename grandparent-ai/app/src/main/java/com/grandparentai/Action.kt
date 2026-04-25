package com.grandparentai

/**
 * The set of atomic actions the model can choose at each step. Kept deliberately small — the
 * spec lists: TAP / TYPE / SCROLL_DOWN / SCROLL_UP / BACK / HOME / DONE. We add WAIT and
 * RECENTS for graceful handling of edge cases.
 */
sealed class Action {
    data class Tap(val x: Float, val y: Float) : Action()
    data class Type(val text: String) : Action()
    data object ScrollDown : Action()
    data object ScrollUp : Action()
    data object Back : Action()
    data object Home : Action()
    data object Recents : Action()
    data object Wait : Action()
    data class Done(val message: String) : Action()
    data class Unknown(val raw: String) : Action()

    fun summary(): String = when (this) {
        is Tap -> "TAP ${x.toInt()},${y.toInt()}"
        is Type -> "TYPE \"${text.take(40)}\""
        ScrollDown -> "SCROLL_DOWN"
        ScrollUp -> "SCROLL_UP"
        Back -> "BACK"
        Home -> "HOME"
        Recents -> "RECENTS"
        Wait -> "WAIT"
        is Done -> "DONE: ${message.take(80)}"
        is Unknown -> "UNKNOWN"
    }
}

/**
 * Parsed model reply: the action to perform plus the friendly one-line narration we read aloud
 * to the user before executing it (e.g., "Opening WhatsApp now").
 */
data class ParsedTurn(val narration: String, val action: Action, val raw: String)
