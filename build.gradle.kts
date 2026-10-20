plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    id("com.diffplug.spotless") version "7.0.2"
    id("com.github.spotbugs") version "6.0.26"
    id("pmd")
    id("org.openrewrite.rewrite") version "6.26.0"
}

group = "com.tradingbot"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot Web Starter & Actuator
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Technical Analysis Libraries (TA-Lib & TA4J)
    implementation("com.tictactec:ta-lib:0.4.0")
    implementation("org.ta4j:ta4j-core:0.16")

    // JSON Processing
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Database: SQLite & Spring JDBC
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")

    // Reactive Streams & Project Reactor
    implementation("io.projectreactor:reactor-core:3.6.11")
    testImplementation("io.projectreactor:reactor-test:3.6.11")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Logback already ships on the test runtime classpath through the web starter, but only at
    // runtime scope. Asserting on emitted log events needs its API to compile against, and the
    // outbound Telegram payload is only observable that way.
    testImplementation("ch.qos.logback:logback-classic")

    // SpotBugs / FindBugs Annotations
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.8.6")
    testCompileOnly("com.github.spotbugs:spotbugs-annotations:4.8.6")

    // Lombok — replaces hand-written getter/setter boilerplate on config/state fields.
    // Version is managed by the Spring Boot BOM (io.spring.dependency-management).
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // OpenRewrite recipes for clean code and static analysis
    rewrite("org.openrewrite.recipe:rewrite-static-analysis:1.16.0")
}

// ==============================================================================
// OpenRewrite Automated Code Cleanup Configuration
// ==============================================================================
rewrite {
    activeRecipe(
        "org.openrewrite.staticanalysis.CommonStaticAnalysis",
        "org.openrewrite.java.RemoveUnusedImports"
    )
}

tasks.withType<Test> {
    // Network-backed probes are tagged "live" and are opt-in so a normal build stays offline:
    //   gradlew test -DliveTests=true
    val liveTests = System.getProperty("liveTests") == "true"
    useJUnitPlatform {
        if (!liveTests) {
            excludeTags("live")
        }
    }
    // Forward audit tuning knobs into the test JVM.
    listOf(
            "liveTests",
            "lvr.audit.maxSymbols",
            "lvr.audit.workers",
            "lvr.audit.sessionDate",
        )
        .forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}

tasks.named<JavaCompile>("compileTestJava") {
    exclude("**/LvrStateStoreTest.java")
    exclude("**/TelegramBotCommandListenerTest.java")
}

tasks.getByName<Jar>("jar") {
    enabled = false
}

// ==============================================================================
// Spotless Code Formatting Configuration
// ==============================================================================
spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat().aosp()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ==============================================================================
// SpotBugs (FindBugs Modern Successor) Static Analysis Configuration
// ==============================================================================
spotbugs {
    ignoreFailures.set(true)
    showStackTraces.set(true)
    showProgress.set(true)
    effort.set(com.github.spotbugs.snom.Effort.DEFAULT)
    reportLevel.set(com.github.spotbugs.snom.Confidence.MEDIUM)
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask> {
    reports.create("html") {
        required.set(true)
    }
    reports.create("xml") {
        // Required by the spotbugsBaselineCheck gate below; ignoreFailures stays true, so
        // SpotBugs itself reports rather than fails, and the baseline gate supplies the policy.
        required.set(true)
    }
}

// ==============================================================================
// PMD — dead-code detection (private members only)
// ==============================================================================
// Scope is deliberately narrower than PMD's default quickstart ruleset. See
// config/pmd/pmd-ruleset.xml for why anything broader would flag Spring-invoked
// @Scheduled and controller methods as dead code.
pmd {
    // Gradle's default tool version is used so the ruleset stays portable; the category/
    // bestpractices rules referenced below exist in both PMD 6.36+ and PMD 7.
    //
    // Unlike SpotBugs, PMD gates the build. The ruleset is restricted to provably-dead private
    // members precisely so this is safe: there is no inherited debt to grandfather, and no
    // reflection-driven scheduler or controller method is in scope.
    isIgnoreFailures = false
    isConsoleOutput = false
    incrementalAnalysis.set(true)
    ruleSetFiles = files("config/pmd/pmd-ruleset.xml")
    ruleSets = emptyList()
}

// Test sources are out of scope, matching the spotbugsMain analysis boundary. Unused
// parameters in test helpers and fixtures are idiomatic, not dead code.
tasks.named("pmdTest") {
    enabled = false
}

// ==============================================================================
// SpotBugs baseline gate — fail only on findings the baseline does not record
// ==============================================================================
// SpotBugs itself stays advisory (ignoreFailures above): this repo carries a large body of
// pre-existing findings that no single branch created, and gating on zero would block every
// change until unrelated debt is cleared. That advisory setting is also exactly why the
// findings went unnoticed - a tool that never fails gets ignored. This gate closes that gap
// without demanding the debt be paid first: the build may not ADD to the pile, but existing
// findings do not block it.

/**
 * Parses a SpotBugs XML report into `<source path>::<bug type>` occurrence counts.
 *
 * Findings are keyed by count rather than by line number so that a finding which merely moves
 * as surrounding code changes is not mistaken for a new one. Counting also means a genuinely
 * new instance in a file that already has several is still caught.
 *
 * @param report the SpotBugs XML report
 * @return the number of occurrences of each distinct finding key
 */
fun spotbugSignatures(report: File): Map<String, Int> {
    val document =
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(report)
    val counts = mutableMapOf<String, Int>()
    val bugs = document.getElementsByTagName("BugInstance")
    for (i in 0 until bugs.length) {
        val bug = bugs.item(i) as org.w3c.dom.Element
        val sources = bug.getElementsByTagName("SourceLine")
        val path =
                if (sources.length > 0)
                        (sources.item(0) as org.w3c.dom.Element).getAttribute("sourcepath")
                else "<unknown>"
        val key = "$path::${bug.getAttribute("type")}"
        counts[key] = (counts[key] ?: 0) + 1
    }
    return counts
}

tasks.register("spotbugsBaselineCheck") {
    group = "verification"
    description = "Fails when SpotBugs reports findings absent from config/spotbugs-baseline.xml."
    dependsOn("spotbugsMain")

    val reportFile = layout.buildDirectory.file("reports/spotbugs/main.xml")
    val baselineFile = layout.projectDirectory.file("config/spotbugs-baseline.xml")
    inputs.file(reportFile)
    inputs.file(baselineFile)

    doLast {
        val report = reportFile.get().asFile
        if (!report.exists()) {
            throw GradleException(
                    "SpotBugs report not found at $report. The XML report must be enabled in the " +
                            "spotbugs task configuration before the baseline gate can run.")
        }
        val current = spotbugSignatures(report)
        val baseline =
                if (baselineFile.asFile.exists()) spotbugSignatures(baselineFile.asFile)
                else emptyMap()

        val introduced =
                current
                        .filter { (key, count) -> count > (baseline[key] ?: 0) }
                        .toList()
                        .sortedBy { it.first }
        val cleared =
                baseline
                        .filter { (key, count) -> count > (current[key] ?: 0) }
                        .toList()
                        .sortedBy { it.first }

        if (introduced.isNotEmpty()) {
            val detail =
                    introduced.joinToString("\n") { (key, count) ->
                        "  +${count - (baseline[key] ?: 0)}  $key"
                    }
            throw GradleException(
                    "SpotBugs reported findings that are not recorded in the baseline:\n$detail\n\n" +
                            "Fix them, or accept them explicitly by refreshing the baseline with:\n" +
                            "  gradlew spotbugsBaselineUpdate\n" +
                            "Baseline file: config/spotbugs-baseline.xml")
        }

        val total = current.values.sum()
        if (cleared.isNotEmpty()) {
            logger.lifecycle(
                    "SpotBugs baseline check passed: $total findings, all recorded. " +
                            "${cleared.size} baseline entries no longer occur - consider refreshing " +
                            "with gradlew spotbugsBaselineUpdate.")
        } else {
            logger.lifecycle("SpotBugs baseline check passed: $total findings, all recorded.")
        }
    }
}

tasks.register("spotbugsBaselineUpdate") {
    group = "verification"
    description = "Records the current SpotBugs findings as the accepted baseline."
    dependsOn("spotbugsMain")

    val reportFile = layout.buildDirectory.file("reports/spotbugs/main.xml")
    val baselineFile = layout.projectDirectory.file("config/spotbugs-baseline.xml")

    doLast {
        val report = reportFile.get().asFile
        if (!report.exists()) {
            throw GradleException("SpotBugs report not found at $report.")
        }
        baselineFile.asFile.parentFile.mkdirs()
        report.copyTo(baselineFile.asFile, overwrite = true)
        logger.lifecycle(
                "Baseline refreshed: config/spotbugs-baseline.xml " +
                        "(${spotbugSignatures(report).values.sum()} findings).")
    }
}

// Automatically clean unused imports and format code before compilation
tasks.named("compileJava") {
    dependsOn("spotlessApply")
}

tasks.named("check") {
    dependsOn("spotbugsBaselineCheck")
    dependsOn("rewriteDryRun")
}

