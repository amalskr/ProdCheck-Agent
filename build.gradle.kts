import io.gitlab.arturbosch.detekt.Detekt

plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

// ---------------------------------------------------------------
// LAYER 1: Deterministic checks (custom Detekt rules, no LLM)
// ---------------------------------------------------------------
detekt {
    // Point this at your Android app's source when you drop prodcheck
    // into a real project (e.g. "app/src/main/kotlin")
    source.setFrom("sample-app-src")
    config.setFrom("config/detekt.yml")
    buildUponDefaultConfig = true
}

dependencies {
    detektPlugins(project(":detekt-rules"))
}

tasks.withType<Detekt>().configureEach {
    reports {
        md.required.set(true)
        md.outputLocation.set(file("build/reports/prodcheck-layer1.md"))
    }
    // Layer 1 findings shouldn't kill the build; the agent consumes them
    ignoreFailures = true
}

// ---------------------------------------------------------------
// LAYER 2: Agentic semantic review (LLM, diff-scoped)
// ---------------------------------------------------------------
tasks.register<JavaExec>("agentCheck") {
    group = "verification"
    description = "Runs the LLM agent over the current git diff"
    dependsOn(":agent:build")
    classpath = project(":agent").extensions
        .getByType<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("com.amalskr.prodcheck.agent.MainKt")
    // Pass the Layer 1 report so the agent doesn't re-check those rules
    args = buildList {
        add("--layer1-report"); add("build/reports/prodcheck-layer1.md")
        add("--repo-root"); add(rootDir.absolutePath)
        add("--fix")                                    // generate fix patch
        if (project.hasProperty("applyFixes")) add("--apply-fixes")
    }
}

// ---------------------------------------------------------------
// The single entry point:  ./gradlew productionCheck
// ---------------------------------------------------------------
tasks.register("productionCheck") {
    group = "verification"
    description = "Layer 1 (Detekt) + Layer 2 (LLM agent) production readiness check"
    dependsOn("detekt")
    finalizedBy("agentCheck")
}
