package com.amalskr.prodcheck.rules

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.lexer.KtTokens

/** #1 — Using !! instead of safe null handling */
class DoubleBangRule(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "DoubleBang",
        severity = Severity.Defect,
        description = "Avoid !!. One unexpected null crashes the app. " +
            "Use ?., ?:, let/run, or requireNotNull with a message.",
        debt = Debt.FIVE_MINS
    )

    override fun visitPostfixExpression(expression: KtPostfixExpression) {
        super.visitPostfixExpression(expression)
        if (expression.operationToken == KtTokens.EXCLEXCL) {
            report(CodeSmell(issue, Entity.from(expression),
                "Replace `!!` with safe null handling (?. / ?: / requireNotNull)."))
        }
    }
}

/** #3 — Long/blocking work on the main thread (heuristic) */
class MainThreadBlockingRule(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "MainThreadBlocking",
        severity = Severity.Performance,
        description = "Possible blocking call outside a background dispatcher. " +
            "Wrap in withContext(Dispatchers.IO) or move to a coroutine.",
        debt = Debt.TWENTY_MINS
    )

    private val blockingCalls = setOf(
        "execute",          // OkHttp/Jsoup synchronous call
        "runBlocking",
        "sleep",            // Thread.sleep
        "readText", "readBytes", "writeText", "writeBytes",
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        // Only flag UI-adjacent files (Activity/Fragment/Screen/@Composable).
        // In repositories/stores/CLI code these calls are usually on a
        // background dispatcher already — that judgement belongs to Layer 2.
        val fileName = expression.containingKtFile.name
        val fileText = expression.containingKtFile.text
        val isUiFile = fileName.endsWith("Activity.kt") ||
            fileName.endsWith("Fragment.kt") ||
            fileName.endsWith("Screen.kt") ||
            "@Composable" in fileText
        if (!isUiFile) return

        val name = expression.calleeExpression?.text ?: return
        if (name !in blockingCalls) return

        // Skip if any enclosing text already shows an IO/Default dispatcher.
        // Heuristic: the agent (Layer 2) does the precise reasoning.
        val enclosing = expression.containingKtFile.text
        val offset = expression.textOffset
        val windowStart = (offset - 400).coerceAtLeast(0)
        val window = enclosing.substring(windowStart, offset)
        if ("Dispatchers.IO" in window || "Dispatchers.Default" in window) return

        report(CodeSmell(issue, Entity.from(expression),
            "`$name` may block the main thread. Confirm it runs on a background dispatcher."))
    }
}

/** #7 — Logging sensitive information */
class SensitiveLogRule(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "SensitiveLog",
        severity = Severity.Security,
        description = "Never log tokens, passwords, personal data or full API responses.",
        debt = Debt.TEN_MINS
    )

    private val logCalls = setOf("d", "e", "i", "v", "w", "wtf", "println", "print")
    private val sensitiveWords = listOf(
        "token", "password", "passwd", "secret", "authorization",
        "apikey", "api_key", "accesskey", "session", "credential",
        "nic", "creditcard", "cvv"
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text?.lowercase() ?: return
        if (callee !in logCalls && !callee.startsWith("log")) return
        val argText = expression.valueArgumentList?.text?.lowercase() ?: return
        if (sensitiveWords.any { it in argText }) {
            report(CodeSmell(issue, Entity.from(expression),
                "Log statement appears to contain sensitive data: strip it or gate behind BuildConfig.DEBUG."))
        }
    }
}

/** #8 — Hardcoded secrets in the app */
class HardcodedSecretRule(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "HardcodedSecret",
        severity = Severity.Security,
        description = "API keys, secrets and environment URLs must not live in source. " +
            "Use BuildConfig fields, gradle properties, or remote config.",
        debt = Debt.TWENTY_MINS
    )

    // High-entropy-ish literals + common key prefixes
    private val patterns = listOf(
        Regex("""AIza[0-9A-Za-z\-_]{30,}"""),          // Google API key
        Regex("""sk-[A-Za-z0-9\-_]{20,}"""),            // generic sk- keys
        Regex("""(?i)bearer\s+[A-Za-z0-9\-_.]{20,}"""),
        Regex("""[A-Za-z0-9+/]{40,}={0,2}"""),          // long base64-looking blob
    )

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)
        val text = expression.text
        if (patterns.any { it.containsMatchIn(text) }) {
            report(CodeSmell(issue, Entity.from(expression),
                "String literal looks like a hardcoded secret. Move it out of source code."))
        }
    }
}
