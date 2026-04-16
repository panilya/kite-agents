plugins {
    id("kite.java-library-conventions")
}

dependencies {
    implementation(project(":kite-core"))
    implementation(project(":kite-openai"))
}

tasks.register<JavaExec>("runSample") {
    group = "application"
    description = "Run a sample. Usage: ./gradlew :kite-samples:runSample -Psample=io.kite.samples.HelloAgent"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set((findProperty("sample") as String?) ?: "io.kite.samples.HelloAgent")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
