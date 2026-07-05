package com.amalskr.prodcheck.agent

import kotlinx.serialization.json.*
import java.io.File

/**
 * Parses the (findings-only) Layer 1 markdown report into the same finding
 * format FixMode consumes, with per-rule fix guidance baked in. This lets the
 * validated propose_edit pipeline auto-fix deterministic findings too.
 */
object Layer1Parser {

    private val guidance = mapOf(
        "DoubleBang" to "First judge INTENT from surrounding code and comments: if the !! is " +
                "deliberate (crash-test, demo, or test fixture code), do NOT change behavior — " +
                "propose adding @Suppress(\"DoubleBang\") with a brief comment instead. Otherwise " +
                "replace !! with a safe pattern: a local val enabling smart-cast, or ?: with a " +
                "sensible fallback. NOTE: requireNotNull throws IllegalArgumentException, NOT " +
                "NullPointerException — never claim they are equivalent, and never use it where " +
                "the exception type matters.",
        "SensitiveLog" to "Remove or redact the sensitive value from the log statement " +
            "(e.g. log the event without the token/password). Do not delete unrelated logging.",
        "MainThreadBlocking" to "ONLY if the call is clearly inside a suspend function or " +
            "coroutine builder: wrap it in withContext(Dispatchers.IO) { ... } and add the " +
            "kotlinx.coroutines imports. If the threading context is unclear, SKIP this finding.",
        "HardcodedSecret" to "SKIP this finding — moving secrets to BuildConfig or gradle " +
            "properties is a project-level decision. Mention it in your summary instead.",
    )

    fun parse(report: File, repoRoot: File): List<JsonElement> {
        if (!report.exists()) return emptyList()
        val findings = mutableListOf<JsonElement>()
        var rule = ""
        var loc: Pair<String, Int>? = null
        var inFence = false

        report.readLines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("### ") -> rule = line
                    .removePrefix("### ").substringAfter(", ").substringBefore(" (")

                line.startsWith("* ") && ":" in line -> {
                    val parts = line.removePrefix("* ").split(":")
                    val file = parts[0]
                    val ln = parts.getOrNull(1)?.toIntOrNull() ?: 1
                    loc = file to ln
                    inFence = false
                }

                line.startsWith("```") -> inFence = !inFence

                inFence && loc != null && line.isNotBlank() -> {
                    val (file, ln) = loc!!
                    val rel = runCatching {
                        File(file).canonicalFile.relativeTo(repoRoot.canonicalFile).path
                    }.getOrDefault(file)
                    findings += buildJsonObject {
                        put("rule", rule)
                        put("severity", "warning")
                        put("file", rel)
                        put("line", ln)
                        put("problem", line)
                        put("fix", guidance[rule] ?: "Apply a minimal, safe fix.")
                    }
                    loc = null   // message captured; ignore the code-snippet fence
                }
            }
        }
        return findings
    }
}
