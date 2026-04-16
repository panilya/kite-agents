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

dependencies {
    "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.0")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.11.0")
    "testImplementation"("org.assertj:assertj-core:3.26.3")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
