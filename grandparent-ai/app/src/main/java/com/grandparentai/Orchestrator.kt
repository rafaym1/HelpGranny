package com.grandparentai

import com.grandparentai.agents.AgentPrompts
import org.json.JSONObject

/**
 * First brain in the chain. Listens to what the user said, decides which specialist agent
 * (or no agent at all) should handle it, and surfaces a friendly spoken response when no
 * agent is needed.
 *
 * Inspired by mobile-use's Orchestrator, but stripped down: we have three specialist agents
 * and a "DIRECT" fall-through, not a multi-step planner/cortex pipeline. Elderly users want
 * one short answer or one accomplished task — not a plan.
 */
object Orchestrator {

    enum class Route { SCAM_SHIELD, EMERGENCY, WHATSAPP, DIRECT }

    data class RouteResult(
        val route: Route,
        val intent: String,
        val spokenResponse: String,
    )

    private val SYSTEM_PROMPT = """
        You are the routing brain of "Grandparent AI", a voice assistant for elderly people.

        Classify the user's intent and route to the right specialist. Respond with ONE LINE
        of strict JSON, no markdown fences, with these exact keys:

        {"route":"SCAM_SHIELD"|"EMERGENCY"|"WHATSAPP"|"DIRECT","intent":"<one short sentence in user's own words>","spoken_response":"<only if route is DIRECT, what to say back; otherwise empty string>"}

        Routing rules:
        - Anything about a suspicious call, urgent money request, prize, OTP, bank, government
          official, or "they said I owe…" → SCAM_SHIELD
        - Fell, hurt, chest pain, can't breathe, dizzy, scared, "help me", emergency → EMERGENCY
        - Send a message, WhatsApp, call my son/daughter/contact, voice note → WHATSAPP
        - Anything else (questions, chitchat, time, weather guesses) → DIRECT, and put a warm,
          plain-language answer in spoken_response.

        Tone: warm, slow, simple. Never use jargon. Understand vague references ("him", "my son",
        "the blue button"). It is OK if the user repeats themselves.
    """.trimIndent()

    suspend fun route(userText: String): RouteResult {
        val raw = ClaudeApiClient.sendTextOnly(SYSTEM_PROMPT, userText)
        return parse(raw, fallbackIntent = userText)
    }

    /** Returns the system prompt for the chosen specialist. */
    fun promptFor(route: Route): String = when (route) {
        Route.SCAM_SHIELD -> AgentPrompts.SCAM_SHIELD
        Route.EMERGENCY -> AgentPrompts.EMERGENCY
        Route.WHATSAPP -> AgentPrompts.WHATSAPP
        Route.DIRECT -> "" // DIRECT never enters the agent loop
    }

    // -------------------------------------------------------------------- parsing

    /**
     * Robust JSON extraction — Claude almost always returns clean JSON given the strict
     * instruction, but we tolerate stray prose by hunting for the first {…} block.
     */
    private fun parse(raw: String, fallbackIntent: String): RouteResult {
        val jsonStr = extractJsonObject(raw) ?: return RouteResult(
            route = Route.DIRECT,
            intent = fallbackIntent,
            spokenResponse = raw.trim().ifBlank {
                "I'm sorry, I didn't catch that. Could you say it again?"
            },
        )
        val obj = try { JSONObject(jsonStr) } catch (_: Throwable) { null }
            ?: return RouteResult(Route.DIRECT, fallbackIntent, "I didn't quite catch that.")

        val routeStr = obj.optString("route", "DIRECT").trim().uppercase()
        val route = runCatching { Route.valueOf(routeStr) }.getOrDefault(Route.DIRECT)
        val intent = obj.optString("intent", fallbackIntent).ifBlank { fallbackIntent }
        val spoken = obj.optString("spoken_response", "")
        return RouteResult(route, intent, spoken)
    }

    private fun extractJsonObject(s: String): String? {
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return s.substring(start, end + 1)
    }
}
