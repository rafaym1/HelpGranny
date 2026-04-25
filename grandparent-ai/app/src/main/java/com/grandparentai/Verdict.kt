package com.grandparentai

/**
 * Scam Shield's structured assessment, surfaced to the UI as a big bold banner.
 * Parsed from a `VERDICT: SCAM | SAFE | UNSURE` line in the model's reply.
 */
enum class Verdict { SCAM, SAFE, UNSURE, NONE;

    companion object {
        private val REGEX = Regex("""VERDICT\s*:\s*(SCAM|SAFE|UNSURE)""", RegexOption.IGNORE_CASE)

        /** Extract a verdict from any of the model's replies during a Scam Shield run. */
        fun parse(text: String): Verdict {
            val m = REGEX.find(text) ?: return NONE
            return when (m.groupValues[1].uppercase()) {
                "SCAM" -> SCAM
                "SAFE" -> SAFE
                "UNSURE" -> UNSURE
                else -> NONE
            }
        }
    }
}
