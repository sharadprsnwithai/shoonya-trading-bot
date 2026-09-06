plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    id("com.diffplug.spotless") version "7.0.2"
    id("com.github.spotbugs") version "6.0.26"
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

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // SpotBugs / FindBugs Annotations
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.8.6")
    testCompileOnly("com.github.spotbugs:spotbugs-annotations:4.8.6")
}

tasks.withType<Test> {
    useJUnitPlatform()
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
        required.set(false)
    }
}

