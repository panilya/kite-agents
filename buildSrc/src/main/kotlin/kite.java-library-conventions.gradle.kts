plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:all",
            "-Xlint:-serial",
            "-Xlint:-processing",
            "-parameters", // critical for @Tool parameter name discovery
        ),
    )
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Automatic-Module-Name" to "io.kite.${project.name.removePrefix("kite-").replace("-", ".")}",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    "testImplementation"(libs.junit.jupiter)
    "testRuntimeOnly"(libs.junit.platform.launcher)
    "testImplementation"(libs.assertj.core)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("live")
    }
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Opt-in: `./gradlew liveTest` runs only @Tag("live") tests, which are gated on provider env vars
// (OPENAI_API_KEY / ANTHROPIC_API_KEY). Hits real APIs with cheap models; run before releases.
tasks.register<Test>("liveTest") {
    group = "verification"
    description = "Runs @Tag(\"live\") tests against real provider APIs. Requires env vars."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("live")
    }
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
    // Always re-run — tests are live and may surface provider drift even when classes are unchanged.
    outputs.upToDateWhen { false }
}
