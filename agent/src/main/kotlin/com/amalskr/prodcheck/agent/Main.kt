package com.amalskr.prodcheck.agent

import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.exitProcess

/**
 * LAYER 2 — Agentic semantic review.
 *
 * Deterministic mistakes (#1, #3, #7, #8) are handled by the Detekt rules.
 * This agent reasons about the semantic ones:
 *   #2  duplicate API call triggers (recomposition, repeated clicks, observers)
 *   #4  business logic living in Activities/Fragments/Composables
 *   #5  missing API error / loading / empty state handling
 *   #6  token expiration & refresh handling
 *   #9  configuration change / process death resilience
 *   #10 crash monitoring integration on new critical paths
 *
 * Usage:  ANTHROPIC_API_KEY=... ./gradlew productionCheck
 */

const val MODEL = "claude-sonnet-4-6"
const val MAX_AGENT_TURNS = 12
const val MAX_FILE_CHARS = 24_000

val SYSTEM_PROMPT = """
You are ProdCheck, a pre-production reviewer for Android (Kotlin/Jetpack Compose) code.
You receive a git diff of a feature/bugfix that is about to ship, plus a Layer-1 static
analysis report. Deterministic issues (!!, blocking calls, secrets, sensitive logs) are
already covered by Layer 1 — do NOT re-report them unless the diff makes them worse.

Your job is to check ONLY the changed code (and files it directly touches) against:

R2  DUPLICATE API CALLS — Can recomposition, repeated clicks, or multiple observers
    trigger the same network call twice? Look for API calls in composables without
    LaunchedEffect keys, missing debounce on click handlers, init{} calls in ViewModels
    recreated per navigation, Flow collected in multiple places without shareIn.
R4  LOGIC PLACEMENT — Is business logic (mapping, validation, decisions) written inside
    an Activity/Fragment/Composable instead of a ViewModel/UseCase?
R5  ERROR & EMPTY STATES — Does every new API call handle failure, loading, and
    empty-response paths? Look for Result/try-catch/sealed UI state coverage.
R6  TOKEN EXPIRATION — If the change touches networking/auth, is 401 handled with a
    refresh (Authenticator/interceptor) without infinite retry loops?
R9  CONFIG CHANGE / PROCESS DEATH — Is UI state that must survive rotation or process
    death held in remember{} or local vars instead of rememberSaveable/SavedStateHandle?
R10 CRASH MONITORING — Do new critical paths swallow exceptions silently instead of
    reporting (Crashlytics/recordException) where appropriate?

You have tools to read files in the repository. Read a file when the diff alone is not
enough to judge (e.g. you see a call into a ViewModel — read the ViewModel).
Be economical: read only what you need.

When done, output your final answer as JSON ONLY (no markdown fences):
{
  "verdict": "PASS" | "WARN" | "BLOCK",
  "findings": [
    {
      "rule": "R2".."R10",
      "severity": "info" | "warning" | "blocker",
      "file": "path",
      "line": 0,
      "problem": "one sentence",
      "fix": "concrete suggested fix, with a short Kotlin snippet if useful"
    }
  ],
  "summary": "2-3 sentence overall assessment"
}
Verdict rules: any blocker => BLOCK, any warning => WARN, else PASS.
Do not invent findings. If the diff is clean for a rule, stay silent about it.
""".trimIndent()

class AnthropicClient(private val apiKey: String) {
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    fun call(system: String, messages: JsonArray, tools: JsonArray): JsonObject {
        val body = buildJsonObject {
            put("model", MODEL)
            put("max_tokens", 4000)
            put("system", system)
            put("messages", messages)
            put("tools", tools)
        }
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.anthropic.com/v1/messages"))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) {
            error("Anthropic API error ${res.statusCode()}: ${res.body().take(500)}")
        }
        return json.parseToJsonElement(res.body()).jsonObject
    }
}

object RepoTools {
    lateinit var repoRoot: File

    val definitions: JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("name", "read_file")
            put("description", "Read a source file from the repository (truncated to $MAX_FILE_CHARS chars).")
            put("input_schema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Path relative to repo root")
                    })
                })
                put("required", buildJsonArray { add("path") })
            })
        })
        add(buildJsonObject {
            put("name", "find_files")
            put("description", "Find files whose path contains the given substring (case-insensitive). Returns up to 30 paths.")
            put("input_schema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject { put("type", "string") })
                })
                put("required", buildJsonArray { add("query") })
            })
        })
    }

    fun execute(name: String, input: JsonObject): String = when (name) {
        "read_file" -> {
            val rel = input["path"]!!.jsonPrimitive.content
            val f = repoRoot.resolve(rel).canonicalFile
            when {
                !f.path.startsWith(repoRoot.canonicalPath) -> "ERROR: path escapes repo root"
                !f.exists() -> "ERROR: file not found: $rel"
                else -> f.readText().take(MAX_FILE_CHARS)
            }
        }
        "find_files" -> {
            val q = input["query"]!!.jsonPrimitive.content.lowercase()
            repoRoot.walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "kts", "xml", "gradle") }
                .filter { ".git" !in it.path && "/build/" !in it.path }
                .filter { q in it.relativeTo(repoRoot).path.lowercase() }
                .take(30)
                .joinToString("\n") { it.relativeTo(repoRoot).path }
                .ifEmpty { "No matches." }
        }
        else -> "ERROR: unknown tool $name"
    }
}

fun gitDiff(repoRoot: File): String {
    // Prefer staged + unstaged changes vs HEAD; fall back to last commit.
    fun run(vararg cmd: String): String =
        ProcessBuilder(*cmd).directory(repoRoot).redirectErrorStream(true)
            .start().inputStream.bufferedReader().readText()

    val diff = run("git", "diff", "HEAD", "--unified=8")
    return if (diff.isBlank()) run("git", "diff", "HEAD~1", "HEAD", "--unified=8") else diff
}

fun main(args: Array<String>) {
    val argMap = args.toList().zipWithNext().toMap()
    val repoRoot = File(argMap["--repo-root"] ?: ".").canonicalFile
    val layer1Path = argMap["--layer1-report"]
    RepoTools.repoRoot = repoRoot

    val apiKey = System.getenv("ANTHROPIC_API_KEY") ?: run {
        System.err.println("ANTHROPIC_API_KEY not set — skipping Layer 2 agent check.")
        exitProcess(0)
    }

    // ---- LAYER 1 AUTO-FIX MODE ----
    // Skips the diff review entirely: parses the Layer 1 report and sends the
    // findings straight into the validated propose_edit fixer pipeline.
    if ("--fix-layer1" in args) {
        val reportFile = layer1Path?.let { File(repoRoot, it) }
        if (reportFile == null || !reportFile.exists()) {
            System.err.println("Layer 1 report not found — run the Layer 1 scan first.")
            exitProcess(1)
        }
        val l1Findings = Layer1Parser.parse(reportFile, repoRoot)
        if (l1Findings.isEmpty()) {
            println("No Layer 1 findings to fix.")
            exitProcess(0)
        }
        println("Layer 1 auto-fix: ${l1Findings.size} finding(s) queued for the fixer.")
        FixMode(AnthropicClient(apiKey), repoRoot).run(
            findings = JsonArray(l1Findings),
            applyDirectly = "--apply-fixes" in args
        )
        exitProcess(0)
    }

    val diff = gitDiff(repoRoot)
    if (diff.isBlank()) {
        println("No diff found — nothing to review.")
        exitProcess(0)
    }

    val layer1 = layer1Path?.let { File(repoRoot, it) }
        ?.takeIf { it.exists() }?.readText()?.take(8_000) ?: "(no Layer 1 report)"

    val client = AnthropicClient(apiKey)
    val messages = buildJsonArray {
        add(buildJsonObject {
            put("role", "user")
            put("content",
                "LAYER 1 REPORT:\n$layer1\n\n" +
                "GIT DIFF TO REVIEW:\n```diff\n${diff.take(60_000)}\n```")
        })
    }.toMutableList()

    var finalText: String? = null
    for (turn in 1..MAX_AGENT_TURNS) {
        val response = client.call(SYSTEM_PROMPT, JsonArray(messages), RepoTools.definitions)
        val content = response["content"]!!.jsonArray
        val stopReason = response["stop_reason"]?.jsonPrimitive?.content

        messages += buildJsonObject { put("role", "assistant"); put("content", content) }

        if (stopReason == "tool_use") {
            val results = buildJsonArray {
                content.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" }
                    .forEach { block ->
                        val b = block.jsonObject
                        val name = b["name"]!!.jsonPrimitive.content
                        val input = b["input"]!!.jsonObject
                        println("  agent -> $name(${input})")
                        add(buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", b["id"]!!.jsonPrimitive.content)
                            put("content", RepoTools.execute(name, input))
                        })
                    }
            }
            messages += buildJsonObject { put("role", "user"); put("content", results) }
        } else {
            finalText = content
                .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                .joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }
            break   // final answer received — stop the agent loop
        }
    }

    val raw = finalText ?: error("Agent did not produce a final answer in $MAX_AGENT_TURNS turns")

    // LLMs sometimes wrap the JSON in prose or fences despite instructions.
    // Deterministic extraction first; one corrective retry if that fails.
    fun extractJson(text: String): JsonObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return runCatching {
            Json { ignoreUnknownKeys = true }
                .parseToJsonElement(text.substring(start, end + 1)).jsonObject
        }.getOrNull()
    }

    var report = extractJson(raw)
    if (report == null) {
        println("  (final answer wasn't valid JSON — asking the agent to re-emit)")
        messages += buildJsonObject {
            put("role", "user")
            put("content", "Your previous reply was not valid JSON. Re-send ONLY the JSON verdict object — no prose, no markdown fences.")
        }
        val retry = client.call(SYSTEM_PROMPT, JsonArray(messages), RepoTools.definitions)
        val retryText = retry["content"]!!.jsonArray
            .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            .joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }
        report = extractJson(retryText)
    }
    if (report == null) {
        val rawFile = File(repoRoot, "build/reports/prodcheck-layer2-raw.txt")
        rawFile.parentFile.mkdirs()
        rawFile.writeText(raw)
        error("Could not parse agent verdict as JSON. Raw output saved to ${rawFile.path}")
    }

    // ---- Pretty print + persist ----
    val verdict = report["verdict"]!!.jsonPrimitive.content
    val findings = report["findings"]?.jsonArray ?: JsonArray(emptyList())
    val out = StringBuilder("# ProdCheck Layer 2 Report\n\n**Verdict: $verdict**\n\n")
    findings.forEach {
        val f = it.jsonObject
        out.append("- [${f["severity"]!!.jsonPrimitive.content.uppercase()}] ")
            .append("${f["rule"]!!.jsonPrimitive.content} ")
            .append("`${f["file"]!!.jsonPrimitive.content}:${f["line"]?.jsonPrimitive?.content ?: "?"}` — ")
            .append(f["problem"]!!.jsonPrimitive.content).append("\n")
            .append("  - Fix: ").append(f["fix"]!!.jsonPrimitive.content).append("\n")
    }
    out.append("\n").append(report["summary"]?.jsonPrimitive?.content ?: "")

    val reportFile = File(repoRoot, "build/reports/prodcheck-layer2.md")
    reportFile.parentFile.mkdirs()
    reportFile.writeText(out.toString())
    println("\n$out\nReport written to ${reportFile.path}")

    // ---- AUTO-FIX MODE ----
    if ("--fix" in args && findings.isNotEmpty()) {
        FixMode(client, repoRoot).run(
            findings = findings,
            applyDirectly = "--apply-fixes" in args
        )
    }

    if (verdict == "BLOCK") exitProcess(1)
}
