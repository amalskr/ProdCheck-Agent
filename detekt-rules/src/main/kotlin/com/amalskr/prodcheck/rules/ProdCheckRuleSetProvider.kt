package com.amalskr.prodcheck.rules

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * LAYER 1 — deterministic production-mistake rules.
 * Covers infographic items: #1 (!!), #3 (main thread work),
 * #7 (sensitive logging), #8 (hardcoded secrets).
 */
class ProdCheckRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "prodcheck"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            DoubleBangRule(config),
            MainThreadBlockingRule(config),
            SensitiveLogRule(config),
            HardcodedSecretRule(config),
        )
    )
}
